package com.apesource.dishservice.discover;

import com.apesource.dishservice.discover.signal.IngredientMatchSignal;
import com.apesource.dishservice.es.DishDoc;

/**
 * 打完分的一道菜：四个信号各自的取值，以及合成后的 {@code feedScore}。
 *
 * <p>把四个分项都留在对象里，不只是为了算总分——
 * <b>排序口径要可解释</b>。一道菜为什么排在第一位，得能说出是匹配赢了还是质量赢了，
 * 否则线上排序出问题只能靠猜。
 *
 * <p>{@code reason} 是给用户看的那句话。它是这个模块存在的理由：
 * 用户信的是"为什么是这道菜"，不是"算法觉得它好"。
 */
public record ScoredDish(
        Long dishId,
        DishDoc doc,
        IngredientMatchSignal.MatchResult match,
        double qualityScore,
        double contextScore,
        double personalScore,
        double feedScore,
        String reason) {

    /**
     * 生成推荐理由。
     *
     * <p>优先级：食材匹配 &gt; 做过的人多 &gt; 快手 &gt; 兜底。
     * 为什么这么排：食材是因人而异的信息，最值得说；
     * "327 人做过"是社交证明，人人都能听懂；
     * 都排不上的时候宁可说一句实在的，也不要编。
     */
    public static String buildReason(DishDoc doc, IngredientMatchSignal.MatchResult match,
                                     double qualityScore) {
        if (match == null) {
            return "";
        }

        switch (match.tier()) {
            case READY -> {
                return "你手边的食材都齐了";
            }
            case CLOSE -> {
                if (match.missingNames() != null && !match.missingNames().isEmpty()) {
                    return "只差 " + String.join("、", match.missingNames());
                }
                if (match.missingSeasoningNames() != null
                        && !match.missingSeasoningNames().isEmpty()) {
                    return "家里没" + String.join("、", match.missingSeasoningNames()) + "，可以省";
                }
                return "差一点点就能做";
            }
            default -> {
                // 没有强匹配信号，退而说社交证明或效率
                if (doc.getCookCount() != null && doc.getCookCount() >= 20) {
                    return doc.getCookCount() + " 人做过";
                }
                if (doc.getCookTimeMin() != null && doc.getCookTimeMin() <= 15) {
                    return doc.getCookTimeMin() + " 分钟能上桌";
                }
                if (qualityScore >= 60) {
                    return "评价不错，值得一试";
                }
                return "";
            }
        }
    }
}
