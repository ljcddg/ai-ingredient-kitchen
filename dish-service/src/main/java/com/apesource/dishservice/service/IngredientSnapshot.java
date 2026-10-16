package com.apesource.dishservice.service;

import java.math.BigDecimal;

/**
 * 食材主数据快照的一项 —— 喂给 Python ai-service 的"随请求注入"主数据。
 *
 * <p>为什么用快照而不是让 Python 连库：ai-service 是<b>无状态计算服务</b>，
 * 不碰业务库（解耦原则之一）。主数据只有几十到几百行，每请求带一份的成本
 * 可以忽略，换来的是零缓存一致性问题 —— 食材转正后下一个请求就认得出来。
 *
 * <p>字段与 ai-service 的 DirectoryEntry 一字不差（含营养六项），
 * 改名前先改两边。营养字段是每 100g 的值。
 *
 * @param id      主数据 ID（全局 Jackson 配置会序列化成字符串，防 JS 精度丢失）
 * @param name    标准名
 * @param alias   别名，逗号分隔
 * @param category 类目（蔬菜/肉类/调料…）
 * @param calorie 每 100g 热量（kcal）
 * @param protein 每 100g 蛋白质（g）
 * @param fat     每 100g 脂肪（g）
 * @param carb    每 100g 碳水（g）
 * @param fiber   每 100g 膳食纤维（g）
 * @param sodium  每 100g 钠（mg）
 */
public record IngredientSnapshot(Long id, String name, String alias, String category,
                                 BigDecimal calorie, BigDecimal protein, BigDecimal fat,
                                 BigDecimal carb, BigDecimal fiber, BigDecimal sodium) {
}
