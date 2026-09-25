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
}
