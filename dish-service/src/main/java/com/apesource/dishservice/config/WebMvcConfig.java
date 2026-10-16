package com.apesource.dishservice.config;

import com.apesource.dishservice.ai.VisionProperties;
import com.apesource.dishservice.security.AdminAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Web 层配置：管理员鉴权拦截器 + 识菜上传目录的静态资源映射。
 *
 * <p>拦截器拦的是 {@code /**}（全部路径），但真正做校验的只有
 * 「带 {@code @AdminOnly} 注解」或「落在管理前缀下」的接口 ——
 * 判断逻辑在 {@link AdminAuthInterceptor#preHandle}。
 *
 * <p>为什么不是反过来（只登记管理路径）：那样新增管理接口时忘了登记，
 * 接口就默认对外开放了，而且不会有任何报错。这里宁可多走一层判断。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    /** 识菜上传目录：/uploads/** 要能直接访问（前端展示原图与裁剪小图） */
    private final VisionProperties visionProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/**")
                // 图片是静态资源，不走管理员鉴权（要登录才能看的部分由 gateway 的
                // /uploads/** 路由 + JWT 过滤器保证，见 nacos-config/gateway.yaml）
                .excludePathPatterns("/uploads/**");
    }

    /**
     * /uploads/** → 上传根目录（{@code ai.vision.upload-dir} 的父目录）。
     *
     * <p>例：upload-dir=uploads/vision → 映射 ./uploads/，于是
     * /uploads/vision/2026/10/xxx.jpg 与 /uploads/vision/crops/img-1_0.jpg 都能取到。
     *
     * <p>注意这是**本服务**的映射；前端拿到的是相对路径 /uploads/**，
     * 必须经 gateway 的同名路由转发过来（那条路由 StripPrefix=0，不能剥前缀）。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadRoot = Path.of(visionProperties.getUploadDir()).toAbsolutePath().normalize();
        Path parent = uploadRoot.getParent() != null ? uploadRoot.getParent() : uploadRoot;
        String location = parent.toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}

