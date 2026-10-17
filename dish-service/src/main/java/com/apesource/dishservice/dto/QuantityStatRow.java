package com.apesource.dishservice.dto;

import java.math.BigDecimal;

/**
 * 类别用量分位的一行（{@code t_ingredient_quantity_stats}）。
 *
 * <p>用它做"单品级样本不够时"的兜底：某个食材库里只有一两道菜用过，
 * 单品分位数没有统计意义，但"蔬菜这类食材每人吃多少"是稳的。
 *
 * @param category    食材类目（蔬菜/肉类/主食/…）
 * @param bucket      人数档：1-2 / 3-4 / 5+
 * @param p50         每人份中位用量（g）
 * @param p90         每人份 90 分位用量（g）
 * @param sampleCount 样本数
 */
public record QuantityStatRow(String category, String bucket,
                              BigDecimal p50, BigDecimal p90, Integer sampleCount) {
}
