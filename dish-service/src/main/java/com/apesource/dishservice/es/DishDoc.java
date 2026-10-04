package com.apesource.dishservice.es;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 菜品在 Elasticsearch 里的文档形态。
 *
 * <p>字段与 {@code dish_index} 的 mapping 一一对应（见项目根目录 es/dish-search.md）。
 * 这里同时承担两个角色：ES 召回结果的载体、以及将来把 MySQL 数据同步到 ES 时的来源。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 是必需的 ——
 * ES 的 {@code _source} 里可能带上索引新增字段，不加的话 Jackson 会直接抛异常。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DishDoc {

    /** 菜品 ID（对应 MySQL t_dish.id，雪花 ID） */
    private Long dishId;

    /** 菜品名 */
    private String name;

    /** 菜名归一化键，用于跨用户重复检测 */
    private String nameKey;

    /** 状态：0-草稿，1-已发布，2-审核拒绝，3-已下架 */
    private Integer status;

    /** 逻辑删除：0-未删除，1-已删除 */
    private Integer deleted;

    /** 来源：1-官方精选，2-AI生成，3-用户自定义 */
    private Integer source;

    /** 创建者用户 ID */
    private Long creatorId;

    /** 全部食材 ID —— 召回的主字段 */
    private List<Long> ingredientIds;

    /** 主料食材 ID —— 召回时权重更高 */
    private List<Long> mainIngredientIds;

    /**
     * 全部食材的<b>名字</b>，包含没有主数据 ID 的自由输入食材（如用户手敲的「松露」）。
     *
     * <p>为什么要和 {@link #ingredientIds} 并存：用户能在输入框里敲食材表之外的东西，
     * 这类食材在 {@code t_dish_ingredient} 里 {@code ingredient_id} 是 NULL，
     * 而 {@code ingredientIds} 是 {@code long} 数组、塞不进 null —— 只能剔除。
     * 剔除的后果是这道菜在索引里等于「没有松露这个标签」，别人选松露永远搜不到它。
     *
     * <p>补一个名字数组之后，召回就能走双路：表内食材查 {@code ingredientIds}、
     * 表外食材查 {@code ingredientNames}，两者都表达「这道菜用了它」。
     */
    private List<String> ingredientNames;

    /** 食材种类数 */
    private Integer ingredientCount;

    /** 口味标签（展示用） */
    private List<String> tasteTags;

    /** 标签 ID */
    private List<Long> tagIds;

    /** 基准份量（人份） */
    private Integer baseServing;

    /** 难度 1-5 */
    private Integer difficulty;

    /** 预计耗时（分钟） */
    private Integer cookTimeMin;

    /** 平均分（仅展示，不用于排序） */
    private Double ratingAvg;

    /** 威尔逊下界得分（排序与直出判定用） */
    private Double ratingWilson;

    /** 评分人数 */
    private Integer ratingCount;

    /** 收藏数 */
    private Integer collectCount;

    /** 浏览数 */
    private Integer viewCount;

    // ---- 冷启动推荐池 ----

    /**
     * 冷启动分级：0-未进池，1-推荐池，2-排行榜。
     *
     * <p>搜索默认只召回 2 —— 推荐池里的菜还没被验证过，
     * 不该出现在搜索结果里，它们要靠推荐流拿曝光。
     */
    private Integer rankLevel;

    /** 综合得分（百分制）：评分与互动加权，冷启动榜排序用 */
    private Double dishScore;

    /** 推流次数 */
    private Integer pushCount;

    /** 点赞数 */
    private Integer likeCount;

    /** 试做打卡数 */
    private Integer cookCount;

    /** 每份热量（kcal，本地计算） */
    private Double energyKcal;

    /** 发布时间 */
    private String publishTime;
}
