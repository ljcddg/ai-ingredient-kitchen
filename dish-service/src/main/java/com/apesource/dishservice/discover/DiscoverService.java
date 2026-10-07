package com.apesource.dishservice.discover;

import com.apesource.dishservice.discover.signal.IngredientMatchSignal;
import com.apesource.dishservice.es.DishDoc;
import com.apesource.dishservice.es.DishSearchRepository;
import com.apesource.dishservice.es.RecalledDish;
import com.apesource.dishservice.service.IngredientDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 发现流：召回 → 过滤 → 打分 → 排序 → 分页。
 *
 * <p>浏览模式和决策模式共用这里的全部结果，只在最后一步取多少条上分叉。
 *
 * <p><b>为什么召回走 ES 而不是 MySQL：</b>食材的双路召回（有主数据 ID 的查
 * {@code ingredientIds}、表外食材查 {@code ingredientNames}）已经在 ES 侧实现了，
 * 再在 MySQL 上写一遍就是两套口径维护两份代码。
 *
 * <p><b>ES 不可用时怎么表现：</b>{@code recall()} 返回空列表而不是抛异常
 * （见 {@code DishSearchRepository}），于是这里自然地返回空页。
 * 宁可让用户看到"暂时没有推荐"，也不要 500。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoverService {

    private final DishSearchRepository searchRepository;
    private final DiscoverRanker ranker;
    private final IngredientDirectory directory;
    private final DiscoverProperties properties;

    /** 浏览模式一页 */
    public record DiscoverPage(long total, int page, int size, boolean hasMore,
                               List<DiscoverItemView> items) {
    }

    /**
     * 浏览模式：给一屏列表。
     */
    public DiscoverPage browse(Long userId, List<Long> ingredientIds, List<String> ingredientNames,
                               DiscoverFilter filter, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), properties.getRecall().getMaxPageSize());

        List<ScoredDish> scored = rank(userId, ingredientIds, ingredientNames, filter);

        int from = (safePage - 1) * safeSize;
        List<ScoredDish> window = from >= scored.size()
                ? List.of()
                : scored.subList(from, Math.min(from + safeSize, scored.size()));

        log.debug("发现浏览：用户 {} 选 {} 样食材，命中 {}，第 {} 页给 {} 条",
                userId, countOf(ingredientIds, ingredientNames), scored.size(), safePage, window.size());

        return new DiscoverPage(scored.size(), safePage, safeSize,
                (long) from + window.size() < scored.size(), toViews(window));
    }

    /**
     * 决策模式：一次备一批，"换一个"从这批里依次取。
     *
     * <p><b>为什么一次给一批而不是每次给一道：</b>每次重新算会因为排序的微小抖动
     * （比如刚好有人打分了）重复出同一道菜，用户会以为"换一个"坏了。
     * 一次取一批、前端本地推进，顺序就锁死了。
     *
     * @param offset 从第几道开始取，用于这一批用完后再取下一批
     */
    public List<DiscoverItemView> decide(Long userId, List<Long> ingredientIds,
                                         List<String> ingredientNames, DiscoverFilter filter,
                                         int offset) {
        List<ScoredDish> scored = rank(userId, ingredientIds, ingredientNames, filter);
        int pool = properties.getRecall().getDecidePoolSize();
        int from = Math.max(0, offset);

        List<ScoredDish> window = from >= scored.size()
                ? List.of()
                : scored.subList(from, Math.min(from + pool, scored.size()));

        log.debug("发现决策：用户 {} 从第 {} 道起取 {} 条（共 {} 道候选）",
                userId, from, window.size(), scored.size());
        return toViews(window);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private List<ScoredDish> rank(Long userId, List<Long> ingredientIds,
                                  List<String> ingredientNames, DiscoverFilter filter) {
        Ingredients ing = normalize(ingredientIds, ingredientNames);

        // includePool = true：把推荐池里的新菜（rankLevel=1）一起召回，
        // 冷启动的曝光就发生在这里，不再需要单独开一个榜
        List<RecalledDish> recalled =
                searchRepository.recall(ing.ids(), ing.names(), null, true);

        return ranker.rank(recalled, ing.idSet(), ing.nameSet(), filter, userId);
    }

    /**
     * 把用户输入归一成「主数据 ID 集合」+「表外名字集合」。
     *
     * <p>表外名字先尝试解析成 ID（走正式名 + 别名 + 去空白大小写），
     * 归得上的并进 ID 集合——这样它就和"勾选同一味食材"完全等价，
     * 不会因为入口不同给出两套结果。
     * 归不上的留在名字集合里，ES 那边有 {@code ingredientNames} 这条路能查到。
     */
    private Ingredients normalize(List<Long> rawIds, List<String> rawNames) {
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();

        if (rawIds != null) {
            for (Long id : rawIds) {
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        if (rawNames != null) {
            for (String raw : rawNames) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Long id = directory.resolveId(raw.trim());
                if (id != null) {
                    ids.add(id);
                } else {
                    names.add(raw.trim());
                }
            }
        }
        return new Ingredients(new ArrayList<>(ids), new ArrayList<>(names), ids, names);
    }

    /** 归一后的手边食材。ids/names 给 ES 用，idSet/nameSet 给匹配判定用 */
    private record Ingredients(List<Long> ids, List<String> names,
                               Set<Long> idSet, Set<String> nameSet) {
    }

    private List<DiscoverItemView> toViews(List<ScoredDish> scored) {
        List<DiscoverItemView> views = new ArrayList<>(scored.size());
        for (ScoredDish s : scored) {
            views.add(toView(s));
        }
        return views;
    }

    private DiscoverItemView toView(ScoredDish s) {
        DishDoc doc = s.doc();
        IngredientMatchSignal.MatchResult m = s.match();

        DiscoverItemView v = new DiscoverItemView();
        v.setId(doc.getDishId());
        v.setName(doc.getName());
        v.setCookTimeMin(doc.getCookTimeMin());
        v.setDifficulty(doc.getDifficulty());
        v.setBaseServing(doc.getBaseServing());
        v.setEnergyKcal(doc.getEnergyKcal() == null ? null : doc.getEnergyKcal().intValue());
        v.setRatingAvg(doc.getRatingAvg());
        v.setRatingCount(doc.getRatingCount());
        v.setCookCount(doc.getCookCount());
        v.setCollectCount(doc.getCollectCount());
        v.setSource(doc.getSource());
        v.setRankLevel(doc.getRankLevel());

        v.setMatchTier(m.tier().name().toLowerCase());
        v.setMatchBadge(m.badge());
        v.setSatisfyRate(m.satisfyRate());
        v.setMatchedIngredients(m.matchedNames());
        v.setMissingIngredients(m.missingNames());
        v.setMissingSeasonings(m.missingSeasoningNames());

        v.setFeedScore(s.feedScore());
        v.setReason(s.reason());
        return v;
    }

    private static int countOf(List<Long> ids, List<String> names) {
        int n = ids == null ? 0 : ids.size();
        n += names == null ? 0 : names.size();
        return n;
    }
}
