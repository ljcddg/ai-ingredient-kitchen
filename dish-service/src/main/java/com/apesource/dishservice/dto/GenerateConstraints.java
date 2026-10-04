package com.apesource.dishservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 生成约束：必用食材、禁用食材、最长耗时、可用设备。
 *
 * <p>它和检索侧（{@code DishSearchRequest}）用的是<b>同一套语义</b>，
 * 只是作用点不同：检索侧用来过滤已有菜品，这里用来约束 AI 生成的内容。
 * 说明文档第三节要求这两条链路"同步"，就是同步到这里。
 *
 * <p><b>为什么单独一个 record 而不是六个参数</b>：
 * 它要被存进任务快照（{@code t_ai_generate_task.request_json}）、算进请求签名、
 * 传给校验器，包成一个值对象才不会漏传 —— 漏传的表现是"忌口没生效"，
 * 而这种 bug 在运行时完全看不出来。
 *
 * <p>字段用包装类型/可空表达"没提这个约束"，而不是用空字符串或 0：
 * 「不限时长」和「最多 0 分钟」是两件完全不同的事。
 *
 * @param mustUseIngredientIds  必用食材（主数据 ID）
 * @param mustUseIngredients    必用食材（名字，含表外食材）
 * @param excludeIngredientIds  禁用食材（主数据 ID）—— 过敏/忌口，绝不能出现
 * @param excludeIngredients    禁用食材（名字）
 * @param maxCookTimeMin        最长总耗时（备菜 + 下锅，分钟）；null 表示不限
 * @param availableEquipment    用户手上有的厨具；为空表示不限制
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateConstraints(List<Long> mustUseIngredientIds,
                                  List<String> mustUseIngredients,
                                  List<Long> excludeIngredientIds,
                                  List<String> excludeIngredients,
                                  Integer maxCookTimeMin,
                                  List<String> availableEquipment) {

    /** 无约束。用它而不是 null，调用方少一层判空 */
    public static GenerateConstraints none() {
        // 创建一个没有任何约束的 GenerateConstraints 实例
        return new GenerateConstraints(List.of(), List.of(), List.of(), List.of(), null, List.of());
    }

    /**
     * 所有约束项都为空 —— 用来决定要不要在提示词里加"用户约束"那一段。
     *
     * <p><b>方法名刻意不带 {@code get}/{@code is} 前缀</b>：Jackson 会把
     * {@code isBlank()} 当成一个名为 {@code blank} 的属性写进 JSON，
     * 而读回来时又不认识它，直接抛 {@code UnrecognizedPropertyException} ——
     * 任务快照就是这样被写坏又读不回来的（写进去能存，任务一执行就失败）。
     * 这个坑是被 {@code DishGeneratePayloadTest} 的往返用例抓出来的。
     */
    public boolean noConstraint() {
        // 检查所有约束项是否都为空
        return mustUseIdSet().isEmpty() && mustUseNames().isEmpty()
                && excludeIdSet().isEmpty() && excludeNames().isEmpty()
                && maxCookTimeMin == null && equipmentNames().isEmpty();
    }

    // 获取必用食材ID集合
    public Set<Long> mustUseIdSet() {
        return idSet(mustUseIngredientIds);
    }

    // 获取必用食材名称列表
    public List<String> mustUseNames() {
        return cleanNames(mustUseIngredients);
    }

    // 获取禁用食材ID集合
    public Set<Long> excludeIdSet() {
        return idSet(excludeIngredientIds);
    }

    // 获取禁用食材名称集合
    public Set<String> excludeNames() {
        return new LinkedHashSet<>(cleanNames(excludeIngredients));
    }

    // 获取可用厨具名称集合
    public Set<String> equipmentNames() {
        return new LinkedHashSet<>(cleanNames(availableEquipment));
    }

    // 将食材ID列表转换为集合，过滤掉null值
    private static Set<Long> idSet(List<Long> raw) {
        Set<Long> set = new LinkedHashSet<>();
        if (raw != null) {
            raw.stream().filter(Objects::nonNull).forEach(set::add);
        }
        return set;
    }

    // 清理食材名称列表，去除null、空字符串和空白字符，并去重
    private static List<String> cleanNames(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
