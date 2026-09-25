package com.apesource.dishservice;

import com.apesource.dishservice.config.EsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 菜品服务启动类。
 *
 * <p>职责边界：
 * <ul>
 *   <li>食材组合召回：用 Elasticsearch 做倒排索引召回（主路径）</li>
 *   <li>菜品详情、评分、收藏：落 MySQL</li>
 * </ul>
 *
 * <p>本服务不直接对外暴露，所有请求经 gateway 转发，
 * 用户身份从网关注入的 {@code X-User-Id} 请求头读取。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(EsProperties.class)
// 菜品索引的定时全量兜底需要它（DishEsSyncListener.scheduledReindex）
@EnableScheduling
public class DishServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DishServiceApplication.class, args);
    }
}
