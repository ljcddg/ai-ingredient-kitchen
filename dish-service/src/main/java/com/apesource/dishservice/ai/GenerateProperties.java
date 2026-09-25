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

    /**
     * 营养校验容差。
     *
     * <p>拿「食材主数据 × 用量」本地算一份，与 AI 给的值比，
     * 偏差超过这个比例就认为这次生成可疑（记日志，并让 nutrition_verified = 0）。
     *
     * <p>为什么定 40% 这么宽：本地那份数据本身就是参考值，
     * AI 的估算又有天然波动（实测同一道菜两次差 20%），
     * 阈值定太严会把正常的生成全部误判成可疑。
     */
    private double nutritionTolerance = 0.4;

    /**
     * 生成温度。
     *
     * <p>0.7 是实测下来"有变化但不会跑偏"的值。再高会出现菜名怪异、步骤跳跃。
     */
    private double temperature = 0.7;
}
