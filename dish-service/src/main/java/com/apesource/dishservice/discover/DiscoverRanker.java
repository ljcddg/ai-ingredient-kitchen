package com.apesource.dishservice.discover;

import com.apesource.dishservice.discover.signal.ContextSignal;
import com.apesource.dishservice.discover.signal.IngredientMatchSignal;
import com.apesource.dishservice.discover.signal.QualitySignal;
import com.apesource.dishservice.discover.signal.TasteProfileSignal;
import com.apesource.dishservice.es.DishDoc;
import com.apesource.dishservice.es.RecalledDish;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 排序层：把四个信号合成一个 {@code feedScore} 并排序。
 *
 * <p><b>排序口径只有这一份。</b>浏览模式和决策模式吃的都是这里算出来的同一个列表，
 * 所以用户切到"帮我决定"看到的第一道，和列表里排第一的必然是同一道——
 * 两种呈现方式给出矛盾的答案是最伤信任的。
 *
 * <p><b>硬过滤在打分之前。</b>先按情境条件把不合适的砍掉，剩下的才打分排序。
 * 顺序反过来（先打分再过滤）会让"勾了 20 分钟却出现 60 分钟的菜"
 * 这种事在高分菜身上发生。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoverRanker {

    private final DiscoverProperties properties;
    private final IngredientMatchSignal matchSignal;
    private final QualitySignal qualitySignal;
    private final ContextSignal contextSignal;
    private final TasteProfileSignal tasteSignal;

    /**
     * 打分并排序。
     *
     * @param recalled ES 召回的候选（已包含推荐池新菜）
     * @param uIds     用户手边食材的主数据 ID
     * @param uNames   用户手边食材的名字（表外食材）
     * @param filter   情境硬过滤条件，可为空
     * @param userId   当前用户，用于排除他自己发的菜
     * @return 按 {@code feedScore} 降序排列的结果
     */
    public List<ScoredDish> rank(List<RecalledDish> recalled, Set<Long> uIds, Set<String> uNames,
                                  DiscoverFilter filter, Long userId) {
        DiscoverProperties.Weights w = properties.getWeights();
        List<ScoredDish> scored = new ArrayList<>();

        int filteredByContext = 0;
        int filteredBySelf = 0;

        for (RecalledDish item : recalled) {
            DishDoc doc = item.getDoc();
            if (doc == null || doc.getDishId() == null) {
                continue;
            }
            // 不给自己推自己的菜：一来看到自己的菜很怪，
            // 二来作者反复刷新会污染互动数据（与推荐流口径一致）
            if (userId != null && doc.getCreatorId() != null
                    && doc.getCreatorId().equals(userId)) {
                filteredBySelf++;
                continue;
            }

            ContextSignal.ContextResult ctx = contextSignal.evaluate(doc, filter);
            if (!ctx.pass()) {
                filteredByContext++;
                continue;
            }

            IngredientMatchSignal.MatchResult match =
                    matchSignal.evaluate(doc, uIds, uNames);
            double quality = qualitySignal.score(doc);
            double personal = tasteSignal.score(doc, userId);

            double feed = w.getMatch() * match.matchScore()
                    + w.getQuality() * (quality / 100d)
                    + w.getContext() * ctx.score()
                    + w.getPersonal() * personal;

            String reason = ScoredDish.buildReason(doc, match, quality);
            scored.add(new ScoredDish(doc.getDishId(), doc, match,
                    quality, ctx.score(), personal, feed, reason));
        }

        scored.sort(Comparator.comparingDouble(ScoredDish::feedScore).reversed());

        log.debug("发现排序：候选 {} → 情境过滤掉 {} → 自家菜 {} → 最终 {}",
                recalled.size(), filteredByContext, filteredBySelf, scored.size());
        return scored;
    }
}
