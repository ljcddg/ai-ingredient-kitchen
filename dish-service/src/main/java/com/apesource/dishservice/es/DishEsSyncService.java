package com.apesource.dishservice.es;

import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * MySQL → ES 的同步服务：把库里的菜品组装成文档，交给 {@link DishIndexWriter} 写入。
 *
 * <p><b>方向只有一条：MySQL 是唯一事实来源，ES 只是它的检索副本。</b>
 * 所以我们永远不"反过来"用索引里的数据去修库，也不允许业务逻辑依赖索引里的字段。
 * 想清这一点，ES 挂了才知道该怎么办 —— 降级走 MySQL 倒排（见 sql/dish.sql 末尾的参考 SQL）。
 *
 * <p><b>索引里只放已发布的菜。</b>草稿是"仅创建者可见"的私有内容，
 * 一旦进了索引，任何人只要构造一个查询就能把别人的草稿搜出来 ——
 * 这属于数据安全问题，不是"少过滤一个字段"那么简单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DishEsSyncService {

    /** 已发布。只有这个状态进索引，见类注释 */
    private static final int STATUS_PUBLISHED = 1;

    /** ES 的 date 类型接受 ISO-8601（不带时区）。存成 "2026-09-15T14:30:00" */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * 幽灵清理时最多扫多少条索引文档。
     *
     * <p>ES 单次 {@code _search} 的 {@code size} 上限是 10000，这里取同一个值。
     * 超过这个量级要把 {@link DishIndexWriter#listAllDishIds(int)} 改成
     * {@code search_after} / PIT 翻页；没超之前，一次取全最简单也最不容易出错。
     */
    private static final int MAX_INDEX_SCAN = 10_000;

    private final DishMapper dishMapper;

    private final DishIngredientMapper dishIngredientMapper;

    private final DishIndexWriter writer;

    /**
     * 把一道菜同步到索引（或从索引里摘掉）。
     *
     * <p>判断依据是**菜品当前的状态**，而不是调用方说它该是什么状态 ——
     * 这样事件重投、并发触发都不会写出不一致的数据。
     */
    public boolean syncOne(Long dishId) {
        Dish dish = dishMapper.selectById(dishId);
        if (dish == null) {
            // 菜被物理删了（正常流程不会，逻辑删除查不到也算这种）——索引里也别留着
            log.info("菜品 {} 在库里查不到，从索引移除", dishId);
            return writer.delete(dishId);
        }

        if (!Objects.equals(dish.getStatus(), STATUS_PUBLISHED)) {
            // 很重要的一条：下架 / 退回草稿时，必须把索引里那份也删掉，
            // 否则它还会被召回出来，用户点进去却提示"菜品不存在"。
            log.info("菜品 {} 状态为 {}（非已发布），从索引移除", dishId, dish.getStatus());
            return writer.delete(dishId);
        }

        return writer.index(buildDoc(dish));
    }

    /**
     * 组装索引文档。
     *
     * <p>字段名必须与 {@code es/dish-index.json} 里的 mapping 完全一致，
     * 类型也要对得上（尤其 {@code ingredientIds} 是 {@code long}，
     * 传了 null 或字符串都会被 ES 拒绝）。
     */
    public DishDoc buildDoc(Dish dish) {
        DishDoc doc = new DishDoc();
        doc.setDishId(dish.getId());
        doc.setName(dish.getName());
        doc.setNameKey(dish.getNameKey());
        doc.setStatus(dish.getStatus());
        // 能查出来就说明没被逻辑删除
        doc.setDeleted(0);
        doc.setSource(dish.getSource());
        doc.setCreatorId(dish.getCreatorId());

        doc.setBaseServing(dish.getBaseServing());
        doc.setDifficulty(dish.getDifficulty());
        doc.setCookTimeMin(dish.getCookTimeMin());

        doc.setRatingAvg(toDouble(dish.getRatingAvg()));
        doc.setRatingWilson(toDouble(dish.getRatingWilson()));
        doc.setRatingCount(dish.getRatingCount());
        doc.setCollectCount(dish.getCollectCount());
        doc.setViewCount(dish.getViewCount());

        doc.setEnergyKcal(toDouble(dish.getEnergyKcal()));
        doc.setPublishTime(formatTime(dish.getPublishTime()));
        doc.setTasteTags(splitTaste(dish.getTasteTag()));

        // ---------- 食材 ----------
        // 召回靠三个数组，分工明确：
        //   · ingredientIds     —— 有主数据 ID 的食材，走 ID 精确匹配
        //   · ingredientNames   —— 全部食材的名字（含表外的），走名字精确匹配
        //   · mainIngredientIds —— 主料，只影响排序权重，不参与召回
        //
        // 为什么 ID 数组里必须剔除 null：表外食材（比如用户手敲的"松露"）
        // ingredient_id 是 NULL，而 mapping 里 ingredientIds 是 long 数组 ——
        // 往 long 字段写 null，ES 会直接报
        // "failed to parse field [ingredientIds] of type [long]"，
        // 结果是**整条文档写不进去**，这道菜从此在召回里消失，日志上还只有一行 warn。
        //
        // 但"ID 剔除"不等于"放弃这道菜"：names 那一路兜住了它 ——
        // 名字照样进索引，别人选/输入「松露」时一样能把它召回出来。
        List<DishIngredient> ingredients = dishIngredientMapper.selectList(
                Wrappers.<DishIngredient>lambdaQuery()
                        .eq(DishIngredient::getDishId, dish.getId()));

        List<Long> allIds = new ArrayList<>();
        List<Long> mainIds = new ArrayList<>();
        List<String> allNames = new ArrayList<>();
        for (DishIngredient ing : ingredients) {
            // 名字无条件收集 —— 包含没有主数据 ID 的自由输入食材，
            // 对这个食材来说，这个名字就是它唯一能被检索到的途径
            String name = ing.getIngredientName() == null ? null : ing.getIngredientName().trim();
            if (name != null && !name.isEmpty() && !allNames.contains(name)) {
                allNames.add(name);
            }

            if (ing.getIngredientId() == null) {
                continue;
            }
            allIds.add(ing.getIngredientId());
            if (Objects.equals(ing.getMainFlag(), 1)) {
                mainIds.add(ing.getIngredientId());
            }
        }

        doc.setIngredientIds(allIds);
        doc.setMainIngredientIds(mainIds);
        doc.setIngredientNames(allNames);
        // 食材种类数按「名字」算：一道「番茄 + 松露」的菜是 2 种食材，
        // 不该因为松露没有主数据 ID 就少算一种
        doc.setIngredientCount(allNames.size());

        return doc;
    }

    /**
     * 全量重建：把库里**所有已发布**的菜重新写一遍索引，并清掉索引里多出来的文档。
     *
     * <p>它是"最终一致"的兜底：增量同步依赖事件，而事件可能因为进程崩溃、
     * ES 短时不可用、或者有人直接改库而漏掉。定时跑一遍全量，这些漏掉的会被自动补齐。
     *
     * <p><b>分两步，顺序不能颠倒：</b>
     * <ol>
     *   <li>先正向 upsert —— 保证库里该有的菜在索引里是最新状态。
     *       先做这步，万一第二步误判，也只会多删不会删错正在用的菜。</li>
     *   <li>再<b>反向清理幽灵文档</b>（见 {@link #pruneGhosts(Set)}）：
     *       索引里有、但库里已经查不到或没发布的那些。
     *       正向写入用的是"重复覆盖"，永远删不掉它们 ——
     *       所以必须显式做一次差集删除。</li>
     * </ol>
     *
     * <p><b>已知的局限（先写在这里，免得以后误以为它很完整）：</b>
     * 数据量大之后要改成分批 + 限流 —— 现在是一次性全查全写，
     * 几千条以内没问题，十万条会打爆内存和 ES 的写队列。
     */
    public ReindexReport reindexAll() {
        long start = System.currentTimeMillis();

        List<Dish> published = dishMapper.selectList(
                Wrappers.<Dish>lambdaQuery().eq(Dish::getStatus, STATUS_PUBLISHED));

        int written = 0;
        int failed = 0;
        for (Dish dish : published) {
            try {
                if (writer.index(buildDoc(dish))) {
                    written++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("重建索引时跳过菜品 {}：{}", dish.getId(), e.getMessage());
            }
        }

        Set<Long> aliveIds = new HashSet<>();
        for (Dish dish : published) {
            aliveIds.add(dish.getId());
        }
        int pruned = pruneGhosts(aliveIds);

        // 写完立刻刷一次，否则验证脚本马上查询会因为 refresh_interval 还没到而查不到
        writer.refresh();

        ReindexReport report = new ReindexReport(
                published.size(), written, failed, pruned, System.currentTimeMillis() - start);
        log.info("ES 全量重建完成：{}", report);
        return report;
    }

    /**
     * 清理幽灵文档：索引里有，但 {@code aliveIds} 里没有的那些。
     *
     * <p><b>为什么要单独做这一步</b>（这是本类最容易被误解的地方）：
     * 写入走的是 {@code PUT /_doc/{雪花ID}} —— 同一个 ID 重复写是<b>覆盖</b>，不是追加，
     * 所以幂等、重复跑也无害。但正因为它是"覆盖型"操作，
     * 索引里那条孤零零的旧文档<b>永远不会被正向写入碰到</b>：
     * 库里都没有它了，自然也不会有人再去同步它。
     *
     * <p>于是它会长期留在索引里，在搜索结果里露脸，
     * 用户点进去却是"菜品不存在" —— 搜索走 ES、详情走 MySQL，两边对不上号。
     * 这种体感非常像 bug，而且查起来很难：
     * 索引看着有数据、接口也没报错，只有详情页知道它不存在。
     *
     * @param aliveIds 库里**当前应该存在**于索引中的菜品 ID（即所有已发布的菜）
     * @return 实际清掉的条数
     */
    private int pruneGhosts(Set<Long> aliveIds) {
        List<Long> indexedIds = writer.listAllDishIds(MAX_INDEX_SCAN);
        if (indexedIds.isEmpty()) {
            // 空列表同时表示"索引是空的"和"读取失败"两种含义，
            // 都按"不清理"处理 —— 宁可漏删，也绝不能因为一次网络抖动误删真实数据
            return 0;
        }

        int pruned = 0;
        int failed = 0;
        for (Long dishId : indexedIds) {
            if (dishId == null || aliveIds.contains(dishId)) {
                continue;
            }
            log.info("发现幽灵文档 dishId={}（ES 里有、库里没有或不满足入库条件），已从索引移除", dishId);
            if (writer.delete(dishId)) {
                pruned++;
            } else {
                failed++;
            }
        }

        if (pruned > 0 || failed > 0) {
            log.info("幽灵文档清理：删除 {} 条，失败 {} 条，索引原有 {} 条",
                    pruned, failed, indexedIds.size());
        }
        return pruned;
    }

    // ---------- 小工具 ----------

    private Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FMT);
    }

    /**
     * 口味标签：库里是逗号分隔的字符串（冗余字段），索引里要的是 keyword 数组。
     * 空串要拆成空列表而不是 ["", ] —— 否则会多出一个空标签参与过滤。
     */
    private List<String> splitTaste(String tasteTag) {
        if (tasteTag == null || tasteTag.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : tasteTag.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 全量重建的结果，用来在日志和接口里说清"到底同步了多少条"。
     *
     * @param pruned 清掉的幽灵文档数，不为 0 说明索引曾经和库对不上，值得看一眼
     */
    public record ReindexReport(int published, int written, int failed, int pruned, long costMs) {
    }
}
