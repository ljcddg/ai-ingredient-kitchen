package com.apesource.dishservice.dto;

import lombok.Data;

import java.util.List;

/**
 * 菜品详情 —— 详情页需要的全部数据，一个接口给全。
 *
 * <p>为什么不拆成多个接口（详情 / 营养 / 评论分开）：详情页是"点进去就要看全"的场景，
 * 拆开会让前端发 4~5 个请求、还要处理各自的加载态和错误态。一次查完更省事，
 * 而且这些数据量都很小（评论先取前 20 条）。
 */
@Data
public class DishDetailView {

    private Long id;
    private String name;
    private String summary;
    private String coverUrl;

    /** 0-草稿（仅创建者可见），1-已发布 */
    private Integer status;

    /** 来源：1-官方精选，2-AI 生成，3-用户自定义。详情页据此显示来源标签 */
    private Integer source;

    private Long creatorId;

    /**
     * 发布时间（字符串）。草稿为 null。
     *
     * <p>详情页要用它区分「草稿」和「已发布到找菜库」两种状态，
     * 光有 {@code status} 只能判断"发没发布"、说不出"什么时候发的"。
     */
    private String publishTime;

    /** 基准份量（人份）—— 用量都是按这个份量给的，前端按当前人数换算 */
    private Integer baseServing;

    private Integer difficulty;
    private Integer prepTimeMin;
    private Integer cookTimeMin;
    private Integer spicyLevel;

    private List<String> tastes;

    /** 制作过程 */
    private List<DishStep> steps;

    /** 食材清单（含用量） */
    private List<IngredientItem> ingredients;

    /** 调料：只有名字，用量由系统按标准配方给 */
    private List<String> seasonings;

    private List<String> equipment;

    /** 大厨建议 */
    private List<String> chefTips;

    /** 替代食材建议 */
    private List<String> substitutes;

    private NutritionView nutrition;

    private RatingView rating;

    private List<CommentView> comments;

    // ------------------------------------------------------------------ 当前用户的个人状态

    /**
     * 我有没有收藏这道菜。
     *
     * <p>必须由后端算而不是前端拿 userId 去比对 —— 前端没有"收藏列表"这个概念，
     * 而且详情页打开时前端手上并没有"我收藏过哪些菜"的全量数据。
     */
    private Boolean collected;

    /** 我打过的分，没打过为 null（前端据此决定评分控件显示几颗星） */
    private Integer myScore;

    // ------------------------------------------------------------------ 嵌套结构

    @Data
    public static class IngredientItem {
        private Long ingredientId;
        private String name;
        private Integer amountGram;
        /** 是否主料 */
        private Boolean main;
    }

    @Data
    public static class NutritionView {
        private Integer calories;
        private Integer proteinGram;
        private Integer fatGram;
        private Integer carbGram;
        private Integer fiberGram;
        private Integer sodiumMg;

        /** 营养来源说明，如"AI 估算，仅供参考" */
        private String source;

        /** 是否通过本地校验（前端可以不展示，但它决定了这份数据可不可信） */
        private Boolean verified;
    }

    @Data
    public static class RatingView {
        private Double average;
        private Integer count;
        private Integer collectCount;
    }

    @Data
    public static class CommentView {
        private Long userId;
        private String nickname;
        private String content;
        private Integer rating;
        private String createTime;
    }
}
