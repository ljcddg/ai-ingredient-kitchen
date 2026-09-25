package com.apesource.dishservice.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 生成队列的配置。
 *
 * <p>为什么这些值必须是配置项而不是常量：
 * 换机器、换模型（从 5B 换到 14B）、换部署方式（本机 → 有 GPU 的机器）
 * 都会改变"同时能跑几个"这个答案，写死了就得改代码重新打包。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.queue")
public class QueueProperties {

    /**
     * 同时执行的生成任务数。
     *
     * <p>默认 <b>1（串行）</b> —— 这是刻意的选择，不是偷懒：
     * 本地 gemma4:e2b 实测热态 3~4 秒一次，串行完全够用；
     * 而小模型在有并发时会互相抢资源，总吞吐反而可能下降。
     * 只有实测确认并发不掉速时才调大。
     */
    private int concurrency = 1;

    /**
     * 单个用户最多能同时占几个队列位置。
     *
     * <p>不限的话，用户连点几次就能塞几十个任务，把队列排到天荒地老，
     * 其他人的请求全被堵在后面。
     */
    private int maxPerUser = 5;
}
