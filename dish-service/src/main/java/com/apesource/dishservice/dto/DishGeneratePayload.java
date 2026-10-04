package com.apesource.dishservice.dto;

import java.util.List;

/**
 * 生成任务的请求快照 —— 会被序列化成 JSON 存进 {@code t_ai_generate_task.request_json}。
 *
 * <p>为什么要存快照而不是只存一个 signature：任务真正执行时可能已经过去几秒到几十秒，
 * 期间用户可能改了选择、甚至换了账号。执行时必须用"提交那一刻的参数"，
 * 否则生成出来的东西和用户看到的不一致。
 *
 * <p>用 record 依赖 Jackson 的参数名推断，而参数名需要编译期 {@code -parameters}
 * （父 pom 里已开）。这也是那次 500 报错换来的经验。
 */
public record DishGeneratePayload(List<IngredientRef> ingredients, List<String> tastes, int servings,
                                  GenerateConstraints constraints) {

    /**
     * 约束（老任务兜底）。
     *
     * <p>约束字段是后加的，库里 2026-10 之前提交的任务快照里没有这个 key，
     * 反序列化出来是 null。所有读取方都走这里拿，就不会有人忘了判空 ——
     * 忘了的表现是 <b>NPE 把任务打成失败</b>，而失败原因只写一句"落库失败"。
     */
    public GenerateConstraints constraintsOrNone() {
        return constraints == null ? GenerateConstraints.none() : constraints;
    }
}
