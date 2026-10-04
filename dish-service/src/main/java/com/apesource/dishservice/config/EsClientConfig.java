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
 * Boot 3.2.4 能用的 Spring Data ES 5.2.x，官方兼容目标是 ES 8.11.1；
 * 服务端最初是原生解压版 7.16.2（明确不在兼容范围内），现在换成了
 * Docker 容器里的 {@code elasticsearch:8.16.0} —— 更接近了，但仍不是官方
 * 钉死的那个版本。手写 RestClient 的价值在于<b>零版本耦合</b>：同一份代码
 * 在 7.x 和 8.x 上都能跑。这次从 7.16.2 升到 8.16.0 业务代码一行未改，
 * 正是这个决定的回报。
 *
 * <h3>为什么用 RestClient 而不是 RestTemplate</h3>
 * {@code RestClient} 是 Spring 6.1 引入的新同步客户端，RestTemplate 已进入维护态。
 * 本项目 Boot 3.2.4 正好带 Spring 6.1，直接用新的。
 *
 * <h3>为什么不发 compatible-with 头</h3>
 * 那个 vendor 头（{@code application/vnd.elasticsearch+json;compatible-with=7}）
 * 是官方 7.x Java 客户端为了连 8.x 服务端做 API 降级才需要的。
 * 我们发的是标准 JSON DSL，ES 8 原生接受 {@code application/json}，
 * 加那个头反而多余 —— 也正因如此，换服务端版本时这里一行都不用改。
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
