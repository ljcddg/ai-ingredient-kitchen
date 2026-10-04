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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 菜品召回服务 —— 整套流程的中枢。
 *
 * <p>处理顺序（每一段的职责都是明确的，不要混）：
 * <ol>
 *   <li><b>参数规范化</b>：把用户给的食材拆成「有 ID 的」和「只有名字的」两个互斥桶。
 *       自由输入的名字先尝试按<b>别名</b>归一到主数据（「西红柿」→ 番茄的 ID），
 *       归不上的才留在名字桶 —— 这是「同一个食材不会被数两次」的前提</li>
 *   <li><b>ES 召回</b>：只要用到你任意一样食材就捞回来（不再要求"全部都用上"）</li>
 *   <li><b>应用层精排</b>：算主料<b>满足率</b>（这道菜现在能不能做）、
 *       <b>利用率</b>（你的食材被用掉多少）、缺料数，先做硬约束过滤再排序</li>
 *   <li><b>直出判定</b>：缺料为 0 且质量达标 → 直接用库里的菜，不调 AI</li>
 * </ol>
 *
 * <h3>为什么要区分「覆盖率」和「主料满足率」</h3>
 * 覆盖率 = 命中食材数 / <b>你选了</b>几样，它衡量的是"你的食材被用掉多少"；
 * 而"这道菜现在能不能做"取决于另一个比例：
 * 命中主料数 / <b>这道菜要求</b>几样主料。
 * 只有番茄和鸡蛋的人，看一道要番茄、鸡蛋、牛肉、奶酪的菜，
 * 覆盖率是 2/2 = 100%，但它缺两样主料，根本做不了 ——
 * 这就是本类同时保留两个指标的原因（说明文档第二节的核心问题）。
 *
 * <h3>为什么把「精排」从 ES 里拿出来做</h3>
 * 满足率、缺料数都是集合运算（用户主料集合 ∩ 菜谱主料集合），ES 的
 * {@code function_score} 表达不了；而「评价人数 ≥ N」这类业务阈值更不该藏进
 * ES 的 score 里 —— 那会让判定逻辑无法解释、也无法单独测试。<br>
 * 分工：ES 负责从海量候选里粗排挑出一批，应用层负责过滤、最终排序与判定。
 *
 * <h3>食材的两条路</h3>
 * 用户在食材表里点选的食材带主数据 ID；在输入框里自由敲的（如「松露」）不一定有。
 * 能按名字/别名归一的，统一并进 ID 桶（这样「西红柿」和点选「番茄」是同一件事）；
 * 归不了的走名字路。两边的召回与命中计算各走一条，最后加总 ——
 * 因为用户侧两个桶是互斥的，所以不会重复计数。
 */
@Service
@RequiredArgsConstructor
public class DishSearchService {

    private static final Logger log = LoggerFactory.getLogger(DishSearchService.class);

    /** 只用现有食材：缺料必须为 0 */
    private static final String MODE_STRICT = "strict";

    /** 允许补充少量食材：缺料 ≤ maxMissing */
    private static final String MODE_LOOSE = "loose";

    /** loose 模式默认允许缺几样 */
    private static final int DEFAULT_MAX_MISSING = 2;

    /** 排序策略名：新策略（满足率）与旧基线（覆盖率），用于对照实验 */
    private static final String STRATEGY_LEGACY = "legacy";

    private final DishSearchRepository dishSearchRepository;

    private final EsProperties properties;

    private final IngredientDirectory directory;

