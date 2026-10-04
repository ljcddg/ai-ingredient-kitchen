package com.apesource.dishservice.config;

import com.apesource.dishservice.security.AdminAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册管理员鉴权拦截器。
 *
 * <p>拦的是 {@code /**}（全部路径），但真正做校验的只有
 * 「带 {@code @AdminOnly} 注解」或「落在管理前缀下」的接口 ——
 * 判断逻辑在 {@link AdminAuthInterceptor#preHandle}。
 *
 * <p>为什么不是反过来（只登记管理路径）：那样新增管理接口时忘了登记，
 * 接口就默认对外开放了，而且不会有任何报错。这里宁可多走一层判断。
 */
@Configuration  // 标记此类为配置类，Spring 会自动扫描并注册其中的 Bean
@RequiredArgsConstructor // 使用 Lombok 的构造器注入，自动生成包含 final 字段的构造方法
public class WebMvcConfig implements WebMvcConfigurer { // 实现 WebMvcConfigurer 接口，用于配置 Spring MVC

    private final AdminAuthInterceptor adminAuthInterceptor; // 注入管理员鉴权拦截器

    /**
     * 添加拦截器到 Spring MVC 的拦截器注册表中
     * @param registry 拦截器注册表，用于注册和配置拦截器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 添加管理员鉴权拦截器，并配置其拦截所有路径（/**）
        registry.addInterceptor(adminAuthInterceptor).addPathPatterns("/**");
    }
}
