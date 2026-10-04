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
     *
     * <p>它的语义在本次改动后有了变化，<b>保留只是为了不打断前端</b>：
     * <ul>
     *   <li>旧语义：false = 菜必须包含你选的<b>全部</b>食材（ES 侧 must 硬过滤）。</li>
     *   <li>新语义：它只是 {@link #mode} 的简写 —— true 等价于 {@code loose}。
     *       因为"必须用上你冰箱里的每一样"这个约束本身就是错的：
     *       你手上有番茄、鸡蛋、牛肉、奶酪，不代表要有一道菜把四样全用上。</li>
     * </ul>
     * 显式传了 {@code mode} 时以 {@code mode} 为准。
     */
    private Boolean allowPartial = Boolean.FALSE;

    /**
     * 召回模式：
     * <ul>
     *   <li>{@code strict}（默认，「只用现有食材」）：<b>缺料数必须为 0</b>，
     *       即菜谱要求的主料你都有。注意它<b>不要求</b>你把手边所有食材都用上。</li>
     *   <li>{@code loose}（「允许补充少量食材」）：缺料数 ≤ {@link #maxMissing}。</li>
     * </ul>
     */
    private String mode;

    /** loose 模式下允许缺几样主料，默认 2 */
    @Min(value = 0, message = "允许缺料数不能为负")
    @Max(value = 10, message = "允许缺料数最多 10 样")
    private Integer maxMissing;

    /**
     * 禁用食材（ID）：菜里出现任何一样就整条淘汰。
     * <p>过敏/忌口属于硬约束，不能只做扣分 —— 扣完分它还是可能排进前五。
     */
    private List<Long> excludeIngredientIds;

    /** 禁用食材（名字，含表外食材），与 {@link #excludeIngredientIds} 取并集 */
    private List<String> excludeIngredients;

    /** 最长耗时（分钟）：超过就淘汰。可空表示不限 */
    @Min(value = 1, message = "最长耗时至少 1 分钟")
    @Max(value = 600, message = "最长耗时最多 600 分钟")
    private Integer maxCookTimeMin;

    /** 页码，从 1 开始 */
    @Min(value = 1, message = "页码从 1 开始")
    private Integer page = 1;

    /** 每页条数 */
    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 50, message = "每页最多 50 条")
    private Integer size = 10;

    /**
     * 排序策略覆盖：{@code satisfaction}（默认）或 {@code legacy}。
     *
     * <p>留这个入口是为了做<b>对照实验</b>：同一批查询要在同一份菜库快照上分别跑两臂，
     * 靠改 Nacos 配置 + 重启服务来切换的话，两臂之间菜库/索引可能已经变了，
     * 结果就不可比了。空值表示用服务端配置（{@code es.score.strategy}）。
     *
     * <p>它只是排序口径的切换，不涉及权限或数据可见性，所以不需要管理员校验。
     */
    private String strategy;
}
