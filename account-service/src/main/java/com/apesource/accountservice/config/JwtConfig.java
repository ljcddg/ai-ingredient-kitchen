package com.apesource.accountservice.config;

import com.apesource.commonservice.constant.AuthConstants;
import com.apesource.commonservice.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 配置类。
 * <p>
 * {@link JwtUtil} 本身是 common-service 里的<b>纯静态工具</b>（不依赖 Spring），
 * 好处是 gateway 那种 WebFlux 环境也能直接用，不受容器线程模型影响。
 * <p>
 * 代价是它不能自己 {@code @Component} 注册，得由各服务「谁用谁注册」，
 * 把配置值注入进来 —— 就是在这样的配置类里完成。
 * <p>
 * 注意 {@code expire-millis} 给了默认值兜底：万一 Nacos 拉取失败，
 * 也不至于因为缺一个非关键配置就启动不了服务。
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtUtil jwtUtil(@Value("${" + AuthConstants.JWT_SECRET_PROPERTY + "}") String secret,
                           @Value("${" + AuthConstants.JWT_EXPIRE_PROPERTY + "}") long expireMillis) {
        return new JwtUtil(secret, expireMillis);
    }
}
