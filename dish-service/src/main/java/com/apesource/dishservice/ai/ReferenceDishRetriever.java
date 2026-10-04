package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishItem;
import com.apesource.dishservice.dto.DishRecallResult;
import com.apesource.dishservice.dto.DishSearchRequest;
import com.apesource.dishservice.dto.DishStep;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.mapper.DishMapper;
import com.apesource.dishservice.service.DishSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 参考菜谱检索（说明文档 P2-1 的"参考菜谱增强生成"）。
 *
 * <p>生成之前先在本库检索几道相似菜谱，把它们的<b>可用片段</b>拼成提示词里的一段，
 * 让模型知道"本地这类菜通常怎么做"，从而少编出库内不成立的做法与耗时。
 *
 * <h3>为什么参考里不放完整菜谱</h3>
 * 只给名称、主料、口味、耗时和<b>前几步</b>的做法要点，不给完整步骤与用量：
 * <ul>
 *   <li>整段塞进去，小模型会把它当答案照抄（生成出来的菜和参考一模一样就没意义了）；</li>
 *   <li>参考是"灵感"而不是"标准答案"，用户的条件与手上食材永远优先。</li>
 * </ul>
 *
 * <h3>失败必须无害</h3>
 * 检索、取步骤任何一步出错都只记 warn 并返回空列表 —— 参考只是锦上添花，
 * ES 挂了不该让"AI 做菜"跟着挂。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceDishRetriever {

    /** 参考条数上限：再多就容易变成"抄参考"，也会挤占提示词预算 */
    private static final int MAX_LIMIT = 5;

    /** 每条参考最多列几样主料 */
    private static final int MAX_MAINS = 6;

    /** 步骤要点截断长度 */
    private static final int MAX_STEP_TEXT = 40;

    private final DishSearchService searchService;

    private final DishMapper dishMapper;

    private final GenerateProperties properties;

    /**
     * 一条参考菜谱的可用片段。
     *
     * @param dishId     参考菜谱 ID —— 会记进生成记录，便于事后追"这次生成参考了谁"
     * @param name       菜名
     * @param mains      这道菜要求的全部主料
     * @param missing    其中用户手上没有的（让模型知道哪些是"人家的料"）
     * @param tasteTags  口味标签
     * @param stepHints  前几步做法要点
     */
    public record Reference(Long dishId, String name, List<String> mains, List<String> missing,
                            List<String> tasteTags, Integer cookTimeMin, List<String> stepHints) {
    }

    /** 检索相似菜谱作为参考；任何异常都返回空列表 */
    public List<Reference> retrieve(DishGenerator.GenerateInput input, int limit) {
        if (input == null || input.ingredients() == null || input.ingredients().isEmpty()) {
            return List.of();
        }
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        try {
            List<Long> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (IngredientRef ref : input.ingredients()) {
                if (ref == null) {
                    continue;
                }
                if (ref.id() != null && ref.id() > 0) {
                    ids.add(ref.id());
                } else if (ref.name() != null && !ref.name().isBlank()) {
                    names.add(ref.name().trim());
                }
            }
            if (ids.isEmpty() && names.isEmpty()) {
                return List.of();
            }

            DishSearchRequest request = new DishSearchRequest();
            request.setIngredientIds(ids);
            request.setFreeIngredients(names);
            // 参考要"相似"而不是"能做"：用户手上只有两样时，严格模式基本检索不到东西，
            // 而参考的价值恰恰在于"这道菜大概长什么样"
            request.setMode("loose");
            request.setMaxMissing(2);
            request.setPage(1);
            request.setSize(size);

            DishRecallResult result = searchService.search(request);
            List<DishItem> items = result == null ? null : result.getCandidates();
            if (items == null || items.isEmpty()) {
                return List.of();
            }

            Map<Long, Dish> withSteps = loadDishes(items);
            List<Reference> references = new ArrayList<>(items.size());
            for (DishItem item : items) {
                references.add(toReference(item, withSteps.get(item.getDishId())));
            }
            return references;
        } catch (Exception e) {
            log.warn("检索参考菜谱失败，本次按无参考生成：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 渲染成提示词里的一段；没有参考时返回空串 ——
     * 空串意味着提示词里连"参考"这两个字都不会出现，两臂（增强/不增强）的差别才干净。
     */
    public String render(List<Reference> references) {
        if (references == null || references.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n\n## 库内相似菜谱（仅供参考，不是答案）\n");
        int index = 1;
        for (Reference r : references) {
            sb.append(index++).append(". ").append(r.name());
            if (r.tasteTags() != null && !r.tasteTags().isEmpty()) {
                sb.append("｜口味：").append(String.join("、", r.tasteTags()));
            }
            if (r.cookTimeMin() != null) {
                sb.append("｜耗时约 ").append(r.cookTimeMin()).append(" 分钟");
            }
            if (r.mains() != null && !r.mains().isEmpty()) {
                sb.append("｜主料：").append(String.join("、", r.mains()));
            }
            if (r.missing() != null && !r.missing().isEmpty()) {
                sb.append("（其中你手上没有：").append(String.join("、", r.missing())).append("）");
            }
            if (r.stepHints() != null && !r.stepHints().isEmpty()) {
                sb.append("｜做法要点：").append(String.join(" / ", r.stepHints()));
            }
            sb.append('\n');
        }
        sb.append("以上只是本地库里的同类菜，用来参考搭配与手法：**必须自己写一道新菜**，"
                + "菜名不得与上面任何一道相同；用户的条件与手上的食材优先于参考。\n");
        return sb.toString();
    }

    private Map<Long, Dish> loadDishes(List<DishItem> items) {
        List<Long> ids = items.stream()
                .map(DishItem::getDishId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            return dishMapper.selectBatchIds(ids).stream()
                    .collect(Collectors.toMap(Dish::getId, dish -> dish, (a, b) -> a));
        } catch (Exception e) {
            // 只是拿不到"做法要点"，参考的主体信息（名称/主料/口味/耗时）还在，照样能用
            log.warn("读取参考菜谱步骤失败，参考里将不含做法要点：{}", e.getMessage());
            return Map.of();
        }
    }

    private Reference toReference(DishItem item, Dish dish) {
        List<String> mains = new ArrayList<>();
        if (item.getMatchedIngredients() != null) {
            mains.addAll(item.getMatchedIngredients());
        }
        if (item.getMissingIngredients() != null) {
            mains.addAll(item.getMissingIngredients());
        }

        List<String> stepHints = List.of();
        if (dish != null && dish.getStepJson() != null) {
            stepHints = dish.getStepJson().stream()
                    .filter(Objects::nonNull)
                    .map(DishStep::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .limit(Math.max(0, properties.getReferenceStepHints()))
                    .map(text -> text.length() <= MAX_STEP_TEXT
                            ? text : text.substring(0, MAX_STEP_TEXT) + "…")
                    .toList();
        }

        return new Reference(item.getDishId(), item.getName(),
                mains.stream().limit(MAX_MAINS).toList(),
                item.getMissingIngredients() == null ? List.of() : item.getMissingIngredients(),
                item.getTasteTags() == null ? List.of() : item.getTasteTags(),
                item.getCookTimeMin(), stepHints);
    }
}
