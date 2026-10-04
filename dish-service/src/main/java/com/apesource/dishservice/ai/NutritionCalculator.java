package com.apesource.dishservice.ai;

import com.apesource.dishservice.entity.Ingredient;
import com.apesource.dishservice.mapper.IngredientMapper;
import com.apesource.dishservice.service.IngredientDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 营养本地计算（说明文档 P2-2）。
 *
 * <p>口径：<b>食材成分表 × 用量 ÷ 人数</b>。
 * 主数据 {@code t_ingredient} 里每样食材都有每 100 克的
 * 热量/蛋白/脂肪/碳水/纤维/钠，乘上这道菜实际用了多少克，就是这一锅的总量，
 * 再按人数均分。
 *
 * <h3>为什么不能无条件相信本地结果</h3>
 * 用户自由输入的食材（主数据里还没有的，比如"香茅"）对不上成分表。
 * 如果只对上 1 样就算总数，会算出严重偏低的值，比 AI 估算还不可信。
 * 所以用<b>克重覆盖率</b>把关：能对上成分表的克重占比低于
 * {@link #MIN_COVERAGE} 就不采用，宁可继续标注"AI 估算"。
 *
 * <h3>已知口径边界（写作时要交代）</h3>
 * <ul>
 *   <li><b>调料不计入</b>：草稿里调料只有名字没有用量，没法算。所以本地值系统性地
 *       略低于真实值（一勺油约 90 千卡），这也是它只用于横向对比、不宣称"实测值"的原因；</li>
 *   <li>主数据是每 100 克参考值，与具体品种、部位有关，本身有误差；</li>
 *   <li>因此菜品里的 {@code nutrition_source=2} 只表示"这份数值是按成分表算出来的"，
 *       不代表实验室测定值。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NutritionCalculator {

    /** 本地计算可用的最低克重覆盖率 */
    public static final double MIN_COVERAGE = 0.8;

    private final IngredientMapper ingredientMapper;

    private final IngredientDirectory directory;

    /** 一样食材：名字 + 克数 */
    public record Item(String name, BigDecimal grams) {
    }

    /**
     * 本地算出来的每人份营养。
     *
     * @param coverage     能对上成分表的克重占比（0~1）
     * @param resolvedItems 对上成分表的食材数
     * @param unresolved   没对上成分表的食材名（会写进日志，也是候选食材的来源）
     */
    public record Result(double calories, double proteinGram, double fatGram, double carbGram,
                         double fiberGram, double sodiumMg, double coverage,
                         int resolvedItems, int totalItems, List<String> unresolved) {

        /** 覆盖率够高才敢用：否则不如老实标成"AI 估算" */
        public boolean trustable() {
            return resolvedItems > 0 && coverage >= MIN_COVERAGE;
        }
    }

    /** 空结果（没有可算的食材） */
    private static final Result EMPTY = new Result(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());

    /**
     * 计算每人份营养。
     *
     * @param items    这道菜用到的食材与克数（调料不必传：没有用量算不了）
     * @param servings 人数（至少按 1 算）
     */
    public Result calculate(List<Item> items, int servings) {
        if (items == null || items.isEmpty()) {
            return EMPTY;
        }
        int people = Math.max(1, servings);
        List<Item> valid = new ArrayList<>();
        for (Item item : items) {
            if (item == null || item.name() == null || item.name().isBlank() || item.grams() == null
                    || item.grams().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            valid.add(item);
        }
        if (valid.isEmpty()) {
            return EMPTY;
        }

        Map<String, Long> idByName = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        for (Item item : valid) {
            Long id = resolve(item.name(), idByName);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }

        Map<Long, Ingredient> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            try {
                for (Ingredient row : ingredientMapper.selectBatchIds(ids)) {
                    byId.put(row.getId(), row);
                }
            } catch (Exception e) {
                // 查不到成分表就当作"全都没对上"，返回不可信结果 —— 绝不用残缺数据凑一个数出来
                log.warn("读取食材成分表失败，本次不做本地营养计算：{}", e.getMessage());
                return new Result(0, 0, 0, 0, 0, 0, 0, 0, valid.size(),
                        valid.stream().map(Item::name).toList());
            }
        }

        double kcal = 0, protein = 0, fat = 0, carb = 0, fiber = 0, sodium = 0;
        double totalGram = 0, resolvedGram = 0;
        int resolved = 0;
        List<String> unresolved = new ArrayList<>();
        for (Item item : valid) {
            double gram = item.grams().doubleValue();
            totalGram += gram;
            Long id = idByName.get(item.name().trim());
            Ingredient row = id == null ? null : byId.get(id);
            if (row == null) {
                unresolved.add(item.name().trim());
                continue;
            }
            resolved++;
            resolvedGram += gram;
            // 每 100 克 → 实际克数 → 每人份
            double factor = gram / 100.0 / people;
            kcal += nz(row.getCalorie()) * factor;
            protein += nz(row.getProtein()) * factor;
            fat += nz(row.getFat()) * factor;
            carb += nz(row.getCarb()) * factor;
            fiber += nz(row.getFiber()) * factor;
            sodium += nz(row.getSodium()) * factor;
        }

        double coverage = totalGram <= 0 ? 0 : resolvedGram / totalGram;
        return new Result(kcal, protein, fat, carb, fiber, sodium, coverage,
                resolved, valid.size(), unresolved);
    }

    /**
     * 名字（含别名）→ 主数据 ID。
     *
     * <p>走 {@link IngredientDirectory} 而不是直接查库：目录本来就维护着
     * "名字 + 别名 → ID"的映射，营养计算与召回用的是同一套归一化口径，
     * 不然会出现"召回认得西红柿、营养算不出来"这种自相矛盾。
     */
    private Long resolve(String name, Map<String, Long> cache) {
        String key = name.trim();
        try {
            Long id = directory.resolveId(key);
            if (id != null) {
                cache.put(key, id);
            }
            return id;
        } catch (Exception e) {
            log.debug("解析食材「{}」失败：{}", key, e.getMessage());
            return null;
        }
    }

    private double nz(BigDecimal value) {
        return value == null ? 0d : value.doubleValue();
    }

    /** 调试用：把结果说成一行 */
    public String describe(Result r) {
        if (r == null) {
            return "无";
        }
        return String.format("每人份 %.0f kcal（覆盖率 %.0f%%，对上 %d/%d 样%s）",
                r.calories(), r.coverage() * 100, r.resolvedItems(), r.totalItems(),
                r.unresolved().isEmpty() ? "" : "，未对上：" + String.join("、", r.unresolved()));
    }
}
