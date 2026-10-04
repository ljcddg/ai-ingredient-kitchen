package com.apesource.dishservice.es;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条召回结果。
 *
 * <p>它的字段来自两段计算，分得很清楚：
 * <ul>
 *   <li>{@link #esScore}、{@link #doc} —— ES 粗排阶段产出</li>
 *   <li>{@link #hitCount}、{@link #similarity}、{@link #finalScore}、{@link #directEligible}
 *       —— 应用层精排阶段填充</li>
 * </ul>
 *
 * <p>为什么不把打分全交给 ES 的 {@code function_score}：
 * ES 算不了「食材覆盖率」（那是集合运算，用户选的食材和菜品食材求交集），
 * 而且真正决定「要不要直出」的业务阈值也不该藏在 ES 的 score 里。
 * 所以分工是：ES 做粗排（从海量候选里挑高质量的一批），应用层做精排与判定。
 */
@Data
@NoArgsConstructor
public class RecalledDish {

    /** ES 返回的粗排分（function_score 的混合结果） */
    private Double esScore;

    /** ES 文档本体 */
    private DishDoc doc;

    /** 命中了几样用户选的食材 */
    private int hitCount;

    /** 食材覆盖率 = hitCount / 用户所选食材数，取值 0~1 */
    private double similarity;

    /** 应用层综合分，由满足率 / 利用率 / 质量分 / 收藏热度加权、再减缺料惩罚得到 */
    private double finalScore;

    /** 是否满足「直出」的全部条件（缺料 + 评价人数 + 质量分） */
    private boolean directEligible;

    /** 走的是哪条质量路径（"充分评价" / "少量高分"），便于事后核对宽松通道 */
    private String directPath;

    /** 主料满足率 = |U∩R| / |R|，衡量菜谱要求的主料齐不齐 */
    private double satisfactionRate;

    /** 主料利用率 = |U∩R| / |U|，衡量手边的食材被用掉多少 */
    private double utilizationRate;

    /** 还缺几样主料 */
    private int missingCount;

    /** 已具备的主料名（给页面展示"你有这些"） */
    private java.util.List<String> matchedIngredients;

    /** 缺失的主料名（给页面展示"还差这些"，并解释为什么没直出） */
    private java.util.List<String> missingIngredients;
}
