package com.apesource.dishservice.security;

import com.apesource.commonservice.constant.AuthConstants;
import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.mapper.UserRoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 管理员鉴权拦截器。
 *
 * <p><b>为什么鉴权要放在业务服务里，而不是只靠网关：</b>
 * 网关解决的是「你是谁」（验签 token、注入 {@code X-User-Id}），
 * 它管不了「你能干什么」—— 角色和资源归属都是业务语义。
 * 更关键的是：网关注入的请求头本身是可伪造的，
 * 只要有人直连了 8082，自己写一个 {@code X-User-Id} 就绕过了网关。
 * 所以凡是涉及权限的判断，一律回源查库（见 {@link UserRoleMapper}）。
 *
 * <p><b>哪些接口算管理接口：</b>两个条件满足其一即可
 * <ol>
 *   <li>方法或类上有 {@link AdminOnly} 注解；</li>
 *   <li>请求路径落在管理接口前缀下（{@link #ADMIN_PATH_PREFIXES}）。</li>
 * </ol>
 * 之所以还要有第 2 条：光靠注解的话，在管理前缀下新增一个接口却忘了加注解，
 * 它就会默认对外开放 —— 这种事故不会报错，只会静默提权。
 * 路径前缀是兜底，注解是显式声明，两者取「或」更安全。
 *
 * <p><b>失败一律关闭（fail-closed）：</b>查库抛异常时不放行。
 * 异常会冒泡到 {@code GlobalExceptionHandler} 变成 500，用户看到的是"服务器开小差了"，
 * 而不是"没有权限" —— 这是有意的：拿不到结论时不能给出任何权限。
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthInterceptor.class);

    /**
     * 管理接口路径前缀。
     * <p>
     * 新增管理接口时优先挂到这些前缀下，这样即使忘了加 {@link AdminOnly} 也仍然是受保护的。
     */
    private static final String[] ADMIN_PATH_PREFIXES = {"/ai/es/", "/ai/ingredients/", "/ai/admin/"};

    private final UserRoleMapper userRoleMapper;

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // 静态资源、错误页这些 handler 不是 HandlerMethod，直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (!needsAdmin(handlerMethod, request)) {
            return true;
        }

        Long userId = parseUserId(request.getHeader(AuthConstants.HEADER_USER_ID));
        if (userId == null) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    Result.unauthorized("未登录或登录状态无效"));
            return false;
        }

        Integer role = userRoleMapper.selectRoleById(userId);
        if (role == null || role != AuthConstants.ROLE_ADMIN) {
            log.warn("拒绝非管理员访问管理接口：userId={}, role={}, {} {}",
                    userId, role, request.getMethod(), request.getRequestURI());
            write(response, HttpServletResponse.SC_FORBIDDEN, Result.forbidden("需要管理员权限"));
            return false;
        }

        log.info("管理员 {} 访问管理接口 {} {}", userId, request.getMethod(), request.getRequestURI());
        return true;
    }

    /** 注解声明 或 路径前缀命中，都算管理接口 */
    private boolean needsAdmin(HandlerMethod handlerMethod, HttpServletRequest request) {
        if (handlerMethod.hasMethodAnnotation(AdminOnly.class)
                || AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), AdminOnly.class) != null) {
            return true;
        }
        String path = request.getRequestURI();
        for (String prefix : ADMIN_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 {@code X-User-Id}。
     * <p>
     * 解不出来就返回 null（调用方按未登录处理），不抛异常 ——
     * 非法请求头只配得到 401，不该在日志里刷一屏堆栈。
     */
    private Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("X-User-Id 不是合法数字：{}", raw);
            return null;
        }
    }

    /**
     * 直接写回统一格式的 JSON。
     * <p>
     * HTTP 状态码和业务 code 保持一致（401/403 都是双写），
     * 前端判断任一个都能识别 —— 和网关拒绝请求时的做法完全一样，
     * 避免"网关拒绝是 401、服务拒绝却是 200"这种不一致。
     */
    private void write(HttpServletResponse response, int httpStatus, Result<Void> body) throws IOException {
        response.setStatus(httpStatus);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
