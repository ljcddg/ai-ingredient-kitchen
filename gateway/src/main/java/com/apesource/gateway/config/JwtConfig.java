package com.apesource.gateway.config;

import com.apesource.commonservice.constant.AuthConstants;
import com.apesource.commonservice.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关侧的 JWT 配置。
 * <p>
 * gateway 和 account-service 各自 new 一个 {@link JwtUtil}，
 * 但只要读的是<b>同一份 {@code jwt.secret}</b>，
 * 签出来的 token 在网关就能验得过 —— JWT 是无状态的，不需要两端通信。
 * <p>
 * 这也意味着：<b>密钥必须在两边保持一致</b>。本项目把它放在各自的本地
 * {@code application.yml} 里，生产环境应统一改为环境变量或配置中心加密配置。
 */
@Configuration
public class JwtConfig {

    /**
     * 注册 JwtUtil。
     * <p>
     * 之所以用 {@code @Bean} 而不是在 JwtUtil 上打 {@code @Component}：
     * JwtUtil 放在 common-service，如果加了 @Component，
     * account-service 和 gateway 都会扫到，但构造参数依赖配置值，
     * 用 @Bean 显式声明更可控，也让「谁用谁注册」的边界更清晰。
     */
    @Bean
    public JwtUtil jwtUtil(@Value("${" + AuthConstants.JWT_SECRET_PROPERTY + "}") String secret,
                           @Value("${" + AuthConstants.JWT_EXPIRE_PROPERTY + "}") long expireMillis) {
        return new JwtUtil(secret, expireMillis);
    }
}
