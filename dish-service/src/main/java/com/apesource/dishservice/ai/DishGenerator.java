package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import com.apesource.dishservice.dto.GenerateConstraints;
import com.apesource.dishservice.dto.IngredientRef;

import java.util.List;

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
     * @param constraints 生成约束（必用/禁用食材、时长、设备）；不传就当作无约束
     */
    record GenerateInput(List<IngredientRef> ingredients, List<String> tastes, int servings,
                         GenerateConstraints constraints) {

        /** 约束的安全取值：调用方不必判空 */
        public GenerateConstraints constraintsOrNone() {
            return constraints == null ? GenerateConstraints.none() : constraints;
        }
    }

    /**
     * 生成结果。
     *
     * @param draft             解析 + 校验通过的草稿
     * @param nutritionVerified 是否通过了本地营养校验（与本地算的值偏差 < 容差）
     * @param costMs            本次生成总耗时（含重试）
     * @param attempts          实际尝试次数（1 表示一次成功）
     * @param rawOutput         最后一次的模型原始输出 —— 出问题时能原样复看，而不是只看结论
     * @param validation        最后一次的校验结果（格式/约束分开）—— 生成记录靠它落库
     * @param meta              这次用的"配方"（模型、提示词版本、温度、重试上限）
     */
    record GenerateResult(DishDraft draft, boolean nutritionVerified, long costMs, int attempts,
                          String rawOutput, ValidationResult validation,
                          GenerationMeta meta) {

        /**
         * 生成用的"配方"。
         *
         * <p>为什么必须记下来：换了模型、改了提示词、调了温度之后，
         * 老结果的解释力就完全不同了。不记这些，"上个月准确率 80%"这种结论根本站不住脚 ——
         * 因为没人知道那 80% 是哪一版提示词跑出来的。
         *
         * @param model         模型标识（如 gemma4:e2b）
         * @param promptVersion 系统提示词内容哈希（前 8 位）
         * @param temperature   温度
         * @param maxAttempts   允许的最大尝试次数
         * @param referenceDishIds 本次注入的参考菜谱 ID（P2-1 的参考增强；未启用时为空）
         */
        record GenerationMeta(String model, String promptVersion, double temperature, int maxAttempts,
                              List<Long> referenceDishIds) {
        }
    }
}
