package com.apesource.dishservice.service;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.config.EsProperties;
import com.apesource.dishservice.dto.DishItem;
import com.apesource.dishservice.dto.DishRecallResult;
import com.apesource.dishservice.dto.DishSearchRequest;
import com.apesource.dishservice.es.DishDoc;
import com.apesource.dishservice.es.DishSearchRepository;
import com.apesource.dishservice.es.RecalledDish;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 菜品召回服务 —— 整套流程的中枢。
 *
 * <p>处理顺序（每一段的职责都是明确的，不要混）：
 * <ol>
 *   <li><b>参数规范化</b>：把用户给的食材拆成「有 ID 的」和「只有名字的」两个互斥桶，
 *       ID 去重升序。排序是为了让「同一个选择」永远产生同一个请求签名 ——
 *       日志好比对，将来给 AI 调用做单飞去重也靠它</li>
 *   <li><b>ES 召回</b>：硬约束（必须包含全部食材）+ function_score 粗排</li>
 *   <li><b>应用层精排</b>：算食材覆盖率，再按「覆盖率 / 威尔逊分 / 收藏热度」加权综合分排序</li>
 *   <li><b>直出判定</b>：三条阈值同时满足 → 直接用库里的菜，不调 AI</li>
 * </ol>
 *
 * <h3>为什么要把「精排」从 ES 里拿出来做</h3>
 * 覆盖率是集合运算（用户所选食材 ∩ 菜品食材），ES 的 {@code function_score}
 * 表达不了；而「评价人数 ≥ 10」这类业务阈值更不该藏进 ES 的 score 里 ——
 * 那会让判定逻辑变得无法解释、也无法单独测试。<br>
 * 分工：ES 负责从海量候选里粗排挑出一批，应用层负责最终排序与判定。
 *
 * <h3>食材的两条路</h3>
 * 用户在食材表里点选的食材带主数据 ID；在输入框里自由敲的（如「松露」）没有 ID。
 * 两边的召回与覆盖率计算各走一条路，最后加总 —— 这样「手边有什么就能用什么」
 * 对表内表外一视同仁，不必逼用户只从列表里挑。
 *
 * <h3>已知的取舍</h3>
 * 现在是「ES 取 Top N（默认 200）→ 应用层排序后分页」。
 * 数据量大到 200 条装不下真正的最优解时，要改成 ES 侧排序 + {@code search_after} 深分页。
 * 当前数据量下前者更简单也更准确（不会漏掉覆盖面最全的菜）。
 */
@Service
@RequiredArgsConstructor
public class DishSearchService {

    private static final Logger log = LoggerFactory.getLogger(DishSearchService.class);

    private final DishSearchRepository dishSearchRepository;

    private final EsProperties properties;

