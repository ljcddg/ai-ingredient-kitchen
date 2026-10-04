package com.apesource.dishservice.dto;

import lombok.Data;

import java.util.List;

/**
 * 提交生成请求（前端 → 后端）。
 *
 * <p>食材有两个来源，所以分成两个字段而不是一个：
 * <ul>
 *   <li>{@code ingredientIds} —— 用户从列表里点选的，有主数据 ID</li>
 *   <li>{@code freeIngredients} —— 用户在输入框里自由敲的，可能压根不在主数据里</li>
 * </ul>
 * 两者最终会被归一成统一的 {@link IngredientRef} 列表再交给生成器。
 * 不合并成一个字段是因为"有没有 ID"这个信息后面还要用
 * （决定它能不能参与营养校验、能不能进倒排索引）。
 */
@Data
public class DishGenerateRequest {

    /** 从列表里选的食材 ID */
    private List<Long> ingredientIds;

    /** 自由输入的食材名（去重前） */
    private List<String> freeIngredients;

    /** 口味标签，可为空 */
    private List<String> tastes;

    /** 人数 */
    private Integer servings;

    // ---------- 生成约束（说明文档第三节：把用户条件真正约束到生成结果上） ----------

    /**
     * 必用食材（从列表里点选的）。
     * <p>和 {@link #ingredientIds} 的区别：那些是"手边有的"，
     * 这些是"这道菜必须用上的"。留空表示不作要求。
     */
    private List<Long> mustUseIngredientIds;

    /** 必用食材（自由输入的名字） */
    private List<String> mustUseIngredients;

    /** 禁用食材（主数据 ID）—— 过敏、忌口、宗教禁忌，出现即判不合格 */
    private List<Long> excludeIngredientIds;

    /** 禁用食材（名字，含表外食材） */
    private List<String> excludeIngredients;

    /** 最长总耗时（备菜 + 下锅，分钟）。可空表示不限 */
    private Integer maxCookTimeMin;

    /** 用户手上有的厨具。可空表示不限制；非空时菜谱要求的设备必须是它的子集 */
    private List<String> availableEquipment;

    /** 打包成值对象传给后面几层 —— 六个参数一路传下去太容易漏 */
    public GenerateConstraints toConstraints() {
        GenerateConstraints constraints = new GenerateConstraints(
                mustUseIngredientIds, mustUseIngredients,
                excludeIngredientIds, excludeIngredients,
                maxCookTimeMin, availableEquipment);
        return constraints.noConstraint() ? GenerateConstraints.none() : constraints;
    }
}
