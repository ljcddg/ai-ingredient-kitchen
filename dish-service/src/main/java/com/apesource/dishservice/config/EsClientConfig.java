package com.apesource.dishservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Elasticsearch 客户端配置。
 *
 * <h3>为什么不用 Spring Data Elasticsearch</h3>
 * 本机 ES 服务端是 7.16.2，而 Boot 3.2.4 能用的 Spring Data ES 5.2.x
 * 官方兼容目标是 8.11.1；Elastic 对「8.x 客户端连 7.x 服务端」明确
 * {@code without guarantees made}。与其靠兼容模式硬凑，不如直接走 HTTP。
 *
 * <h3>为什么用 RestClient 而不是 RestTemplate</h3>
 * {@code RestClient} 是 Spring 6.1 引入的新同步客户端，RestTemplate 已进入维护态。
 * 本项目 Boot 3.2.4 正好带 Spring 6.1，直接用新的。
 *
 * <h3>为什么不发 compatible-with 头</h3>
 * 那个头是给「8.x 客户端连 7.x 服务端」做 API 降级用的。
 * 我们发的是标准 JSON DSL，7.16 原生认识，加那个头反而多余。
 * 也正因为如此，以后把 ES 换成 8.x，这里的代码一行都不用改。
 */
@Configuration
public class EsClientConfig {

    @Bean
    public RestClient esRestClient(EsProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeout()));
        factory.setReadTimeout(Duration.ofSeconds(properties.getSocketTimeout()));

        return RestClient.builder()
                .baseUrl(properties.getUris())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