    /**
     * 按食材组合召回菜品。
     */
    public DishRecallResult search(DishSearchRequest request) {
        // ---------- 1. 参数规范化 ----------
        RequestedIngredients wanted = normalizeIngredients(
                request.getIngredientIds(), request.getFreeIngredients());

        // ---------- 2. ES 召回（硬约束） ----------
        boolean allowPartial = Boolean.TRUE.equals(request.getAllowPartial());
        List<RecalledDish> recalled = dishSearchRepository.recall(
                wanted.ids(), wanted.names(), request.getTasteTags(), allowPartial);

        // ---------- 3. 应用层精排 ----------
        rank(recalled, wanted);

        // ---------- 4. 直出判定 ----------
        RecalledDish best = recalled.isEmpty() ? null : recalled.get(0);
        boolean directReturn = best != null && best.isDirectEligible();

        // ---------- 5. 组装响应 ----------
        int page = request.getPage() == null ? 1 : request.getPage();
        int size = request.getSize() == null ? 10 : request.getSize();

        DishRecallResult result = new DishRecallResult();
        result.setDirectReturn(directReturn);
        // 用户"手边有几样食材"——表内表外都算，前端拿它算命中比例的分母
        result.setRequestedIngredientCount(wanted.total());
        result.setTotal(recalled.size());
        result.setPage(page);
        result.setSize(size);
        result.setBest(best == null ? null : toItem(best));
        result.setCandidates(paginate(recalled, page, size).stream().map(this::toItem).toList());

        // 召回为空有两种可能：「真的没有匹配的菜」和「ES 挂了」。
        // 前者是正常业务结果（去调 AI），后者要提示降级 —— 必须区分开。
        boolean esAvailable = !recalled.isEmpty() || dishSearchRepository.ping();
        result.setSearchEngineAvailable(esAvailable);
        result.setMissReason(directReturn ? null : buildMissReason(recalled, wanted, esAvailable));

        log.info("食材召回：所选 {} 种（表内 {} / 表外 {}），命中 {} 条，直出={}{}",
                wanted.total(), wanted.ids().size(), wanted.names().size(),
                recalled.size(), directReturn,
                directReturn ? "" : "（" + result.getMissReason() + "）");

        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 用户这次「手边有什么」的规范化结果。
     *
     * <p>两个桶是<b>互斥</b>的：一样食材要么归一到主数据拿到了 ID（进 {@code ids}），
     * 要么没有（进 {@code names}），不会两边都占。这个不变式是覆盖率不重复计数的前提。
     *
     * @param ids   有主数据 ID 的食材（点选的，或自由输入但按名字/别名归一成功的）
     * @param names 没有 ID 的食材名（食材主数据之外的自由输入）
     */
    private record RequestedIngredients(List<Long> ids, List<String> names) {
        int total() {
            return ids.size() + names.size();
        }
    }

    /**
     * 参数规范化：ID 去重 + 升序，名字去空白 + 去重。
     *
     * <p>ID 升序不是为了好看：它让「选 A 再选 B」和「选 B 再选 A」产生完全一样的
     * 请求参数，签名才能稳定 —— 日志比对、AI 调用的单飞去重都依赖这一点。
     */
    private RequestedIngredients normalizeIngredients(List<Long> rawIds, List<String> rawNames) {
        List<Long> ids = rawIds == null ? List.of()
                : new ArrayList<>(new HashSet<>(rawIds.stream().filter(Objects::nonNull).toList()));
        ids.sort(Comparator.naturalOrder());

        Set<String> dedup = new LinkedHashSet<>();
        if (rawNames != null) {
            for (String n : rawNames) {
                if (n != null && !n.isBlank()) {
                    dedup.add(n.trim());
                }
            }
        }
        List<String> names = new ArrayList<>(dedup);

        // 校验放在这里而不是 DTO 注解上：合法性取决于「两个桶的总数」，
        // 单看任何一个字段都会误判（只输入表外食材时 ingredientIds 本来就是空的）
        if (ids.isEmpty() && names.isEmpty()) {
            throw new BusinessException("请至少选择一种食材");
        }

        int max = properties.getRecall().getMaxIngredients();
        if (ids.size() + names.size() > max) {
            throw new BusinessException("一次最多选择 " + max + " 种食材");
        }
        return new RequestedIngredients(ids, names);
    }

    /** 应用层精排：算覆盖率与综合分，然后按综合分降序 */
    private void rank(List<RecalledDish> recalled, RequestedIngredients wanted) {
        EsProperties.Score cfg = properties.getScore();
        Set<Long> idSet = new HashSet<>(wanted.ids());
        Set<String> nameSet = new HashSet<>(wanted.names());
        int total = wanted.total();
        double collectBaseline = Math.log1p(cfg.getCollectBaseline());

        for (RecalledDish item : recalled) {
            DishDoc doc = item.getDoc();
            if (doc == null) {
                continue;
            }

            // 覆盖率 = 命中食材数 / 用户所选食材数
            int hit = countHits(doc, idSet, nameSet);
            item.setHitCount(hit);
            item.setSimilarity(total == 0 ? 0d : (double) hit / total);

            double collectNorm = collectBaseline <= 0
                    ? 0d
                    : Math.min(1d, Math.log1p(nz(doc.getCollectCount())) / collectBaseline);

            item.setFinalScore(cfg.getWeightSimilarity() * item.getSimilarity()
                    + cfg.getWeightWilson() * nz(doc.getRatingWilson())
                    + cfg.getWeightCollect() * collectNorm);

            item.setDirectEligible(isDirectEligible(item, doc));
        }

        recalled.sort(Comparator.comparingDouble(RecalledDish::getFinalScore).reversed());
    }

    /**
     * 命中食材数 = 有 ID 的那部分交集 + 只有名字的那部分交集。
     *
     * <p><b>为什么这样加不会重复计数</b>：用户侧的两个桶互斥（见 {@link RequestedIngredients}），
     * 所以「按 ID 命中」和「按名字命中」针对的是<b>不同批</b>食材。
     * 比如用户选了「番茄」（有 ID）又输入了「松露」（无 ID）：
     * 前者只可能走 ID 路，后者只可能走名字路，各数一次正好。
     */
    private int countHits(DishDoc doc, Set<Long> idSet, Set<String> nameSet) {
        int hit = 0;

        List<Long> docIds = doc.getIngredientIds();
        if (docIds != null) {
            for (Long id : docIds) {
                if (id != null && idSet.contains(id)) {
                    hit++;
                }
            }
        }

        List<String> docNames = doc.getIngredientNames();
        if (docNames != null) {
            for (String name : docNames) {
                if (name != null && nameSet.contains(name)) {
                    hit++;
                }
            }
        }
        return hit;
    }

    /**
     * 直出判定 —— 三条阈值同时满足才算命中。
     *
     * <p>刻意用确定性规则而不是概率：如果这里写成「大概率直出」，
     * 同样输入有时给旧菜、有时调 AI，用户会以为系统在抽风，
     * 而且这种逻辑也没法写测试。
     */
    private boolean isDirectEligible(RecalledDish item, DishDoc doc) {
        EsProperties.Judge judge = properties.getJudge();
        return item.getSimilarity() >= judge.getMinSimilarity()
                && nz(doc.getRatingCount()) >= judge.getMinRatingCount()
                && nz(doc.getRatingWilson()) >= judge.getMinRatingWilson();
    }

    private List<RecalledDish> paginate(List<RecalledDish> all, int page, int size) {
        int from = (page - 1) * size;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(from + size, all.size()));
    }

