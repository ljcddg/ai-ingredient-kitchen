package com.apesource.dishservice.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Python ai-service 的 HTTP 客户端。
 *
 * <p>菜谱生成（{@link RemoteDishGenerator}）和营养计算（{@link NutritionCalculator}）共用这一个 client：
 * 地址、共享令牌、读超时都只在 {@link RemoteProperties} 里配一次。
 */
@Configuration
public class AiServiceClientConfig {

    @Bean
    public RestClient aiServiceClient(RemoteProperties remoteProperties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // 读超时必须大于 Python 内部两层（600 < 630），否则 Java 先断，错误信息全错位
        factory.setReadTimeout(Duration.ofSeconds(
                Math.max(1, remoteProperties.getReadTimeoutSeconds())));
        String token = remoteProperties.getToken() == null ? "" : remoteProperties.getToken();
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(remoteProperties.getBaseUrl())
                .defaultHeader("X-Ai-Token", token)
                .build();
    }
}
