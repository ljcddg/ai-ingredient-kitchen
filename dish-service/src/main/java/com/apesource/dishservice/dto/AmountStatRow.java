package com.apesource.dishservice.dto;

import java.math.BigDecimal;

/**
 * 单品用量分位的一行（{@code t_ingredient_amount_stats}）。
 *
 * <p>为什么是行对象而不是实体：这两张统计表是 {@code sql/build-stats.py} 的离线产物，
 * 服务端<b>只读</b>、从不 insert/update。用实体 + BaseMapper 会凭空多出一堆没人用的
 * 写方法（insert/updateById），下一个人读到会以为服务里有代码在写它。
 * 直接一个 record 接 @Select 结果，语义就是"一次查询的投影"。
 *
 * @param ingredientId 食材主数据 ID
 * @param bucket       人数档：1-2 / 3-4 / 5+
 * @param p50          每人份中位用量（g）
 * @param p90          每人份 90 分位用量（g）—— 校验上限的基准
 * @param sampleCount  样本数
 */
public record AmountStatRow(Long ingredientId, String bucket,
                            BigDecimal p50, BigDecimal p90, Integer sampleCount) {
}
