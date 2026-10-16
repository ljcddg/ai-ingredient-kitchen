package com.apesource.dishservice.ai;

import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.es.EsSyncTaskService;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把库里已有的菜品营养重算一遍（说明文档 P2-2 的落地动作）。
 *
 * <p>新生成的菜在 {@code DishDraftWriter} 里就已经按成分表算了；
 * 这个服务是给"以前那批标着 AI 估算的菜"补算用的，同时也产出论文需要的
 * <b>「AI 估算 vs 本地计算」偏差分布</b> —— 这件事只有把整库重算一遍才看得见。
 *
 * <p>只更新营养字段，不改别的；营养变了会让 ES 里的 {@code energyKcal} 过期，
 * 所以每更新一道菜就登记一条索引同步待办（复用 P1-6 的补偿通道，
 * 不需要在这里直接写 ES —— 写失败也不会漏）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NutritionRecomputeService {

    private final DishMapper dishMapper;

    private final DishIngredientMapper dishIngredientMapper;

    private final NutritionCalculator calculator;

    private final EsSyncTaskService esSyncTaskService;

    /**
     * @param scanned          扫描的菜品数
     * @param updated          用本地计算覆盖了营养的菜品数
     * @param skipped          成分表覆盖不足、保持原值的菜品数
     * @param meanDeviationPct 与原热量值偏差的平均百分比（只统计两边都有的）
     * @param maxDeviationPct  最大偏差百分比
     * @param localSourceTotal 重算后全库"本地计算"来源的菜品总数
     * @param queuedSync       登记了索引同步待办的菜品数
     */
    public record Report(int scanned, int updated, int skipped, double meanDeviationPct,
                         double maxDeviationPct, int localSourceTotal, int queuedSync) {
    }

    public Report recomputeAll() {
        List<Dish> dishes = dishMapper.selectList(
                Wrappers.<Dish>lambdaQuery().eq(Dish::getDeleted, 0));
        int updated = 0;
        int skipped = 0;
        int queued = 0;
        int compared = 0;
        double deviationSum = 0;
        double deviationMax = 0;
        List<String> samples = new ArrayList<>();

        // 先把全库的食材用量收齐，再一次性交给 ai-service 算（主数据快照只带一份）
        List<NutritionCalculator.Target> targets = new ArrayList<>(dishes.size());
        for (Dish dish : dishes) {
            List<DishIngredient> rows = dishIngredientMapper.selectList(
                    Wrappers.<DishIngredient>lambdaQuery().eq(DishIngredient::getDishId, dish.getId()));
            List<NutritionCalculator.Item> items = new ArrayList<>();
            for (DishIngredient row : rows) {
                if (row.getIngredientName() == null || row.getIngredientName().isBlank()) {
                    continue;
                }
                items.add(new NutritionCalculator.Item(row.getIngredientName(), row.getAmountGram()));
            }
            targets.add(new NutritionCalculator.Target(String.valueOf(dish.getId()), items,
                    dish.getBaseServing() == null ? 1 : dish.getBaseServing()));
        }
        Map<String, NutritionCalculator.Result> locals = calculator.calculateAll(targets);

        for (Dish dish : dishes) {
            NutritionCalculator.Result local = locals.get(String.valueOf(dish.getId()));
            if (local == null || !local.trustable()) {
                skipped++;
                continue;
            }

            double before = dish.getEnergyKcal() == null ? 0 : dish.getEnergyKcal().doubleValue();
            if (before > 0 && local.calories() > 0) {
                double deviation = Math.abs(before - local.calories()) / local.calories() * 100;
                deviationSum += deviation;
                compared++;
                if (deviation > deviationMax) {
                    deviationMax = deviation;
                }
                if (samples.size() < 5) {
                    samples.add(String.format("%s：AI %.0f → 本地 %.0f kcal（%.0f%%）",
                            dish.getName(), before, local.calories(), deviation));
                }
            }

            int affected = dishMapper.update(null, Wrappers.<Dish>lambdaUpdate()
                    .eq(Dish::getId, dish.getId())
                    .set(Dish::getEnergyKcal, round(local.calories()))
                    .set(Dish::getProteinG, round(local.proteinGram()))
                    .set(Dish::getFatG, round(local.fatGram()))
                    .set(Dish::getCarbG, round(local.carbGram()))
                    .set(Dish::getFiberG, round(local.fiberGram()))
                    .set(Dish::getSodiumMg, round(local.sodiumMg()))
                    .set(Dish::getNutritionSource, DishDraftWriter.SOURCE_LOCAL)
                    .set(Dish::getNutritionVerified, 1));
            if (affected > 0) {
                updated++;
                // ES 里的热量会过期：登记待同步，交给 P1-6 的补偿通道去写
                try {
                    esSyncTaskService.enqueue(dish.getId(), LocalDateTime.now());
                    queued++;
                } catch (Exception e) {
                    log.warn("营养重算后登记索引同步失败 dishId={}：{}", dish.getId(), e.getMessage());
                }
            }
        }

        double mean = compared > 0 ? deviationSum / compared : 0;
        long localTotal = dishMapper.selectCount(Wrappers.<Dish>lambdaQuery()
                .eq(Dish::getNutritionSource, DishDraftWriter.SOURCE_LOCAL));
        log.info("营养重算完成：扫描 {}，更新 {}，跳过 {}，平均偏差 {}%，最大 {}%",
                dishes.size(), updated, skipped, Math.round(mean), Math.round(deviationMax));
        for (String sample : samples) {
            log.info("偏差样本：{}", sample);
        }
        return new Report(dishes.size(), updated, skipped,
                round2(mean), round2(deviationMax), (int) localTotal, queued);
    }

    private BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
