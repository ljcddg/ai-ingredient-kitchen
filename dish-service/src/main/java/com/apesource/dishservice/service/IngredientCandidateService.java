package com.apesource.dishservice.service;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.entity.Ingredient;
import com.apesource.dishservice.entity.IngredientCandidate;
import com.apesource.dishservice.es.EsSyncTaskService;
import com.apesource.dishservice.event.DishPublishedEvent;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.IngredientCandidateMapper;
import com.apesource.dishservice.mapper.IngredientMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 食材候选：记录自由输入的食材，由管理员审核后转正成正式食材。
 *
 * <p>这是「表外食材能被搜到」的<b>治本那一半</b>：
 * 名字召回（见 {@code EsDslBuilder} 的双路查询）解决的是「现在就能搜到」，
 * 但不精确；转正之后食材有了主数据 ID，走的就是 ID 精确匹配，
 * 而且能参与营养计算、出现在食材表里给别的人点选。
 *
 * <p><b>三个动作，职责分明：</b>
 * <ul>
 *   <li>{@link #recordUsage} —— 草稿落库时顺手记一笔（高频、绝不抛异常）</li>
 *   <li>{@link #approve} —— 管理员审核<b>通过</b>：转正并留痕（写主数据 → 回填明细 → 重写索引）</li>
 *   <li>{@link #reject} —— 管理员审核<b>拒绝</b>：标记拒绝 + 记理由，之后不再计数</li>
 * </ul>
 *
 * <p><b>为什么不再"按次数自动转正"（本次的改动要点）：</b>
 * 旧实现是 {@code use_count >= 3} 就批量转正。它把「出现得多」当成了
 * 「这个名字有效」的证据，而两者并不等价 —— 一个错别字被几个人先后敲出来，
 * 一样能刷到 3 次，然后就被写进正式食材表。而主数据是所有归一化与营养计算的基准，
 * 污染它的代价远高于少收录一个食材。
 * 现在阈值退化成"待办清单里的优先提示"（见 {@link #HOT_THRESHOLD}），
 * 是否转正由管理员逐条判定，并记录审核人、时间、理由。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngredientCandidateService {

    /**
     * 热度提示阈值：达到这个次数的候选，在待办清单里值得优先看。
     *
     * <p><b>它已经不再决定谁被转正</b>（旧实现正是这么用的，见类注释）。
     * 现在它只有一个用途：让运营先看"大家都在敲、但库里还没有"的那些名字。
     * 清单本身按 {@code use_count} 倒序返回，这个常量供前端给这些行加个提醒标记。
     */
    public static final int HOT_THRESHOLD = 3;

    /** 转正食材的默认分类 —— t_ingredient.category 是 NOT NULL，必须给值 */
    private static final String DEFAULT_CATEGORY = "其他";

    /** 单次审核的条数上限：防止一个请求把整张表扫一遍 */
    private static final int MAX_REVIEW_BATCH = 100;

    private final IngredientCandidateMapper candidateMapper;

    private final IngredientMapper ingredientMapper;

    private final DishIngredientMapper dishIngredientMapper;

    /** 审核通过后要重写索引：登记待办（事务内）+ 提交后事件触发同步 */
    private final EsSyncTaskService esSyncTaskService;

    private final ApplicationEventPublisher events;

    /**
     * 记一笔：这些食材不在主数据里。已存在则次数 +1。
     *
     * <p><b>绝不抛异常。</b>它跑在「草稿落库」那个事务里，抛出去会把用户刚生成好的菜
     * 一起回滚 —— 而记候选只是锦上添花的手段，不值得拿用户等了二十秒的成果去换。
     * 所以每个名字单独 try/catch，一个失败不影响其他。
     *
     * @param names 落库时没能归一到主数据 ID 的食材名
     */
    public void recordUsage(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            String key = nameKey(name);
            try {
                IngredientCandidate exist = candidateMapper.selectOne(
                        Wrappers.<IngredientCandidate>lambdaQuery()
                                .eq(IngredientCandidate::getNameKey, key));

                if (exist == null) {
                    IngredientCandidate fresh = new IngredientCandidate();
                    fresh.setName(name);
                    fresh.setNameKey(key);
                    fresh.setUseCount(1);
                    fresh.setStatus(IngredientCandidate.STATUS_CANDIDATE);
                    fresh.setFirstSeen(LocalDateTime.now());
                    fresh.setLastSeen(LocalDateTime.now());
                    candidateMapper.insert(fresh);
                } else if (Objects.equals(exist.getStatus(), IngredientCandidate.STATUS_CANDIDATE)) {
                    // 已转正 / 已拒绝的不再计数：前者已经进主数据了，
                    // 后者是明确不想要的垃圾输入，计数只会让它反复出现在待办里
                    candidateMapper.update(null, Wrappers.<IngredientCandidate>lambdaUpdate()
                            .eq(IngredientCandidate::getId, exist.getId())
                            .set(IngredientCandidate::getUseCount, exist.getUseCount() + 1)
                            .set(IngredientCandidate::getLastSeen, LocalDateTime.now()));
                }
            } catch (Exception e) {
                log.warn("记录食材候选失败（不影响生成结果）：{} - {}", name, e.getMessage());
            }
        }
    }

    // ================================================================== 审核

    /**
     * 审核通过：把候选转正成正式食材。
     *
     * <p>每条候选做四件事：写/认领主数据 → 回填历史明细的 {@code ingredient_id}
     * → 标记候选已转正并记下审核人/时间/理由 → 把受影响的菜重写一遍索引。
     * 最后一步不能省：索引里的 {@code ingredientIds} 是从明细表来的，
     * 不回写索引的话，这些菜在 ES 里依然只有名字、没有 ID，转正就白做了。
     *
     * <p>状态不是「候选」的（已被别人审过、或者已经拒绝过）会被跳过并计入
     * {@code skipped}，不报错 —— 管理页面里两个人同时点通过是正常操作，
     * 幂等比抛异常对使用者更友好。
     *
     * @param candidateIds 要转正的候选ID（管理员显式指定）
     * @param reviewerId   审核人（管理员）用户ID
     * @param reason       审核说明，可为空
     */
    @Transactional(rollbackFor = Exception.class)
    public ReviewReport approve(Collection<Long> candidateIds, long reviewerId, String reason) {
        List<Long> ids = normalizeIds(candidateIds);

        int approved = 0;
        int skipped = 0;
        int refilled = 0;
        Set<Long> touched = new LinkedHashSet<>();

        for (Long id : ids) {
            Promotion promotion = promoteOne(id, reviewerId, reason);
            if (promotion == null) {
                skipped++;
                continue;
            }
            approved++;
            refilled += promotion.refilledRows();
            touched.addAll(promotion.touchedDishIds());
        }

        int queued = enqueueSync(touched);
        ReviewReport report = new ReviewReport(approved, 0, skipped, refilled, touched.size(), queued);
        log.info("食材候选审核-通过：{}（审核人 {}）", report, reviewerId);
        return report;
    }

    /**
     * 审核拒绝：标记为拒绝并记下理由，之后 {@link #recordUsage} 不再为它计数。
     *
     * <p><b>理由必填</b>：拒绝是"把某个名字永久挡在主数据之外"的决定，
     * 没有理由的话，过几个月连自己都想不起来当初为什么拒 —— 而这个决定恰恰是最需要复盘的。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReviewReport reject(Collection<Long> candidateIds, long reviewerId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("拒绝食材候选必须填写理由，便于后续复盘");
        }
        List<Long> ids = normalizeIds(candidateIds);

        int rejected = 0;
        int skipped = 0;
        for (Long id : ids) {
            // 条件更新：只有还是「候选」的才能被拒。已经转正的不许被拒
            //（那会让主数据里多出一个"来源已被拒绝"的食材，说不清该不该留）
            int updated = candidateMapper.update(null, Wrappers.<IngredientCandidate>lambdaUpdate()
                    .eq(IngredientCandidate::getId, id)
                    .eq(IngredientCandidate::getStatus, IngredientCandidate.STATUS_CANDIDATE)
                    .set(IngredientCandidate::getStatus, IngredientCandidate.STATUS_REJECTED)
                    .set(IngredientCandidate::getReviewedBy, reviewerId)
                    .set(IngredientCandidate::getReviewTime, LocalDateTime.now())
                    .set(IngredientCandidate::getReviewReason, reason.trim()));
            if (updated > 0) {
                rejected++;
            } else {
                skipped++;
            }
        }

        ReviewReport report = new ReviewReport(0, rejected, skipped, 0, 0, 0);
        log.info("食材候选审核-拒绝：{}（审核人 {}，理由：{}）", report, reviewerId, reason);
        return report;
    }

    /**
     * 待审清单，按热度倒序。
     * <p>给运维/管理员看的：现在大家都在敲哪些食材表里还没有的东西。
     * 达到 {@link #HOT_THRESHOLD} 的排在前面，但是否转正由人来判断。
     */
    public List<IngredientCandidate> topCandidates(int limit) {
        return candidateMapper.selectList(Wrappers.<IngredientCandidate>lambdaQuery()
                .eq(IngredientCandidate::getStatus, IngredientCandidate.STATUS_CANDIDATE)
                .orderByDesc(IngredientCandidate::getUseCount)
                .orderByDesc(IngredientCandidate::getLastSeen)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }

    /**
     * 已审核历史（转正 + 拒绝），按审核时间倒序。
     *
     * <p>为什么要能查已拒绝的：拒绝是一个不可逆的决定，
     * 用户过一阵子再敲同一个名字时，运营得能看到"上次是谁、因为什么拒的"，
     * 否则同一条候选会被反复拒、反复讨论。
     */
    public List<IngredientCandidate> reviewedHistory(int limit) {
        return candidateMapper.selectList(Wrappers.<IngredientCandidate>lambdaQuery()
                .in(IngredientCandidate::getStatus,
                        IngredientCandidate.STATUS_PROMOTED, IngredientCandidate.STATUS_REJECTED)
                .orderByDesc(IngredientCandidate::getReviewTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    // ================================================================== 内部

    /**
     * 转正单条候选。
     *
     * @return null 表示这条不是「候选」状态、已被处理过，调用方按跳过处理
     */
    private Promotion promoteOne(Long candidateId, long reviewerId, String reason) {
        IngredientCandidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            log.warn("候选食材 {} 不存在，跳过", candidateId);
            return null;
        }

        // ① 先占有这次审核：条件更新保证同一条候选只会被转正一次。
        //    放在写主数据之前，是为了让"谁有权写主数据"这件事先定下来 ——
        //    否则并发审核会各自写一份主数据，靠唯一键报错来收场。
        int claimed = candidateMapper.update(null, Wrappers.<IngredientCandidate>lambdaUpdate()
                .eq(IngredientCandidate::getId, candidateId)
                .eq(IngredientCandidate::getStatus, IngredientCandidate.STATUS_CANDIDATE)
                .set(IngredientCandidate::getStatus, IngredientCandidate.STATUS_PROMOTED)
                .set(IngredientCandidate::getReviewedBy, reviewerId)
                .set(IngredientCandidate::getReviewTime, LocalDateTime.now())
                .set(IngredientCandidate::getReviewReason, blankToNull(reason)));
        if (claimed == 0) {
            log.info("候选食材 {}（{}）状态已不是「候选」，跳过", candidateId, candidate.getName());
            return null;
        }

        // ② 主数据里已经有同名的就直接认领，不重复插（唯一键 uk_name 也会拦）
        Ingredient exist = ingredientMapper.selectOne(
                Wrappers.<Ingredient>lambdaQuery().eq(Ingredient::getName, candidate.getName()));

        Long masterId;
        if (exist != null) {
            masterId = exist.getId();
        } else {
            Ingredient ingredient = new Ingredient();
            ingredient.setName(candidate.getName());
            ingredient.setCategory(DEFAULT_CATEGORY);
            // 营养字段（calorie/protein/…）留 DB 默认的 0 —— 表示"还不知道"。
            // 营养校验那边会把 0 当作没有数据，直接放弃估算（见 ai-service 的 nutrition.estimate_calories_per_serving）。
            ingredientMapper.insert(ingredient);
            masterId = ingredient.getId();
        }

        // ③ 回填历史明细：这些食材以前没有 ID，现在有了
        List<DishIngredient> rows = dishIngredientMapper.selectList(
                Wrappers.<DishIngredient>lambdaQuery()
                        .eq(DishIngredient::getIngredientName, candidate.getName())
                        .isNull(DishIngredient::getIngredientId));
        List<Long> touchedDishIds = new ArrayList<>();
        for (DishIngredient row : rows) {
            dishIngredientMapper.update(null, Wrappers.<DishIngredient>lambdaUpdate()
                    .eq(DishIngredient::getId, row.getId())
                    .set(DishIngredient::getIngredientId, masterId));
            touchedDishIds.add(row.getDishId());
        }

        // ④ 回填主数据ID（状态在第①步已经改成「已转正」，这里补上指向）
        candidateMapper.update(null, Wrappers.<IngredientCandidate>lambdaUpdate()
                .eq(IngredientCandidate::getId, candidateId)
                .set(IngredientCandidate::getIngredientId, masterId));

        log.info("食材候选转正：「{}」被用了 {} 次，收录为主数据 ID={}，回填 {} 条明细",
                candidate.getName(), candidate.getUseCount(), masterId, rows.size());
        return new Promotion(masterId, rows.size(), touchedDishIds);
    }

    /**
     * 把受影响的菜登记为「待重新同步索引」，并立刻发一个发布事件。
     *
     * <p><b>为什么从"当场同步"改成"登记 + 事件"</b>：这个方法跑在审核事务里
     * （{@code approve} 带 {@code @Transactional}）。在事务里直接写 ES 有两个毛病：
     * <ol>
     *   <li>事务万一回滚，已经写进索引的东西回不来 ——
     *       索引里会留下一份"库里并不存在"的文档；</li>
     *   <li>ES 一慢，事务与行锁就被一起拖长，审核接口跟着变慢。</li>
     * </ol>
     * 登记（事务内）+ 提交后立即试一次（事件）+ 后台按退避重试，三件事合起来
     * 既保住了"改完很快就生效"，也不会再出现"同步失败就等到凌晨"的情况。
     *
     * @return 登记成功的菜品数（不再返回"同步成功数"：同步是异步的，当场报不出来）
     */
    private int enqueueSync(Set<Long> dishIds) {
        int queued = 0;
        for (Long dishId : dishIds) {
            try {
                esSyncTaskService.enqueue(dishId, LocalDateTime.now());
                events.publishEvent(new DishPublishedEvent(dishId));
                queued++;
            } catch (Exception e) {
                log.warn("登记索引同步失败 dishId={}：{}", dishId, e.getMessage());
            }
        }
        return queued;
    }

    /** 去重 + 限流 + 空值校验，统一在这里做，两个审核入口共用 */
    private List<Long> normalizeIds(Collection<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            throw new BusinessException("请至少选择一条候选食材");
        }
        List<Long> ids = candidateIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            throw new BusinessException("请至少选择一条候选食材");
        }
        if (ids.size() > MAX_REVIEW_BATCH) {
            throw new BusinessException("一次最多审核 " + MAX_REVIEW_BATCH + " 条，请分批处理");
        }
        return ids;
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * 归一化键：去空白 + 小写。
     * <p>只做这一步是因为食材名很短、几乎没有歧义；
     * 真要做同义归一应该走主数据的 {@code alias} 字段，而不是在这里猜。
     */
    private String nameKey(String name) {
        return name.replaceAll("\\s+", "").toLowerCase();
    }

    /** 单条候选转正后的结果 */
    private record Promotion(Long masterId, int refilledRows, List<Long> touchedDishIds) {
    }

    /**
     * 一次审核的结果。
     *
     * @param approved      转正成功的候选数
     * @param rejected      拒绝成功的候选数
     * @param skipped       状态不是「候选」而跳过的条数（已被别人审过）
     * @param refilledRows  回填的历史明细条数
     * @param touchedDishes 受影响的菜品数
     * @param queuedDishes  已登记"待重新同步索引"的菜品数
     *                      （同步是异步的，这里报的是"欠下的债"而不是"已还清的数"）
     */
    public record ReviewReport(int approved, int rejected, int skipped,
                               int refilledRows, int touchedDishes, int queuedDishes) {
    }
}
