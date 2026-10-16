package com.apesource.dishservice.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 识菜客户端注册。
 *
 * <p>{@link VisionClient} 不加 {@code @Component}：它需要构造期做一次
 * HttpClient / RestClient 装配（与 {@link RemoteDishGenerator} 同一模式，
 * 由配置类显式注册，构造参数清晰可见）。
 */
@Configuration
public class VisionClientConfig {

    @Bean
    public VisionClient visionClient(VisionProperties visionProperties) {
        return new VisionClient(visionProperties);
    }
}
