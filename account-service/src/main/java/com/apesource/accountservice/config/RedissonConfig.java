package com.apesource.accountservice.config;

import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置。
 *
 * <h2>为什么需要这个类</h2>
 * Redisson 默认是「<b>饿汉式建连</b>」：{@code Redisson.create(config)} 在创建
 * {@code RedissonClient} 对象时就去建立连接池，连不上直接抛
 * {@code RedisConnectionException}。而 {@link com.apesource.accountservice.bloom.UserBloomFilter}
 * 需要构造注入 {@code RedissonClient}，于是<b>整个 Spring 容器启动失败</b>。
 * <p>
 * 实测（把 Redis 端口指到空端口）的失败链条就是：
 * <pre>
 * UnsatisfiedDependencyException: Error creating bean with name 'userBloomFilter'
 *   → BeanCreationException: Error creating bean with name 'redisson'
 *     → RedisConnectionException: Unable to connect to Redis server
 * </pre>
 * 结果就是：<b>Redis 一挂，账号服务根本起不来，注册登录全部不可用</b>。
 * <p>
 * 这里打开<b>懒初始化</b>：真正用到时才建立连接。配合 {@code UserBloomFilter} 里的降级逻辑，
 * Redis 不可用时账号服务依然能正常启动并提供服务，只是退化为「每次都查数据库」。
 * <p>
 * 注：{@code RedissonAutoConfigurationCustomizer} 是 redisson-spring-boot-starter 提供的
 * 官方扩展点，在这里改的 Config 会应用到自动配置生成的客户端上。
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonAutoConfigurationCustomizer redissonLazyInitializationCustomizer() {
        return config -> config.setLazyInitialization(true);
    }
}
