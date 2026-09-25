package com.apesource.dishservice.service;

import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.entity.Ingredient;
import com.apesource.dishservice.mapper.IngredientMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把用户的两种食材输入统一成一份列表。
 *
 * <p>用户能提供食材的方式有两种：从列表里点选（有 ID），以及在输入框里自由敲（只有名字）。
 * 归一的目标是：<b>能对上主数据的就补上 ID，对不上就保留原文</b>。
 *
 * <p>匹配顺序（命中率从高到低）：
 * <ol>
 *   <li>按 ID 直接查（用户点选的）</li>
 *   <li>按标准名精确匹配</li>
 *   <li>按别名匹配（"西红柿" → "番茄"）</li>
 *   <li>都对不上 → 保留原文，ID 为 null</li>
 * </ol>
 *
 * <p>第 4 种不是失败：用户输入"松露"这种没收录的食材，照样能让 AI 用上它生成菜谱，
 * 只是它不参与营养校验、也进不了倒排索引（召回时会漏掉）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngredientResolver {

    /** 自由输入的名字长度上限，挡掉明显的垃圾输入 */
    private static final int MAX_FREE_NAME_LENGTH = 20;

    private final IngredientMapper ingredientMapper;

    /**
     * @param ids       从列表里点选的食材 ID，可为空
     * @param freeNames 自由输入的食材名，可为空
     * @return 去重后的食材列表（保持用户给的顺序）
     */
    public List<IngredientRef> resolve(List<Long> ids, List<String> freeNames) {
        List<IngredientRef> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 主数据一次性全读出来，它同时服务两件事：
        //   ① 校验点选的 ID 是不是真的存在
        //   ② 把自由输入的名字/别名归一到标准名
        // 食材主数据只有几十到几百条，一次全读比按需多次查更简单，也省掉缓存失效的麻烦。
        // 哪天真涨到几万条，再改成"归一化名称列 + 索引"或专门的同义词表。
        List<Ingredient> all = ingredientMapper.selectList(
                Wrappers.<Ingredient>lambdaQuery().eq(Ingredient::getStatus, 1));

        Map<Long, Ingredient> byId = new LinkedHashMap<>();
        Map<String, Ingredient> byName = new LinkedHashMap<>();
        for (Ingredient i : all) {
            byId.put(i.getId(), i);
            byName.putIfAbsent(i.getName().toLowerCase(), i);
            if (i.getAlias() != null && !i.getAlias().isBlank()) {
                for (String a : i.getAlias().split(",")) {
                    String key = a.trim().toLowerCase();
                    if (!key.isEmpty()) {
                        byName.putIfAbsent(key, i);
                    }
                }
            }
        }

        // ---------- ① 从列表点选的 ----------
        if (ids != null) {
            for (Long id : ids) {
                Ingredient master = byId.get(id);
                if (master == null) {
                    log.warn("食材 ID {} 在主数据里不存在，已忽略", id);
                    continue;
                }
                if (seen.add(master.getName())) {
                    result.add(new IngredientRef(master.getId(), master.getName()));
                }
            }
        }

        // ---------- ② 自由输入的 ----------
        if (freeNames != null) {
            for (String raw : freeNames) {
                String name = raw == null ? "" : raw.trim();
                if (name.isEmpty()) {
                    continue;
                }
                if (name.length() > MAX_FREE_NAME_LENGTH) {
                    log.warn("自由输入的食材名过长，已忽略：{}", name.substring(0, MAX_FREE_NAME_LENGTH));
                    continue;
                }

                Ingredient matched = byName.get(name.toLowerCase());

                // 归一成功就用主数据的标准名 —— 用户输入的「西红柿」在菜谱里会显示成
                // 「番茄」，而不是同一道菜里出现两种叫法。
                //
                // 【去重要按归一后的名字，不能按原文】否则「番茄」点选 + 「西红柿」输入
                // 会变成两条指向同一个食材的重复项：AI 看到的就成了"番茄、西红柿"两样东西，
                // 很可能做出一道写着"番茄西红柿炒蛋"的怪菜；营养校验还会把它算两遍。
                String finalName = matched != null ? matched.getName() : name;
                if (!seen.add(finalName)) {
                    continue;
                }
                result.add(new IngredientRef(matched != null ? matched.getId() : null, finalName));

                if (matched == null) {
                    log.info("自由输入的食材「{}」不在主数据里，本次生成照常，但不参与营养校验", name);
                }
            }
        }

        return result;
    }
}
