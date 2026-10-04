package com.apesource.dishservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「手写菜谱」的提交内容（前端食材页里的页内面板，不是新页面）。
 *
 * <p>和 AI 生成的分工正好反过来：AI 那边是模型写内容、系统只做校验；
 * 这里<b>内容全是用户写的，系统只负责算</b> —— 按主数据成分表算出每人份营养、
 * 把食材名字归一化到主数据 ID（这样这道菜能被搜到、能被算营养）。
 *
 * <p>字段都能缺省：{@code preview} 接口允许用户边填边试算，
 * 所以这里不做参数校验注解，真正的把关在
 * {@code ManualDishWriter.save()} 里（一次把问题说全，而不是让用户改一条报一条）。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 是防呆：
 * 前端多传一个字段不该让整次上传失败 —— 这个坑在 {@code GenerateConstraints} 上踩过，
 * 当时 record 里的 {@code isBlank()} 被 Jackson 当成 getter，序列化时多写一个字段，
 * 回读直接抛 {@code UnrecognizedPropertyException}，表现是"提交能存、一执行就失败"。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManualDishRequest(String name,
                               Integer servings,
                               Integer cookTimeMin,
                               Integer prepTimeMin,
                               Integer difficulty,
                               List<String> tasteTags,
                               String summary,
                               List<String> seasonings,
                               List<String> equipment,
                               String chefTips,
                               List<Item> ingredients,
                               List<String> steps) {

    /** 一样食材：从列表选的带 {@code ingredientId}，自由输入的是 null */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String name, Long ingredientId, BigDecimal grams) {
    }

    /**
     * 人数。
     *
     * <p>按 1 兜底而不是报错：用户可能是先填食材后填人数，
     * 试算期间人数为空不该让他看到一片红。
     */
    public int servingsOrDefault() {
        return servings == null || servings < 1 ? 1 : Math.min(servings, 20);
    }
}