    private DishItem toItem(RecalledDish recalled) {
        DishDoc doc = recalled.getDoc();
        DishItem item = new DishItem();
        item.setDishId(doc.getDishId());
        item.setName(doc.getName());
        item.setTasteTags(doc.getTasteTags());
        item.setBaseServing(doc.getBaseServing());
        item.setDifficulty(doc.getDifficulty());
        item.setCookTimeMin(doc.getCookTimeMin());
        item.setEnergyKcal(doc.getEnergyKcal());
        item.setRatingAvg(doc.getRatingAvg());
        item.setRatingCount(doc.getRatingCount());
        item.setRatingWilson(doc.getRatingWilson());
        item.setCollectCount(doc.getCollectCount());
        item.setHitCount(recalled.getHitCount());
        item.setSimilarity(recalled.getSimilarity());
        item.setScore(recalled.getFinalScore());
        item.setDirectEligible(recalled.isDirectEligible());
        return item;
    }

    /**
     * 说明「为什么没直出」。
     * 这个字符串既给前端做提示，也是线上排查时最有用的线索 ——
     * 它能直接告诉你卡在哪一条阈值上。
     */
    private String buildMissReason(List<RecalledDish> recalled, RequestedIngredients wanted,
                                   boolean esAvailable) {
        if (!esAvailable) {
            return "检索引擎暂时不可用，请稍后重试";
        }
        if (recalled.isEmpty()) {
            return String.format("库里还没有包含这 %d 种食材的菜品，需要 AI 生成", wanted.total());
        }

        RecalledDish top = recalled.get(0);
        DishDoc doc = top.getDoc();
        EsProperties.Judge judge = properties.getJudge();

        if (top.getSimilarity() < judge.getMinSimilarity()) {
            return String.format("最接近的「%s」只覆盖 %d/%d 种食材",
                    doc.getName(), top.getHitCount(), wanted.total());
        }
        if (nz(doc.getRatingCount()) < judge.getMinRatingCount()) {
            return String.format("最接近的「%s」评价人数不足（%d < %d 人），分数还不可信",
                    doc.getName(), nz(doc.getRatingCount()), judge.getMinRatingCount());
        }
        if (nz(doc.getRatingWilson()) < judge.getMinRatingWilson()) {
            return String.format("最接近的「%s」评分偏低（%.2f < %.2f）",
                    doc.getName(), nz(doc.getRatingWilson()), judge.getMinRatingWilson());
        }
        return "没有找到满足直出条件的菜品";
    }

    private static double nz(Double value) {
        return value == null ? 0d : value;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
