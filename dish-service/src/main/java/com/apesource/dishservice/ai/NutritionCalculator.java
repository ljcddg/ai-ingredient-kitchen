package com.apesource.dishservice.ai;

import com.apesource.dishservice.service.IngredientDirectory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 营养计算客户端 —— 调 Python ai-service 的 {@code /api/v1/nutrition}。
 *
 * <p>计算口径全部在 Python 侧（{@code nutrition.py}）：<b>食材成分表 × 用量 ÷ 人数</b>，
 * 按克重覆盖率把关，未对上的食材列出来。与菜谱生成的营养校验共用同一份实现，
 * 不会出现两边算出不同结果的情况。这里只负责组装主数据快照和用量、发请求、把结果映射回 {@link Result}。
 *
 * <h3>为什么不能无条件相信本地结果</h3>
 * 用户自由输入的食材（主数据里还没有的，比如"香茅"）对不上成分表。
 * 如果只对上 1 样就算总数，会算出严重偏低的值。所以只有覆盖率够高的结果才
 * {@link Result#trustable()}，否则调用方继续标注"AI 估算"。
 *
 * <h3>失败降级</h3>
 * ai-service 不可用时返回不可信结果，<b>绝不用残缺数据凑一个数出来</b>。
 * 调用方会退回 AI 估算并如实标注来源 —— 与"成分表查不到就不采用本地值"同一条原则。
 */
@Slf4j
@Component
public class NutritionCalculator {

    /** 本地计算可用的最低克重覆盖率（与 Python 侧 {@code nutrition.MIN_COVERAGE} 保持一致） */
    public static final double MIN_COVERAGE = 0.8;

    private final RestClient aiServiceClient;

    private final IngredientDirectory directory;

    public NutritionCalculator(RestClient aiServiceClient, IngredientDirectory directory) {
        this.aiServiceClient = aiServiceClient;
        this.directory = directory;
    }

    /** 一样食材：名字 + 克数 */
    public record Item(String name, BigDecimal grams) {
    }

    /**
     * 批量计算的一个目标。
     *
     * @param key      调用方自己的标识（如菜品 ID 字符串），用来把结果对回去
     * @param items    这道菜用到的食材与克数
     * @param servings 人数
     */
    public record Target(String key, List<Item> items, int servings) {
    }

    /**
     * 本地算出来的每人份营养。
     *
     * @param coverage      能对上成分表的克重占比（0~1）
     * @param resolvedItems 对上成分表的食材数
     * @param unresolved    没对上成分表的食材名（会写进日志，也是候选食材的来源）
     */
    public record Result(double calories, double proteinGram, double fatGram, double carbGram,
                         double fiberGram, double sodiumMg, double coverage,
                         int resolvedItems, int totalItems, List<String> unresolved) {

        /** 覆盖率够高才敢用：否则不如老实标成"AI 估算" */
        public boolean trustable() {
            return resolvedItems > 0 && coverage >= MIN_COVERAGE;
        }
    }

    /**
     * 计算每人份营养（单道菜）。
     *
     * @param items    这道菜用到的食材与克数（调料不必传：没有用量算不了）
     * @param servings 人数（至少按 1 算）
     */
    public Result calculate(List<Item> items, int servings) {
        if (items == null || items.isEmpty()) {
            return unavailable(items);
        }
        return calculateAll(List.of(new Target("single", items, servings))).get("single");
    }

    /**
     * 批量计算（全库重算用），一次请求完成，主数据快照只带一份。
     *
     * @return key → 结果；请求失败时每个目标都是不可信结果
     */
    public Map<String, Result> calculateAll(List<Target> targets) {
        Map<String, Result> out = new HashMap<>();
        if (targets == null || targets.isEmpty()) {
            return out;
        }

        List<Map<String, Object>> items = new ArrayList<>(targets.size());
        for (Target target : targets) {
            items.add(Map.of(
                    "dishId", target.key(),
                    "servings", Math.max(1, target.servings()),
                    "ingredients", toIngredientBodies(target.items())));
        }

        try {
            BatchResponse response = aiServiceClient.post()
                    .uri("/api/v1/nutrition")
                    .body(Map.of("directory", directory.snapshot(), "items", items))
                    .retrieve()
                    .body(BatchResponse.class);
            if (response == null || response.results() == null) {
                throw new IllegalStateException("响应里没有 results");
            }
            for (Row row : response.results()) {
                out.put(row.dishId(), row.toResult());
            }
        } catch (Exception e) {
            // ai-service 挂了或响应异常：全部按"没对上"处理，调用方会退回 AI 估算
            log.warn("营养计算服务不可用，本次 {} 道菜不采用本地营养值：{}", targets.size(), e.getMessage());
            for (Target target : targets) {
                out.put(target.key(), unavailable(target.items()));
            }
        }

        // 服务端没返回的目标（理论上不会有）同样按不可信处理
        for (Target target : targets) {
            out.computeIfAbsent(target.key(), k -> unavailable(target.items()));
        }
        return out;
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

    private List<Map<String, Object>> toIngredientBodies(List<Item> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (Item item : items) {
            if (item == null || item.name() == null) {
                continue;
            }
            Map<String, Object> body = new HashMap<>();
            body.put("name", item.name());
            body.put("amountGram", item.grams() == null ? null : item.grams().doubleValue());
            out.add(body);
        }
        return out;
    }

    private Result unavailable(List<Item> items) {
        int total = items == null ? 0 : items.size();
        List<String> names = items == null ? List.of()
                : items.stream().map(Item::name).toList();
        return new Result(0, 0, 0, 0, 0, 0, 0, 0, total, names);
    }

    /** ai-service 的 {@code /api/v1/nutrition} 响应，字段名与 routes.py 一字不差 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatchResponse(boolean ok, List<Row> results) {
    }

    /** 单道菜的结果。{@code trustable} 不映射：由 {@link Result#trustable()} 统一判定，口径只有一份 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(String dishId, double calories, double proteinGram, double fatGram,
               double carbGram, double fiberGram, double sodiumMg, double coverage,
               int resolvedItems, int totalItems, List<String> unresolved) {

        Result toResult() {
            return new Result(calories, proteinGram, fatGram, carbGram, fiberGram, sodiumMg,
                    coverage, resolvedItems, totalItems,
                    unresolved == null ? List.of() : unresolved);
        }
    }
}
