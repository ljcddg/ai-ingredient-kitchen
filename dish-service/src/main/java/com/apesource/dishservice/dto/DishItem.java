package com.apesource.dishservice.dto;

import lombok.Data;

import java.util.List;

/**
 * 返回给前端的单条菜品。
 *
 * <p>注意这里【不含】食材用量明细 —— 那样数据太大，而且召回阶段不需要。
 * 用户点进菜品详情时再按 dishId 单查（用量按人数做换算）。
 */
@Data
public class DishItem {

    /** 菜品 ID */
    private Long dishId;

    /** 菜品名 */
    private String name;

    /** 口味标签，展示用 */
    private List<String> tasteTags;

    /** 基准份量（人份）—— 前端按 baseServing 与用户所选人数做换算 */
    private Integer baseServing;

    /** 难度 1-5 */
    private Integer difficulty;

    /** 预计耗时（分钟） */
    private Integer cookTimeMin;

    /** 每份热量（kcal） */
    private Double energyKcal;

    /** 平均分（仅展示） */
    private Double ratingAvg;

    /** 评分人数 */
    private Integer ratingCount;

    /** 威尔逊下界得分（≈ 5 分制的 4.2 分） */
    private Double ratingWilson;

    /** 收藏数 */
    private Integer collectCount;

    // ---------- 以下是召回过程的中间量，返回给前端便于展示与调试 ----------

    /** 命中了几样用户选的食材 */
    private int hitCount;

    /** 食材覆盖率 = hitCount / 用户所选食材数 */
    private double similarity;

    /** 应用层综合分（排序依据） */
    private double score;

    /** 是否满足直出条件 */
    private boolean directEligible;

    // ---------- 主料满足率口径（说明文档第二节的核心指标） ----------

    /**
     * 主料满足率 = |U∩R| / |R|。
     * <p>U = 你手上（已排除常备调料）的主料，R = 这道菜要求的必需主料。
     * 它回答的是「这道菜现在能不能做」—— 而 {@link #similarity} 回答的是
     * 「你的食材被用掉了多少」，两者缺一不可。
     */
    private double satisfactionRate;

    /** 主料利用率 = |U∩R| / |U|，衡量手边的食材被用掉多少 */
    private double utilizationRate;

    /** 还缺几样主料（0 = 现在就能做） */
    private int missingCount;

    /** 已具备的主料名，给页面显示"你有这些" */
    private List<String> matchedIngredients;

    /** 缺失的主料名，给页面显示"还差这些" —— 这比一个数字有用得多 */
    private List<String> missingIngredients;

    /** 推荐理由：一句话说明为什么推荐它 / 为什么还不能直接用 */
    private String reason;

    /** 直出走的哪条质量路径（"充分评价" / "少量高分"）；未直出时为空 */
    private String directPath;
}
