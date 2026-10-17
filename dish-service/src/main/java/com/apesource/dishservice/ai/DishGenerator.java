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
         * @param knowledgeRuleIds 本次注入的烹饪常识条目 ID（③ 知识库；未命中时为空）
         * @param grounding     依据校验结果；null 表示判不了（没参考 / 参考没用量），**不是 0 分**
         */
        record GenerationMeta(String model, String promptVersion, double temperature, int maxAttempts,
                              List<Long> referenceDishIds, List<String> knowledgeRuleIds,
                              GroundingStats grounding) {

            /** 兼容旧调用点：不带知识库、不做依据校验 */
            GenerationMeta(String model, String promptVersion, double temperature, int maxAttempts,
                           List<Long> referenceDishIds) {
                this(model, promptVersion, temperature, maxAttempts, referenceDishIds, List.of(), null);
            }
        }

        /**
         * 依据校验（grounding）的汇总，口径对齐 RAGAS 的三项指标。
         *
         * <p>三类分开记，因为它们的处置方式完全不同：
         * <ul>
         *   <li>GROUNDED：用量落在库内参考的 0.5~2 倍区间 —— 有据</li>
         *   <li>DEVIATED：找到了参照但用量差得远 —— 可能是合理的（比如做汤），也可能是瞎写</li>
         *   <li>UNGROUNDED：库里根本没有这道菜用到它 —— 新菜的正常代价，但占比过高就说明
         *       生成在脱离检索结果自由发挥，RAG 等于没生效</li>
         * </ul>
         *
         * <p><b>三项指标各自度量链路的不同环节，缺一项就看不出问题出在哪：</b>
         * faithfulness 度量"生成有没有依据"，answerRelevance 度量"回答有没有答所问"，
         * ctxPrecision / ctxRecall 度量"检索准不准、全不全"。只有 faithfulness 时，
         * 生成质量差和检索质量差是分不开的。
         *
         * @param faithfulness   有据比例 = grounded / 总数，0~1（RAGAS: answer faithfulness）
         * @param answerRelevance 答案相关性 0~1（见 ai-service grounding.answer_relevance）
         * @param ctxPrecision   上下文精确率 0~1（参考主料里"用户手上真有"的比例）
         * @param ctxRecall      上下文召回率 0~1（用户食材被参考覆盖的比例）
         */
        record GroundingStats(int grounded, int deviated, int ungrounded, double faithfulness,
                              Double answerRelevance, Double ctxPrecision, Double ctxRecall) {

            /** 兼容旧调用点：只有 faithfulness 的那一版 */
            GroundingStats(int grounded, int deviated, int ungrounded, double faithfulness) {
                this(grounded, deviated, ungrounded, faithfulness, null, null, null);
            }

            int total() {
                return grounded + deviated + ungrounded;
            }
        }
    }
}