    /**
     * 按食材组合召回菜品。
     */
    public DishRecallResult search(DishSearchRequest request) {
        // ---------- 1. 参数规范化（含别名归一化） ----------
        RequestedIngredients wanted = normalizeIngredients(
                request.getIngredientIds(), request.getFreeIngredients());
        Constraints constraints = Constraints.from(request);

        // ---------- 2. ES 召回（只保证"至少用到一样"，真正的约束在下面） ----------
        List<RecalledDish> recalled = dishSearchRepository.recall(
                wanted.ids(), wanted.names(), request.getTasteTags());

        // ---------- 3. 应用层精排（含硬约束过滤） ----------
        // 排序策略：请求级覆盖优先（对照实验用），否则用服务端配置
        String strategy = resolveStrategy(request.getStrategy());
        RankOutcome outcome = rank(recalled, wanted, constraints, strategy);
        List<RecalledDish> ranked = outcome.kept();

        // ---------- 4. 直出判定 ----------
        RecalledDish best = ranked.isEmpty() ? null : ranked.get(0);
        boolean directReturn = best != null && best.isDirectEligible();

        // ---------- 5. 组装响应 ----------
        int page = request.getPage() == null ? 1 : request.getPage();
        int size = request.getSize() == null ? 10 : request.getSize();

        DishRecallResult result = new DishRecallResult();
        result.setDirectReturn(directReturn);
        // 用户"手边有几样食材"——表内表外都算，前端拿它算命中比例的分母
        result.setRequestedIngredientCount(wanted.total());
        result.setTotal(ranked.size());
        result.setPage(page);
        result.setSize(size);
        result.setBest(best == null ? null : toItem(best));
        result.setCandidates(paginate(ranked, page, size).stream().map(this::toItem).toList());

        // 召回为空有两种可能：「真的没有匹配的菜」和「ES 挂了」。
        // 前者是正常业务结果（去调 AI），后者要提示降级 —— 必须区分开。
        boolean esAvailable = !ranked.isEmpty() || dishSearchRepository.ping();
        result.setSearchEngineAvailable(esAvailable);
        result.setMissReason(directReturn ? null
                : buildMissReason(ranked, outcome.filteredOut(), wanted, constraints, esAvailable));

        log.info("食材召回：所选 {} 种（主料 {}，表内 {} / 表外 {}，模式={}），"
                        + "ES 召回 {} 条，约束筛掉 {} 条，可用 {} 条，直出={}{}",
                wanted.total(), wanted.mainTotal(), wanted.ids().size(), wanted.names().size(),
                constraints.mode(), recalled.size(), outcome.filteredOut(), ranked.size(), directReturn,
                directReturn ? "" : "（" + result.getMissReason() + "）");

        return result;
    }

    // ==================== 参数规范化 ====================

    /**
     * 用户这次「手边有什么」的规范化结果。
     *
     * <p>两个桶是<b>互斥</b>的：一样食材要么归一到主数据拿到了 ID（进 {@code ids}），
     * 要么没有（进 {@code names}），不会两边都占。这个不变式是命中数不重复计数的前提 ——
     * 所以自由输入的名字要<b>先尝试归一</b>，归上了就搬进 ID 桶、不再留在名字桶里。
     *
     * <p>{@code mainIds/mainNames} 是前两个桶里<b>去掉常备调料</b>的部分。
     * 满足率与缺料只算主料：菜谱要求"盐"而你没勾盐，不该被判成"缺一样料"。
     *
     * @param ids       有主数据 ID 的食材（点选的，或自由输入但按名字/别名归一成功的）
     * @param names     没有 ID 的食材名（食材主数据之外的真正自由输入）
     * @param mainIds   其中算主料的（排除调料类目）
     * @param mainNames 其中算主料的名字
     */
    private record RequestedIngredients(List<Long> ids, List<String> names,
                                        List<Long> mainIds, List<String> mainNames) {
        int total() {
            return ids.size() + names.size();
        }

        int mainTotal() {
            return mainIds.size() + mainNames.size();
        }
    }

    /**
     * 参数规范化：ID 去重 + 升序，名字去空白 + 按别名归一 + 去重。
     *
     * <p>ID 升序不是为了好看：它让「选 A 再选 B」和「选 B 再选 A」产生完全一样的
     * 请求参数，签名才能稳定 —— 日志比对、AI 调用的单飞去重都依赖这一点。
     */
    private RequestedIngredients normalizeIngredients(List<Long> rawIds, List<String> rawNames) {
        Set<Long> idSet = new LinkedHashSet<>();
        if (rawIds != null) {
            rawIds.stream().filter(Objects::nonNull).forEach(idSet::add);
        }

        Set<String> unresolved = new LinkedHashSet<>();
        if (rawNames != null) {
            for (String raw : rawNames) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String name = raw.trim();
                // 【本次改动的要点】自由输入先按名字/别名归一：
                // 「西红柿」能归到番茄的 ID，于是它和"点选番茄"变成同一件事，
                // 既不会重复计数，也能命中那些按 ID 索引了番茄的菜。
                Long id = directory.resolveId(name);
                if (id != null) {
                    idSet.add(id);
                } else {
                    unresolved.add(name);
                }
            }
        }

