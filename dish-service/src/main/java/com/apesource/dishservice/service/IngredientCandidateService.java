package com.apesource.dishservice.service;

import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.entity.Ingredient;
import com.apesource.dishservice.entity.IngredientCandidate;
import com.apesource.dishservice.es.DishEsSyncService;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.IngredientCandidateMapper;
import com.apesource.dishservice.mapper.IngredientMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 食材候选：记录自由输入的食材，攒够了转正成正式食材。
 *
 * <p>这是「表外食材能被搜到」的<b>治本那一半</b>：
 * 名字召回（见 {@code EsDslBuilder} 的双路查询）解决的是「现在就能搜到」，
 * 但不精确；转正之后食材有了主数据 ID，走的就是 ID 精确匹配，
 * 而且能参与营养计算、出现在食材表里给别的人点选。
 *
 * <p>两个动作分开、职责清楚：
 * <ul>
 *   <li>{@link #recordUsage} —— 草稿落库时顺手记一笔（高频、必须不影响主流程）</li>
 *   <li>{@link #promoteReady} —— 把够次数的候选转正（低频、手动或定时触发）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngredientCandidateService {

    /**
     * 出现多少次才够转正。
     *
     * <p>取 3 是「别让一次手滑就污染主数据」和「真需求别等太久」之间的折中。
     * 学习项目里这个阈值很小，很快就能看到效果；真实生产可能要几十次 + 人工复核。
     */
    private static final int PROMOTE_THRESHOLD = 3;

    /** 转正食材的默认分类 —— t_ingredient.category 是 NOT NULL，必须给值 */
    private static final String DEFAULT_CATEGORY = "其他";

    private final IngredientCandidateMapper candidateMapper;

    private final IngredientMapper ingredientMapper;

    private final DishIngredientMapper dishIngredientMapper;

    private final DishEsSyncService esSyncService;

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

    /**
     * 把够次数的候选转正。
     *
     * <p>四步：写主数据 → 回填历史明细的 {@code ingredient_id} → 标记候选已转正 →
     * 把受影响的菜重写一遍索引。
     * <p>最后一步不能省：索引里的 {@code ingredientIds} 是从明细表来的，
     * 不回写索引的话，这些菜在 ES 里依然只有名字、没有 ID，转正就白做了。
     */
    @Transactional(rollbackFor = Exception.class)
    public PromoteReport promoteReady() {
        List<IngredientCandidate> ready = candidateMapper.selectList(
                Wrappers.<IngredientCandidate>lambdaQuery()
                        .eq(IngredientCandidate::getStatus, IngredientCandidate.STATUS_CANDIDATE)
                        .ge(IngredientCandidate::getUseCount, PROMOTE_THRESHOLD));

        int promoted = 0;
        int refilled = 0;
        Set<Long> touched = new LinkedHashSet<>();

        for (IngredientCandidate c : ready) {
            // 1) 主数据里已经有同名的就直接认领，不重复插（唯一键 uk_name 也会拦）
            Ingredient exist = ingredientMapper.selectOne(
                    Wrappers.<Ingredient>lambdaQuery().eq(Ingredient::getName, c.getName()));

            Long masterId;
            if (exist != null) {
                masterId = exist.getId();
            } else {
                Ingredient ing = new Ingredient();
                ing.setName(c.getName());
                ing.setCategory(DEFAULT_CATEGORY);
                // 营养字段（calorie/protein/…）留 DB 默认的 0 —— 表示"还不知道"。
                // 营养校验那边会把 0 当作没有数据，直接放弃估算（见 AiDishGenerator）。
                ingredientMapper.insert(ing);
                masterId = ing.getId();
            }

            // 2) 回填历史明细：这些食材以前没有 ID，现在有了
            List<DishIngredient> rows = dishIngredientMapper.selectList(
                    Wrappers.<DishIngredient>lambdaQuery()
                            .eq(DishIngredient::getIngredientName, c.getName())
                            .isNull(DishIngredient::getIngredientId));
            for (DishIngredient row : rows) {
                dishIngredientMapper.update(null, Wrappers.<DishIngredient>lambdaUpdate()
                        .eq(DishIngredient::getId, row.getId())
                        .set(DishIngredient::getIngredientId, masterId));
                touched.add(row.getDishId());
                refilled++;
            }

            // 3) 标记候选已转正
            candidateMapper.update(null, Wrappers.<IngredientCandidate>lambdaUpdate()
                    .eq(IngredientCandidate::getId, c.getId())
                    .set(IngredientCandidate::getStatus, IngredientCandidate.STATUS_PROMOTED)
                    .set(IngredientCandidate::getIngredientId, masterId));

            promoted++;
            log.info("食材候选转正：「{}」被用了 {} 次，收录为主数据 ID={}，回填 {} 条明细",
                    c.getName(), c.getUseCount(), masterId, rows.size());
        }

        // 4) 回填过的菜重写索引（单条失败不影响其他，也不让整个转正回滚）
        int synced = 0;
        for (Long dishId : touched) {
            try {
                if (esSyncService.syncOne(dishId)) {
                    synced++;
                }
            } catch (Exception e) {
                log.warn("转正后同步菜品 {} 到索引失败：{}", dishId, e.getMessage());
            }
        }

        return new PromoteReport(promoted, refilled, touched.size(), synced);
    }

    /**
     * 待收录清单，按热度倒序。
     * <p>给运维/管理员看的：现在大家都在敲哪些食材表里还没有的东西。
     */
    public List<IngredientCandidate> topCandidates(int limit) {
        return candidateMapper.selectList(Wrappers.<IngredientCandidate>lambdaQuery()
                .eq(IngredientCandidate::getStatus, IngredientCandidate.STATUS_CANDIDATE)
                .orderByDesc(IngredientCandidate::getUseCount)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }

    /**
     * 归一化键：去空白 + 小写。
     * <p>只做这一步是因为食材名很短、几乎没有歧义；
     * 真要做同义归一应该走主数据的 {@code alias} 字段，而不是在这里猜。
     */
    private String nameKey(String name) {
        return name.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 一次转正的结果。
     *
     * @param promoted     转正的食材个数
     * @param refilledRows 回填的历史明细条数
     * @param touchedDishes 受影响的菜品数（需要重写索引的）
     * @param syncedDishes 实际同步成功的菜品数
     */
    public record PromoteReport(int promoted, int refilledRows, int touchedDishes, int syncedDishes) {
    }
}
