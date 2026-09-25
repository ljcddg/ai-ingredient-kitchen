package com.apesource.dishservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 跨域配置。
 *
 * <p>为什么本服务也要配一份（网关那边明明已经配了 globalcors）：
 * 前端有两条调用路径，走网关 7000 时跨域由网关统一处理；
 * 但调试时经常直接连本服务 8082，这条路没有网关那层 CORS，
 * 浏览器预检会直接失败，报的是一个和业务无关的跨域错误。两边都配上，两条路都能通。
 *
 * <p><b>安全提示</b>：{@code allowedOriginPatterns("*")} 只适合本地开发，
 * 生产环境必须收紧到具体域名。
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
