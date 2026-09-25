package com.apesource.gateway.filter;

import com.apesource.commonservice.constant.AuthConstants;
import com.apesource.commonservice.result.Result;
import com.apesource.commonservice.util.JwtUtil;
import com.apesource.gateway.config.AuthProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全局 JWT 认证过滤器。
 * <p>
 * 职责：在请求进入具体业务服务之前，统一完成「解析 token → 校验 → 透传身份」，
 * 这样每个下游服务就不用各写一套拦截器了。
 * <p>
 * <b>四个关键设计点：</b>
 * <ol>
 *   <li><b>为什么是 GlobalFilter 而不是 GatewayFilter</b>：
 *       全局过滤器对所有路由生效，不用在每条 route 上都配一遍；</li>
 *   <li><b>为什么 order 是 -100</b>：
 *       {@code NettyRoutingFilter} 的 order 是 {@code Integer.MAX_VALUE}，
 *       只要排在它前面就一定会在转发之前执行。用负数是为了和业务侧过滤器（通常 0~100）拉开距离；</li>
 *   <li><b>白名单要用「剥掉路由前缀后」的路径去匹配</b>：
 *       前端请求的是 {@code /api/account/auth/login}，但白名单里写的是 {@code /auth/**}。
 *       如果不剥前缀直接匹配，登录接口会被自己的认证过滤器拦下 —— 死循环，谁都登不进去。
 *       （实际踩过这个坑，见下面的 {@link #matchPath}）</li>
 *   <li><b>为什么必须删掉外部传入的 X-User-Id</b>：
 *       这是最容易出事的地方。如果不管，攻击者只要在请求头里自己写一个
 *       {@code X-User-Id: 1} 就能冒充管理员 —— 网关必须把客户端传来的同名头<b>清掉</b>，
 *       再写入自己校验出来的值，即「以网关的结论为准」。</li>
 * </ol>
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGlobalFilter.class);

    /**
     * 需要从路径中剥掉的网关前缀。
     * <p>
     * 这些前缀和 {@code gateway.yaml} 里路由的 {@code StripPrefix} 值必须对应，
     * 否则白名单会匹配不上。加前缀时这里要一起改。
     */
    private static final List<String> STRIP_PREFIXES =
            List.of("/api/account", "/api/dish", "/api/product", "/api/order");

    private final JwtUtil jwtUtil;

    private final AuthProperties authProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthGlobalFilter(JwtUtil jwtUtil, AuthProperties authProperties) {
        this.jwtUtil = jwtUtil;
        this.authProperties = authProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 总开关：本地调试可以整体关掉
        if (!authProperties.isEnabled()) {
            return chain.filter(exchange);
        }

        // 2. 白名单放行：登录、注册这些接口本来就没有 token。
        //    注意这里匹配的是「剥掉路由前缀后」的路径，理由见类注释第 3 点。
        String matchPath = matchPath(path);
        if (authProperties.isWhiteListed(matchPath)) {
            return chain.filter(exchange);
        }

        // 3. 取 token：先剥掉 "Bearer " 前缀
        String authorization = request.getHeaders().getFirst(AuthConstants.HEADER_AUTHORIZATION);
        String token = JwtUtil.resolveToken(authorization);
        if (token == null) {
            log.warn("请求[{} {}]未携带合法的 Authorization 头，已拒绝", request.getMethod(), path);
            return reject(exchange, "未登录或登录已过期，请重新登录");
        }

        // 4. 验签 + 过期校验。parseToken 内部已捕获所有异常，失败返回 null
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            log.warn("请求[{} {}]的 token 校验失败（签名错误或已过期），已拒绝", request.getMethod(), path);
            return reject(exchange, "登录状态无效，请重新登录");
        }

        Long userId = resolveUserId(claims);
        if (userId == null) {
            log.warn("token 中缺少 userId claim，无法识别用户身份");
            return reject(exchange, "登录状态异常，请重新登录");
        }

        String username = claims.get(AuthConstants.CLAIM_USERNAME, String.class);

        // 5. 关键：先删掉外部传入的同名头，再写入网关校验后的值，防止身份伪造
        ServerHttpRequest mutated = request.mutate()
                .headers(headers -> {
                    headers.remove(AuthConstants.HEADER_USER_ID);
                    headers.remove(AuthConstants.HEADER_USERNAME);
                })
                .header(AuthConstants.HEADER_USER_ID, String.valueOf(userId))
                .header(AuthConstants.HEADER_USERNAME, username == null ? "" : username)
                .build();

        log.debug("认证通过：userId={}, username={}, path={}", userId, username, path);
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 把请求路径转换成「下游服务看到的路径」，用于和 route 的 StripPrefix 保持一致。
     * <p>
     * 例：{@code /api/account/auth/login} → {@code /auth/login}
     * <p>
     * 之所以要这么做：白名单写的是下游视角的路径（{@code /auth/**}），
     * 而网关收到的是带前缀的（{@code /api/account/auth/login}）。
     * 这俩不一致时，登录接口会被网关当成「受保护资源」直接 401 ——
     * 表现就是「不管怎样都登不进去」，且没有任何报错提示，很难查。
     *
     * @param path 网关收到的完整路径
     * @return 剥掉前缀后的路径；无需剥离时原样返回
     */
    private String matchPath(String path) {
        for (String prefix : STRIP_PREFIXES) {
            if (path.equals(prefix)) {
                return "/";
            }
            if (path.startsWith(prefix + "/")) {
                return path.substring(prefix.length());
            }
        }
        return path;
    }

    /**
     * 从 Claims 里取 userId。
     * <p>
     * claim 里的数字经 Jackson 反序列化后可能是 Integer 也可能是 Long，
     * 直接强转成 Long 会踩 ClassCastException，所以统一按 Number 处理。
     */
    private Long resolveUserId(Claims claims) {
        Object raw = claims.get(AuthConstants.CLAIM_USER_ID);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        // 兼容只用 subject 存 userId 的 token
        String subject = claims.getSubject();
        if (subject != null && subject.matches("\\d+")) {
            return Long.parseLong(subject);
        }
        return null;
    }

    /**
     * 拒绝请求：直接写回统一格式的 JSON，不再转发给下游。
     * <p>
     * 注意这里必须给响应真正「结束」掉 —— WebFlux 是异步的，
     * 手动写响应体要用 {@code response.writeWith(...)}，
     * 不能像 Servlet 那样只设置状态码就返回。
     */
    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        // HTTP 状态码用 401，业务 code 也用 401，前端判断任一都能识别「需要登录」
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // 避免中文乱码
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");

        Result<Void> body = Result.unauthorized(message);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            // 序列化失败兜底，保证一定能返回点什么
            bytes = "{\"code\":401,\"message\":\"登录状态无效\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 过滤器顺序。
     * <p>
     * 负数的意义：{@code NettyRoutingFilter}（真正发起下游调用）的 order 是
     * {@code Integer.MAX_VALUE}，只要小于它就会在转发前执行。
     * 取 -100 是为了排在绝大多数业务过滤器之前 —— 认证必须是最早的一道关卡。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
