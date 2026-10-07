package com.apesource.dishservice.discover.signal;

import com.apesource.dishservice.es.DishDoc;
import com.apesource.dishservice.service.IngredientDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 食材匹配信号 —— 本模块挖得最深的一个。
 *
 * <p><b>为什么它值得做深：</b>「你手边有什么」是这个产品唯一别人没有的信号。
 * 评分、收藏、热度任何菜谱站都有，只有"这道菜你现在做得了吗"是独一份的。
 * 力气花在这里的边际收益，远高于把质量分公式再调精细两位小数。
 *
 * <p><b>口径必须和搜索侧一致：</b>必需集合 R = 全部<b>非调料</b>食材，
 * 不是 {@code mainIngredientIds}。后者听起来更贴题，但实测每道菜只标了 1 个主料，
 * 拿它当必需集合会让缺料恒为 0（详细说明见 {@code DishSearchService.requiredMains}）。
 * 两边对"什么叫缺料"的理解必须统一，否则用户在搜索页和发现页会看到矛盾的结论。
 *
 * <p><b>输出三档而不只是一个分数：</b>分数是给排序用的，档位是给人看的。
 * 「齐活 / 差一点 / 缺得多」比"匹配度 0.73"有用得多——用户要的是
 * "我现在能不能做"，不是"我和它的契合度是多少"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngredientMatchSignal {

    /** 缺一样必需食材的扣分量。缺得越多扣得越狠，但不是线性崩塌 */
    private static final double MISSING_PENALTY = 0.15;

    /** 一样不缺的额外加分：让"全齐"的菜稳定压过"差一样"的菜 */
    private static final double ALL_READY_BONUS = 0.10;

    /** 用户没选任何食材时的中性分——此时这个信号不参与决策 */
    private static final double NEUTRAL_SCORE = 0.5;

    private final IngredientDirectory directory;

    /**
     * 匹配档位。
     *
     * <p>顺序即优先级：前端按这个顺序决定角标的醒目程度。
     */
    public enum Tier {
        /** 没选食材，不适用 */
        NONE,
        /** 一样不缺，现在就能做 */
        READY,
        /** 只差一点（≤1 样非调料，或缺的只是调料） */
        CLOSE,
        /** 缺得比较多 */
        FAR
    }

    /**
     * 匹配结论。
     *
     * <p>{@code matchScore} 进排序公式；其余字段全是给前端做展示的，
     * 一道菜被推荐出来总得说清楚为什么。
     */
    public record MatchResult(
            double satisfyRate,
            double matchScore,
            Tier tier,
            List<String> matchedNames,
            List<String> missingNames,
            List<String> missingSeasoningNames) {

        /** 有没有明确缺非调料食材 */
        public boolean hasHardMissing() {
            return missingNames != null && !missingNames.isEmpty();
        }

        /** 角标文案。返回空串表示这个场景不该显示角标 */
        public String badge() {
            return switch (tier) {
                case READY -> "食材齐了";
                case CLOSE -> "差一点";
                case FAR -> "还差 " + (missingNames == null ? 0 : missingNames.size()) + " 样";
                case NONE -> "";
            };
        }
    }

    /**
     * 算一道菜的匹配情况。
     *
     * @param doc    菜品文档（ES 侧）
     * @param uIds   用户手边食材的主数据 ID
     * @param uNames 用户手边食材的名字（表外食材走这条）
     */
    public MatchResult evaluate(DishDoc doc, Set<Long> uIds, Set<String> uNames) {
        if (uIds.isEmpty() && uNames.isEmpty()) {
            return new MatchResult(0d, NEUTRAL_SCORE, Tier.NONE,
                    List.of(), List.of(), List.of());
        }

        List<Required> required = requiredOf(doc);

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Required req : required) {
            if (owns(req, uIds, uNames)) {
                matched.add(req.name());
            } else {
                missing.add(req.name());
            }
        }

        // 调料单独统计：它不阻止你做这道菜，但值得提一句"家里没姜"
        List<String> missingSeasoning = new ArrayList<>();
        for (String name : nz(doc.getIngredientNames())) {
            if (name == null || !directory.isSeasoningName(name)) {
                continue;
            }
            if (!owns(new Required(null, name), uIds, uNames)
                    && !missingSeasoning.contains(name)) {
                missingSeasoning.add(name);
            }
        }

        double satisfyRate = required.isEmpty() ? 1d : (double) matched.size() / required.size();
        double penalty = required.isEmpty()
                ? 0d
                : MISSING_PENALTY * ((double) missing.size() / required.size());
        double bonus = (required.isEmpty() || !missing.isEmpty()) ? 0d : ALL_READY_BONUS;
        double score = clamp(satisfyRate - penalty + bonus, 0d, 1d);

        Tier tier = switch (missing.size()) {
            case 0 -> required.isEmpty() ? Tier.NONE : Tier.READY;
            case 1 -> Tier.CLOSE;
            default -> Tier.FAR;
        };

        return new MatchResult(satisfyRate, score, tier, matched, missing, missingSeasoning);
    }

    /**
     * 这道菜的必需食材 = 全部非调料食材。
     *
     * <p>名字列表是权威来源：索引构建时会无条件收集全部食材名，
     * 包括没有主数据 ID 的表外食材（见 {@code DishEsSyncService.buildDoc}）。
     * {@code ingredientIds} 只装得下有 ID 的，会漏掉表外食材。
     */
    private List<Required> requiredOf(DishDoc doc) {
        List<Required> required = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String name : nz(doc.getIngredientNames())) {
            if (name == null || directory.isSeasoningName(name) || !seen.add(name)) {
                continue;
            }
            required.add(new Required(directory.resolveId(name), name));
        }

        // 兜底：名字列表为空（异常/老数据）时退回主料 ID
        if (required.isEmpty()) {
            for (Long id : new LinkedHashSet<>(nz(doc.getMainIngredientIds()))) {
                String name = directory.nameOf(id);
                required.add(new Required(id, name != null ? name : ("食材#" + id)));
            }
        }
        return required;
    }

    /**
     * 判断这味食材用户手边有没有。
     *
     * <p>三条路都要试：名字直接对上（表外食材）、ID 对上（最常见）、
     * 以及拿用户选的 ID 反查名字再比对（目录没收录该别名时这条路能兜住）。
     */
    private boolean owns(Required req, Set<Long> uIds, Set<String> uNames) {
        if (uNames.contains(req.name())) {
            return true;
        }
        if (req.id() != null && uIds.contains(req.id())) {
            return true;
        }
        for (Long id : uIds) {
            if (req.name().equals(directory.nameOf(id))) {
                return true;
            }
        }
        return false;
    }

    /** 一道菜要求的一味食材 */
    private record Required(Long id, String name) {
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }
}
