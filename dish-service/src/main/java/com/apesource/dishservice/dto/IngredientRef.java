package com.apesource.dishservice.dto;

/**
 * 用户选用的一个食材。
 *
 * <p>为什么 id 允许为 null：用户可以在输入框里自由输入前端列表之外的食材，
 * 那些食材还没进主数据表，就没有 id。这类食材照样能参与 AI 生成
 * （生成只需要名字），只是没法参与营养校验 —— 这就是 {@code id == null} 的含义。
 *
 * @param id   食材主数据 ID，可能为 null（自由输入且尚未归一的食材）
 * @param name 食材名（自由输入的原文，或主数据里的标准名）
 */
public record IngredientRef(Long id, String name) {
}