        List<Long> ids = new ArrayList<>(idSet);
        ids.sort(Comparator.naturalOrder());
        List<String> names = new ArrayList<>(unresolved);

        // 校验放在这里而不是 DTO 注解上：合法性取决于「两个桶的总数」，
        // 单看任何一个字段都会误判（只输入表外食材时 ingredientIds 本来就是空的）
        if (ids.isEmpty() && names.isEmpty()) {
            throw new BusinessException("请至少选择一种食材");
        }

        int max = properties.getRecall().getMaxIngredients();
        if (ids.size() + names.size() > max) {
            throw new BusinessException("一次最多选择 " + max + " 种食材");
        }

        List<Long> mainIds = ids.stream().filter(id -> !directory.isSeasoning(id)).toList();
        List<String> mainNames = names.stream()
                .filter(name -> !directory.isSeasoningName(name))
                .toList();

        return new RequestedIngredients(ids, names, mainIds, mainNames);
    }

    /**
     * 硬约束：禁用食材、最长耗时、缺料上限。
     *
     * <p>这些条件必须<b>先过滤再排序</b>，不能做成扣分项：
     * 过敏食材扣完分仍然可能排进前五，那等于没拦。
     */
    private record Constraints(String mode, int maxMissing,
                               Set<Long> excludeIds, Set<String> excludeNames,
                               Integer maxCookTimeMin) {

        static Constraints from(DishSearchRequest request) {
            // allowPartial 是历史字段，语义映射成 loose：true 等价于"允许补齐少量主料"
            String mode = request.getMode();
            if (mode == null || mode.isBlank()) {
                mode = Boolean.TRUE.equals(request.getAllowPartial()) ? MODE_LOOSE : MODE_STRICT;
            }
            mode = mode.trim().toLowerCase(Locale.ROOT);
            if (!MODE_STRICT.equals(mode) && !MODE_LOOSE.equals(mode)) {
                // 写错了就按最严格的来：宁可少给结果，也不要悄悄放宽安全约束
                log.warn("未知的召回模式「{}」，按 strict 处理", request.getMode());
                mode = MODE_STRICT;
            }

            Set<Long> excludeIds = new LinkedHashSet<>();
            if (request.getExcludeIngredientIds() != null) {
                request.getExcludeIngredientIds().stream().filter(Objects::nonNull).forEach(excludeIds::add);
            }
            Set<String> excludeNames = new LinkedHashSet<>();
            if (request.getExcludeIngredients() != null) {
                for (String name : request.getExcludeIngredients()) {
                    String key = IngredientDirectory.key(name);
                    if (key != null) {
                        excludeNames.add(key);
                    }
                }
            }

            int maxMissing = request.getMaxMissing() == null ? DEFAULT_MAX_MISSING : request.getMaxMissing();
            return new Constraints(mode, maxMissing, excludeIds, excludeNames, request.getMaxCookTimeMin());
        }

        /** 这个缺料数在当前模式下是否可接受 */
        boolean allows(int missing) {
            return MODE_STRICT.equals(mode) ? missing == 0 : missing <= maxMissing;
        }
    }

    // ==================== 精排 ====================

    /** 精排结果：留下的候选 + 被硬约束筛掉多少条（用于解释"为什么没结果"） */
    private record RankOutcome(List<RecalledDish> kept, int filteredOut) {
    }

    private RankOutcome rank(List<RecalledDish> recalled, RequestedIngredients wanted,
                             Constraints constraints, String strategy) {
        List<RecalledDish> kept = new ArrayList<>(recalled.size());
        int filteredOut = 0;

        for (RecalledDish item : recalled) {
            DishDoc doc = item.getDoc();
            if (doc == null) {
                continue;
            }

            computeMetrics(item, doc, wanted);

            if (isExcluded(doc, constraints)) {
                filteredOut++;
                log.debug("淘汰「{}」：命中禁用食材", doc.getName());
                continue;
            }
            if (constraints.maxCookTimeMin() != null
                    && nz(doc.getCookTimeMin()) > constraints.maxCookTimeMin()) {
                filteredOut++;
                log.debug("淘汰「{}」：耗时 {} 分钟超过上限 {}",
                        doc.getName(), nz(doc.getCookTimeMin()), constraints.maxCookTimeMin());
                continue;
            }
            if (!constraints.allows(item.getMissingCount())) {
                filteredOut++;
                log.debug("淘汰「{}」：缺 {} 样主料，模式={}",
                        doc.getName(), item.getMissingCount(), constraints.mode());
                continue;
            }

            item.setFinalScore(score(item, doc, strategy));
            item.setDirectEligible(isDirectEligible(item, doc));
            kept.add(item);
        }

        kept.sort(Comparator.comparingDouble(RecalledDish::getFinalScore).reversed());
        return new RankOutcome(kept, filteredOut);
    }

    /**
     * 算一条候选的全部指标。
     *
     * <p>两套口径<b>同时</b>保留，不是冗余：
     * <ul>
     *   <li>旧口径 {@code hitCount/similarity}：命中食材数 / 你选了几样（含调料）。
     *       它是对照实验里 legacy 策略的输入，必须保持和基线一致，所以不能改。</li>
     *   <li>新口径 {@code satisfactionRate/utilizationRate/missingCount}：
     *       只在主料上算，回答"现在能不能做"。</li>
     * </ul>
     */
    private void computeMetrics(RecalledDish item, DishDoc doc, RequestedIngredients wanted) {
        // ---- 旧口径（保持与基线可比）----
        int hitAll = countHits(doc, new HashSet<>(wanted.ids()), new HashSet<>(wanted.names()));
        item.setHitCount(hitAll);
        int totalAll = wanted.total();
        item.setSimilarity(totalAll == 0 ? 0d : (double) hitAll / totalAll);

        // ---- 新口径：主料 ----
        Set<Long> uIds = new HashSet<>(wanted.mainIds());
        Set<String> uNames = new HashSet<>(wanted.mainNames());

        List<RequiredMain> required = requiredMains(doc);
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (RequiredMain req : required) {
            if (owns(req, uIds, uNames)) {
                matched.add(req.name());
            } else {
                missing.add(req.name());
            }
        }

        item.setMatchedIngredients(matched);
        item.setMissingIngredients(missing);
        item.setMissingCount(missing.size());

        double satisfaction = required.isEmpty() ? 1d : (double) matched.size() / required.size();
        item.setSatisfactionRate(satisfaction);

        int userMain = uIds.size() + uNames.size();
        // 用户只选了调料（主料数为 0）时利用率无意义，记 0 —— 此时真正起作用的是满足率与质量分
        item.setUtilizationRate(userMain == 0 ? 0d : (double) matched.size() / userMain);
    }

    /**
     * 这道菜要求的「必需主料」。
     *
     * <p><b>为什么用"全部非调料食材"而不是索引里的 {@code mainIngredientIds}：</b>
     * 后者听起来更贴题（就是"主料"），但实测数据里它<b>太稀疏</b> ——
     * 30 道已发布的菜，每道只标了 1 个 main_flag=1。
     * 例如「番茄牛肉鸡蛋紫米粥」的主料数组只有 [番茄]，
     * 于是"只拿番茄来搜"也会算出满足率 100%、缺料 0 ——
     * 这恰恰是说明文档要治的那个假象（"需要番茄、鸡蛋、牛肉及奶酪的菜也可能获得 100% 覆盖率"）。
     *
     * <p>而从「这道菜现在能不能做」的语义出发，答案是清楚的：
     * <b>只要菜谱里用到了、而你又没有的（且不是常备调料），就是缺料</b>，
     * 不管厨师管它叫主料还是辅料 —— 缺了牛肉，你就是得去买。
     * 所以 R 取名字列表里全部非调料食材；{@code mainIngredientIds} 只在
     * 名字列表为空（异常数据）时兜底，另外保留它是为了将来做"主料命中额外加权"。
     *
     * <p>名字列表的权威性来自索引构建：{@code DishEsSyncService.buildDoc} 会无条件收集
     * 全部食材名（含没有主数据 ID 的表外食材），所以它才是这道菜食材的完整集合。
     */
    private List<RequiredMain> requiredMains(DishDoc doc) {
        List<RequiredMain> required = new ArrayList<>();

        for (String name : nzList(doc.getIngredientNames())) {
            if (name == null || directory.isSeasoningName(name)) {
                continue;
            }
            if (required.stream().anyMatch(r -> name.equals(r.name()))) {
                continue;
            }
            required.add(new RequiredMain(directory.resolveId(name), name));
        }

        // 兜底：名字列表为空（异常/老数据）时，退回用主料 ID 数组
        if (required.isEmpty()) {
            for (Long id : new LinkedHashSet<>(nzList(doc.getMainIngredientIds()))) {
                String name = directory.nameOf(id);
                required.add(new RequiredMain(id, name != null ? name : ("食材#" + id)));
            }
        }
        return required;
    }

    /**
     * 判断这味必需主料用户是否已有。
     *
     * <p>三条路都要试，因为名字与 ID 两个方向的解析能力不一定都齐：
     * <ol>
     *   <li>用户按名字输入过它（表外食材走这条）；</li>
     *   <li>它自己解析出了 ID，而用户按 ID 选过（最常见）；</li>
     *   <li>反向查：拿用户选的每个 ID 去目录里反查名字，看是否同名 ——
     *       名字→ID 这一步查不到时（目录没收录该名字的别名），这条能兜住。</li>
     * </ol>
     */
    private boolean owns(RequiredMain req, Set<Long> uIds, Set<String> uNames) {
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

    /** 一道菜要求的一味主料：有主数据 ID 时按 ID 判断归属，否则只能按名字判 */
    private record RequiredMain(Long id, String name) {
    }

    /**
     * 综合分。
     *
     * <p>两套策略由 {@code es.score.strategy} 切换，为的是做对照实验：
     * 同一批查询分别跑 legacy 与新策略，比较 Precision@5 与平均缺料数。
     */
    private double score(RecalledDish item, DishDoc doc, String strategy) {
        EsProperties.Score cfg = properties.getScore();
        double collectNorm = collectNorm(doc);
        double quality = nz(doc.getRatingWilson());

        if (STRATEGY_LEGACY.equalsIgnoreCase(strategy)) {
            return cfg.getWeightSimilarity() * item.getSimilarity()
                    + cfg.getWeightWilson() * quality
                    + cfg.getWeightCollect() * collectNorm;
        }

        int cap = cfg.getMissingPenaltyCap() <= 0 ? 1 : cfg.getMissingPenaltyCap();
        double penalty = cfg.getMissingPenalty()
                * Math.min(1d, (double) item.getMissingCount() / cap);

        return cfg.getWeightSatisfaction() * item.getSatisfactionRate()
                + cfg.getWeightUtilization() * item.getUtilizationRate()
                + cfg.getWeightWilson() * quality
                + cfg.getWeightCollect() * collectNorm
                - penalty;
    }

    private double collectNorm(DishDoc doc) {
        double baseline = Math.log1p(properties.getScore().getCollectBaseline());
        return baseline <= 0 ? 0d : Math.min(1d, Math.log1p(nz(doc.getCollectCount())) / baseline);
    }

    /**
     * 解析本次使用的排序策略。
     *
     * <p>请求里写了就用请求的（对照实验要能在同一份菜库快照上跑两臂），
     * 没写就用服务端配置，写了个不认识的值则退回服务端配置并记 warn ——
     * 这里不能报错：排序口径写错不该让整个召回接口 500。
     */
    private String resolveStrategy(String override) {
        String serverDefault = properties.getScore().getStrategy();
        if (override == null || override.isBlank()) {
            return serverDefault;
        }
        String normalized = override.trim().toLowerCase(Locale.ROOT);
        if (STRATEGY_LEGACY.equals(normalized) || "satisfaction".equals(normalized)) {
            return normalized;
        }
        log.warn("未知的排序策略「{}」，回退到服务端配置「{}」", override, serverDefault);
        return serverDefault;
    }

    /**
     * 直出判定。
     *
     * <p><b>第一条就是"缺料为 0"</b>：只要还缺主料，就谈不上"直接用这道菜"——
     * 说明文档明确要求「严格模式不得返回缺失必需主料的直出结果」。
     *
     * <p>质量上有两条路：评价充分（人多 + 威尔逊下界达标）、少量但高分
     * （新菜天然没人评价，只按第一条卡就永远直出不了，只能一直调 AI）。
     * 走了哪条路会记在 {@code directPath} 里，便于事后核对这条宽松通道有没有被滥用。
     */
    private boolean isDirectEligible(RecalledDish item, DishDoc doc) {
        if (item.getMissingCount() > 0) {
            return false;
        }
        EsProperties.Judge judge = properties.getJudge();

        if (nz(doc.getRatingCount()) >= judge.getMinRatingCount()
                && nz(doc.getRatingWilson()) >= judge.getMinRatingWilson()) {
            item.setDirectPath("充分评价");
            return true;
        }
        if (nz(doc.getRatingCount()) >= judge.getLightRatingCount()
                && nz(doc.getRatingAvg()) >= judge.getLightRatingAvg()) {
            item.setDirectPath("少量高分");
            return true;
        }
        return false;
    }

    /**
     * 命中食材数 = 有 ID 的那部分交集 + 只有名字的那部分交集。
     *
     * <p><b>为什么这样加不会重复计数</b>：用户侧的两个桶互斥（见 {@link RequestedIngredients}），
     * 所以「按 ID 命中」和「按名字命中」针对的是<b>不同批</b>食材。
     */
    private int countHits(DishDoc doc, Set<Long> idSet, Set<String> nameSet) {
        int hit = 0;

        for (Long id : nzList(doc.getIngredientIds())) {
            if (id != null && idSet.contains(id)) {
                hit++;
            }
        }
        for (String name : nzList(doc.getIngredientNames())) {
            if (name != null && nameSet.contains(name)) {
                hit++;
            }
        }
        return hit;
    }

    private boolean isExcluded(DishDoc doc, Constraints constraints) {
        if (constraints.excludeIds().isEmpty() && constraints.excludeNames().isEmpty()) {
            return false;
        }
        for (Long id : nzList(doc.getIngredientIds())) {
            if (id != null && constraints.excludeIds().contains(id)) {
                return true;
            }
        }
        for (String name : nzList(doc.getIngredientNames())) {
            if (name != null && constraints.excludeNames().contains(IngredientDirectory.key(name))) {
                return true;
            }
        }
        return false;
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
        item.setDirectPath(recalled.getDirectPath());
        item.setSatisfactionRate(recalled.getSatisfactionRate());
        item.setUtilizationRate(recalled.getUtilizationRate());
        item.setMissingCount(recalled.getMissingCount());
        item.setMatchedIngredients(recalled.getMatchedIngredients());
        item.setMissingIngredients(recalled.getMissingIngredients());
        item.setReason(buildReason(recalled));
        return item;
    }

    /** 推荐理由：一句话说清"为什么给你推荐它 / 为什么还不能直接用" */
    private String buildReason(RecalledDish item) {
        if (item.getMissingCount() == 0) {
            return item.isDirectEligible()
                    ? "主料齐全，直接用这道菜（" + item.getDirectPath() + "）"
                    : "主料齐全，可照着做";
        }
        return String.format("还缺 %d 样主料：%s",
                item.getMissingCount(), String.join("、", item.getMissingIngredients()));
    }

    /**
     * 说明「为什么没直出」。
     * 这个字符串既给前端做提示，也是线上排查时最有用的线索 ——
     * 它能直接告诉你卡在哪一步上。
     */
    private String buildMissReason(List<RecalledDish> ranked, int filteredOut,
                                   RequestedIngredients wanted, Constraints constraints,
                                   boolean esAvailable) {
        if (!esAvailable) {
            return "检索引擎暂时不可用，请稍后重试";
        }
        if (ranked.isEmpty()) {
            if (filteredOut > 0) {
                return String.format("有 %d 道菜用到了这些食材，但不满足当前约束（缺主料/禁用食材/耗时），已按条件筛掉",
                        filteredOut);
            }
            return String.format("库里还没有包含这 %d 种食材的菜品，需要 AI 生成", wanted.total());
        }

        RecalledDish top = ranked.get(0);
        DishDoc doc = top.getDoc();
        if (top.getMissingCount() > 0) {
            return String.format("最接近的「%s」还缺 %d 样主料：%s",
                    doc.getName(), top.getMissingCount(), String.join("、", top.getMissingIngredients()));
        }
        EsProperties.Judge judge = properties.getJudge();
        if (nz(doc.getRatingCount()) < judge.getLightRatingCount()) {
            return String.format("最接近的「%s」评价人数不足（%d < %d 人），分数还不可信",
                    doc.getName(), nz(doc.getRatingCount()), judge.getLightRatingCount());
        }
        return String.format("最接近的「%s」评分不够高（均分 %.1f，威尔逊下界 %.2f），未达直出标准",
                doc.getName(), nz(doc.getRatingAvg()), nz(doc.getRatingWilson()));
    }

    private static <T> List<T> nzList(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static double nz(Double value) {
        return value == null ? 0d : value;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
