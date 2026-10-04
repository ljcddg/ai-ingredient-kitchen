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
     * <p><b>它必须和 Ollama 服务端的并行能力配套看</b>，否则只是把请求从"应用队列"
     * 挪到"模型队列"里排队：
     * <ul>
     *   <li>这里 = 2，而 Ollama 是 {@code OLLAMA_NUM_PARALLEL=1} 时，第二个请求进了
     *       Ollama 仍然要排队等前一个跑完 —— 用户等待时间一点没少，只是多占了一个应用侧线程；</li>
     *   <li>直接把 Ollama 提到 2 也不行：本机 RTX 4060 Laptop 只有 8G 显存，
     *       并行槽位会让 KV cache 翻倍（2 × 40960 上下文），实测显存不够、
     *       Ollama 自动回退到 CPU（ps 显示 97% CPU），连单请求都从 2.5s 劣化到 7~8s。</li>
     * </ul>
     * 结论：这里保留 2 表示"允许两个任务轮流使用模型"，真正的并行
     * 要等 Ollama 的上下文调小（腾出显存）之后才有意义。</p>
     */
    private int concurrency = 2;

    /**
     * 单个用户最多能同时占几个队列位置。
     *
     * <p>不限的话，用户连点几次就能塞几十个任务，把队列排到天荒地老，
     * 其他人的请求全被堵在后面。
     */
    private int maxPerUser = 5;
}
