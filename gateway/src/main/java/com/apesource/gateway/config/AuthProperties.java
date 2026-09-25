package com.apesource.gateway.config;

import com.apesource.commonservice.constant.AuthConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 认证白名单配置。
 * <p>
 * 格式（application.yml）：
 * <pre>
 * gateway:
 *   auth:
 *     enabled: true                 # 总开关，本地调试可关掉一次性放行
 *     white-list:
 *       - /auth/**                  # 登录注册本身不能要求登录
 *       - /actuator/**
 * </pre>
 * <p>
 * 用 {@code @ConfigurationProperties} 而不是 {@code @Value}：
 * 列表型配置用 @Value 要写成 SpEL 表达式，难读又容易踩坑；
 * {@code @ConfigurationProperties} 是 Spring Boot 官方推荐的类型安全姿势，
 * 配置写错时启动阶段就能发现，而不是运行时静默失效。
 */
@Component
@ConfigurationProperties(prefix = "gateway.auth")
public class AuthProperties {

    private static final Logger log = LoggerFactory.getLogger(AuthProperties.class);

    /** Ant 风格路径匹配器，支持 {@code /auth/**} 这种通配 */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 是否开启校验，默认开启；写成配置项是为了本地联调时能一把关掉 */
    private boolean enabled = true;

    /** 免认证路径 */
    private List<String> whiteList = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getWhiteList() {
        return whiteList;
    }

    public void setWhiteList(List<String> whiteList) {
        this.whiteList = whiteList;
    }

    /**
     * 判断请求路径是否免认证。
     * <p>
     * 注意用的是 {@code getPathWithinApplication()} 拿到的路径，
     * 要保证调用方传进来的是「去掉网关自身前缀后」的路径，
     * 否则 {@code /api/auth/login} 匹配不上 {@code /auth/**}。
     *
     * @param path 请求路径
     * @return true 表示免认证
     */
    public boolean isWhiteListed(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String pattern : whiteList) {
            if (MATCHER.match(pattern, path)) {
                log.debug("路径[{}]命中白名单规则[{}]，放行", path, pattern);
                return true;
            }
        }
        return false;
    }

    /**
     * 校验网关自身配置是否自洽，启动时调用一次。
     * <p>
     * 这里检查的不是业务，而是「配置错误」——比如把 {@code /auth/**} 的通配
     * 写成 {@code /auth/*}，会导致 {@code /auth/login/xxx} 被拦截，
     * 排查起来毫无头绪。提前在启动日志里提示，能省下大量时间。
     */
    public void validate() {
        if (!enabled) {
            log.warn("网关 JWT 校验已关闭（gateway.auth.enabled=false），仅限本地调试！");
            return;
        }
        boolean hasAuthPattern = whiteList.stream().anyMatch(p -> p.startsWith("/auth"));
        if (!hasAuthPattern) {
            log.warn("白名单中未包含 /auth/**，登录注册接口也会被要求携带 token，请确认是否符合预期");
        }
        log.info("网关 JWT 校验已开启，白名单规则：{}", whiteList);
    }
}
