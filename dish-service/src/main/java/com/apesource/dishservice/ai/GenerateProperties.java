package com.apesource.dishservice.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 生成相关的可调参数。
 *
 * <p>这些都是「和小模型打交道的经验值」，换模型（5B → 14B）后很可能要重新调，
 * 所以全部做成配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.generate")
public class GenerateProperties {

    /**
     * 最多尝试几次（含第一次）。
     *
     * <p>默认 2 —— 也就是"失败重试 1 次"。为什么不给更多：
     * 每次生成要 10 秒上下，用户等不起；而且小模型在同一个坑里连栽三次的概率并不低，
     * 与其烧资源不如老实告诉他"生成失败，请重试"。
     */
    private int maxAttempts = 2;

    // nutritionTolerance 已随迁移删除（2026-10-16）：营养偏差校验（AI 估算 vs 成分表）
    // 的实现和阈值都在 Python ai-service 的 nutrition.py，Java 侧不再参与这个比对。

    /**
     * 生成温度。
     *
     * <p>0.7 是实测下来"有变化但不会跑偏"的值。再高会出现菜名怪异、步骤跳跃。
     */
    private double temperature = 0.2;

    /**
     * 是否用库内相似菜谱做参考增强（说明文档 P2-1）。
     *
     * <p>做成开关是为了做对照实验：同一批查询分别跑"直接生成"和"参考增强"两臂，
     * 比较格式通过率、约束违反率、重试次数与人工评分。
     */
    private boolean enhanceEnabled = true;

    /**
     * 注入几条参考菜谱。
     *
     * <p>3 条是实测的平衡点：再多，提示词里全是别人的菜，
     * 模型容易把参考当答案照抄；再少，又起不到"看看库里的同类菜长什么样"的作用。
     */
    private int referenceLimit = 3;

    /**
     * 参考菜谱里每条塞几步做法要点。
     *
     * <p>只给前两步：够模型看出手法，又不至于把整道菜抄下来。
     */
    private int referenceStepHints = 2;

    /**
     * 单次模型调用的超时（秒）。
     *
     * <p>为什么必须设：实测遇到过单次生成卡住 6 分钟以上（小模型在矛盾约束下反复"思考"），
     * 而同步调用会一直占着队列线程，用户界面只显示"生成中"，没有任何提示。
     * 有了超时，用户能在有限时间内拿到一句明确的失败原因。
     *
     * <p><b>阈值定多少是有实测依据的</b>（2026-10-02 负载实验）：
     * 单请求独占模型时一次生成约 40~175 秒；但两个任务同时在飞时，
     * Ollama 并行度为 1，两个请求分时复用同一个模型实例，单次调用很容易超过 240 秒 ——
     * 用 240 秒做阈值时，6 个任务<b>全部</b>被误判成"超时失败"。
     * 所以放宽到 600 秒：超时的用途是兜住"真的卡死"，不是把"并发下变慢"判成失败。
     *
     * <p>注意底层 HTTP 请求不会因为这个超时被取消（和"用户取消"是同一种语义：
     * 应用侧放手，模型侧可能还在跑、还占着 GPU）。
     */
    private int attemptTimeoutSeconds = 600;

    /**
     * 降级配额：检索引擎不可用时，每个用户每小时最多提交几次生成。
     *
     * <p>说明文档的要求：<b>避免检索故障把所有请求都转移到本地模型</b>。
     * 检索挂了的时候，页面上"没有合适的菜"会自然把人推向"让 AI 现做"，
     * 而本地只有一个模型、队列并发只有 2 —— 不限流的话，
     * 一次 ES 抖动就能把生成队列堵死几十分钟。
     */
    private int degradedQuotaPerHour = 5;
}
