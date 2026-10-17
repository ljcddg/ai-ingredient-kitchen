package com.apesource.dishservice.service;

import com.apesource.dishservice.entity.IngredientSubstitute;
import com.apesource.dishservice.mapper.IngredientSubstituteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 食材替代建议。
 *
 * <p>回答的是用户看到"还缺 2 样主料"之后的下一个问题：
 * <b>能不能用手边别的东西顶一下？</b> 这比"再去买"友好得多，
 * 也是食材替代表存在的唯一理由。
 *
 * <p><b>为什么替代建议不参与排序</b>：它只在结果展示时出现。
 * 把"能替代"算进得分会诱导系统推荐"其实做不了、但可以用别的顶"的菜，
 * 那正好抵消了满足率这套指标的意义。
 *
 * <p>查不到就不返回 —— 不猜、不编。宁可让用户看到"缺豆腐"，
 * 也不要瞎给一个"豆腐可用松露代替"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngredientSubstituteService {

    /** 每样食材最多给几条建议（多了用户反而不看） */
    private static final int MAX_PER_INGREDIENT = 3;

    private final IngredientSubstituteMapper mapper;

    private final IngredientDirectory directory;

    /**
     * 批量查替代建议。
     *
     * @param missingNames 缺的食材名（可以含表外食材，解析不到主数据就跳过）
     * @return 原食材名 → 可替代食材名列表；没有建议的食材不会出现在 Map 里
     */
    public Map<String, List<String>> suggest(Collection<String> missingNames) {
        if (missingNames == null || missingNames.isEmpty()) {
            return Map.of();
        }

        // 名字 → 主数据 ID（表外食材没有 ID，自然查不到替代）
        Map<String, Long> idByName = new LinkedHashMap<>();
        for (String name : new LinkedHashSet<>(missingNames)) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Long id = directory.resolveId(name.trim());
            if (id != null && id > 0) {
                idByName.put(name.trim(), id);
            }
        }
        if (idByName.isEmpty()) {
            return Map.of();
        }

        List<IngredientSubstitute> rows;
        try {
            rows = mapper.selectList(new LambdaQueryWrapper<IngredientSubstitute>()
                    .in(IngredientSubstitute::getIngredientId, idByName.values()));
        } catch (Exception e) {
            // 统计表还没建/没跑脚本时，检索也该照常返回
            log.debug("读取食材替代失败（不影响检索）：{}", e.getMessage());
            return Map.of();
        }

        // 按原食材分组，取分数最高的前几条
        Map<Long, List<IngredientSubstitute>> bySource = new LinkedHashMap<>();
        for (IngredientSubstitute row : rows) {
            if (row.getIngredientId() == null || row.getSubstituteId() == null) {
                continue;
            }
            bySource.computeIfAbsent(row.getIngredientId(), k -> new ArrayList<>()).add(row);
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : idByName.entrySet()) {
            List<IngredientSubstitute> candidates = bySource.get(entry.getValue());
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            List<String> names = candidates.stream()
                    .sorted(Comparator.comparing(
                            (IngredientSubstitute s) -> s.getScore() == null ? 0d : s.getScore().doubleValue())
                            .reversed())
                    .limit(MAX_PER_INGREDIENT)
                    .map(s -> directory.nameOf(s.getSubstituteId()))
                    .filter(Objects::nonNull)
                    .toList();
            if (!names.isEmpty()) {
                result.put(entry.getKey(), names);
            }
        }
        return result;
    }

    /** 只查一样，给详情页用 */
    public List<String> suggestOne(String name) {
        return suggest(Set.of(name)).getOrDefault(name, List.of());
    }
}
