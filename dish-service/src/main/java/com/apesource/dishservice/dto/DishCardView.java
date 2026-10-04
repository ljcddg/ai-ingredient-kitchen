package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 菜品列表项 —— 「我的菜品」「我的收藏」共用。
 *
 * <p>为什么和 {@link DishDetailView} 分开：列表一次要展示 20 条，
 * 如果把步骤、食材明细、评论都带上，响应体会膨胀十几倍，
 * 而列表页一个都不显示。所以列表只带"一屏能看全"的字段。
 *
 * <p>字段名和后端查询里的 SQL 别名严格对应（都是驼峰），
 * 改 SQL 别名时必须同步改这里，否则会静默读出 null。
 */
@Data
public class DishCardView {

    private Long id;
    private String name;
    private String summary;
    private String coverUrl;

    /** 来源：1-官方精选，2-AI 生成，3-用户自定义。前端据此显示「AI 生成」小标 */
    private Integer source;

    /** 状态：0-草稿（仅自己可见），1-已发布 */
    private Integer status;

    private Integer difficulty;
    private Integer prepTimeMin;
    private Integer cookTimeMin;

    /** 营养来源：0-没有，1-AI 估算，2-本地精确 */
    private Integer nutritionSource;

    private Integer energyKcal;

    private Double ratingAvg;
    private Integer ratingCount;
    private Integer collectCount;

    // ---- 冷启动：推荐池 / 排行榜 ----

    /** 冷启动分级：0-未进池，1-推荐池，2-排行榜。前端据此显示「推荐中 / 已上榜」徽章 */
    private Integer rankLevel;

    /** 综合得分（百分制）。冷启动排行榜按它排序，与 ratingAvg 不是一回事 */
    private Double dishScore;

    private Integer likeCount;

    /** 试做打卡数 */
    private Integer cookCount;

    /** 推流次数。作者在自己的菜品列表里可见，用来展示冷启动进度 */
    private Integer pushCount;

    /** 查看次数。作者可见 */
    private Integer viewCount;

    /** 作者 ID。前端据此判断"这是我的菜"，决定要不要显示冷启动数据 */
    private Long creatorId;

    /**
     * 推荐流专用：{@code full}-全匹配 / {@code partial}-部分匹配 / {@code random}-随机补齐。
     * 其他列表里这个字段是 null。
     *
     * <p>它让推荐逻辑"可见"—— 用户能看懂这道菜为什么出现在面前。
     */
    private String matchType;

    /** 菜品创建时间（已格式化的字符串） */
    private String createTime;

    /** 我收藏它的时间（只有「我的收藏」列表有值） */
    private String markedTime;
}
