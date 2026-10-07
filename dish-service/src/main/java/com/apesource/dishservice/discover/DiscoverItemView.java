package com.apesource.dishservice.discover;

import lombok.Data;

import java.util.List;

/**
 * 发现流里的一张卡片。
 *
 * <p>和 {@code DishCardView} 最大的区别是多了<b>缺料信息</b>和<b>推荐理由</b>——
 * 这正是旧榜单最缺的东西：它只告诉用户"这道菜排第几"，
 * 不告诉用户"你做得了吗"和"为什么推给你"。
 *
 * <p>字段刻意分成三组，前端按需渲染，不用猜哪个字段可能为 null：
 * 菜品信息（总是有）、匹配信息（没选食材时为空数组/空串）、排序信息（调试用）。
 */
@Data
public class DiscoverItemView {

    // ---------- 菜品信息 ----------
    private Long id;
    private String name;

    /*
     * 没有 summary / coverUrl —— 不是忘了加。
     * ES 文档里本来就没这两个字段（SOURCE_FIELDS 未包含），
     * 搜索侧的 DishItem 同样没有，整个检索链路都不带摘要和封面。
     * 这里不破例去补一次数据库查询，保持两条链路一致；
     * 前端确实需要封面时，加一次按 ID 批量补查即可（一次查询，成本很低）。
     */
    private Integer cookTimeMin;
    private Integer difficulty;
    private Integer baseServing;
    private Integer energyKcal;
    private Double ratingAvg;
    private Integer ratingCount;
    private Integer cookCount;
    private Integer collectCount;
    private Integer source;
    private Integer rankLevel;

    // ---------- 匹配信息（方向二的核心产出）----------
    /** 档位：none / ready / close / far */
    private String matchTier;
    /** 角标文案，如「食材齐了」「还差 2 样」 */
    private String matchBadge;
    private Double satisfyRate;
    private List<String> matchedIngredients;
    private List<String> missingIngredients;
    /** 缺的调料——不阻止做这道菜，但值得提一句 */
    private List<String> missingSeasonings;

    // ---------- 排序信息 ----------
    /** 综合分，调试与答辩演示用，前端不必展示 */
    private Double feedScore;
    /** 推荐理由，一句话 */
    private String reason;
}
