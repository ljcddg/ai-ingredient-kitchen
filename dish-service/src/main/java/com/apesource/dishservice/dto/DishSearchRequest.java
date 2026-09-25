package com.apesource.dishservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 菜品召回请求。
 *
 * <p>注意几个字段的定位（这一点在设计上很容易搞错）：
 * <ul>
 *   <li>{@code ingredientIds} —— 唯一的硬约束，走 ES 倒排召回</li>
 *   <li>{@code tasteTags} —— 只做过滤与加权，【不进匹配键】。
 *       口味是模糊偏好，硬过滤容易把结果集砍到 0 条</li>
 *   <li>{@code servings} —— 只用于呈现层换算用量，【不进匹配键】。
 *       否则同一道菜会因份量不同裂成多条索引记录，命中率再砍一刀</li>
 * </ul>
 */
@Data
public class DishSearchRequest {

    /**
     * 用户选中的食材主数据 ID 列表。
     * 上限 10 与 Nacos 上的 {@code es.recall.max-ingredients} 保持一致 ——
     * Service 层还会用配置值再校验一次，两边都要拦。
     *
     * <p><b>这里不能加 {@code @NotEmpty}</b>：用户完全可能<b>只</b>输入食材表之外的食材
     * （手边只有「松露」），那种情况下本字段是空的，但请求依然合法。
     * 「至少要有一种食材」由 Service 层按 {@code ingredientIds + freeIngredients} 的总数判定。
     */
    @Size(max = 10, message = "一次最多选择 10 种食材")
    private List<Long> ingredientIds;

    /**
     * 自由输入的食材名（食材表里没有的，如「松露」）。
     *
     * <p>它们没有主数据 ID，但<b>照样参与召回</b> —— 走 ES 的
     * {@code ingredientNames} 字段（见 {@link com.apesource.dishservice.es.DishDoc}）。
     * 这是「手边有什么就能用什么」这个承诺的一部分。
     */
    @Size(max = 10, message = "一次最多选择 10 种食材")
    private List<String> freeIngredients;

    /** 口味标签，如 ["微辣", "家常"]。可空 */
    private List<String> tasteTags;

    /** 用餐人数。可空，只影响前端展示的用量换算 */
    @Min(value = 1, message = "人数至少为 1")
    @Max(value = 20, message = "人数最多为 20")
    private Integer servings;

    /**
     * 是否允许「相似召回」（允许缺部分食材）。
     * 默认 false —— 先按硬约束找「完全覆盖所选食材」的菜，
     * 一条都没有时再由上层决定要不要放开。
     */
    private Boolean allowPartial = Boolean.FALSE;

    /** 页码，从 1 开始 */
    @Min(value = 1, message = "页码从 1 开始")
    private Integer page = 1;

    /** 每页条数 */
    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 50, message = "每页最多 50 条")
    private Integer size = 10;
}
