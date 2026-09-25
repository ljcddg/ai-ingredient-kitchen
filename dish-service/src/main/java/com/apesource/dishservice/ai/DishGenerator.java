package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import com.apesource.dishservice.dto.IngredientRef;

import java.util.List;

/**
 * 菜谱生成器 —— 整套 AI 链路里唯一与"用哪个模型"有关的地方。
 *
 * <p>抽象成接口的意义：将来要从 Ollama 换成 DeepSeek（或换回手写 RestClient），
 * 只需要换一个实现类，业务代码（队列、Service）一行都不用动。
 * 这和项目里 {@code JwtUtil} 提到 common、ES 用 RestClient 直连是同一个思路：
 * <b>把易变的部分关进一个接口里</b>。
 */
public interface DishGenerator {

    /**
     * 根据食材生成一道菜的草稿。
     *
     * @return 生成结果（含草稿、营养是否通过校验、耗时、实际尝试次数）
     * @throws DishGenerationException 多次尝试后仍不合格
     */
    GenerateResult generate(GenerateInput input);

    /**
     * 生成输入。
     *
     * @param ingredients 用户选/输入的食材（含自由输入、id 可能为 null 的那些）
     * @param tastes      口味标签，可为空
     * @param servings    人数
     */
    record GenerateInput(List<IngredientRef> ingredients, List<String> tastes, int servings) {
    }

    /**
     * 生成结果。
     *
     * @param draft             解析 + 校验通过的草稿
     * @param nutritionVerified 是否通过了本地营养校验（与本地算的值偏差 < 容差）
     * @param costMs            本次生成总耗时（含重试）
     * @param attempts          实际尝试次数（1 表示一次成功）
     */
    record GenerateResult(DishDraft draft, boolean nutritionVerified, long costMs, int attempts) {
    }
}
