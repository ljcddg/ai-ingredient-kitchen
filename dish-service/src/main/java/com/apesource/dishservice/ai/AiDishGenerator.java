package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.entity.Ingredient;
import com.apesource.dishservice.mapper.IngredientMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用 Ollama 本地模型生成菜谱。
 *
 * <p>整个流程是「约定 + 约束 + 校验 + 重试」四件事叠起来的：
 * <pre>
 *   系统提示词（说清要什么）
 *        ↓
 *   format=json（约束解码，保证是合法 JSON，不会裹 ```json 代码块）
 *        ↓
 *   反序列化 → 校验器逐字段检查
 *        ↓ 不合格
 *   把"哪里不合格"原样追加进提示词，重试一次
 *        ↓ 仍不合格
 *   抛 DishGenerationException，任务标记失败
 * </pre>
 *
 * <p><b>关于营养</b>：AI 给的营养值会展示给用户（标注"仅供参考"），
 * 同时这里会用「食材主数据 × 用量」本地算一份做对比 ——
 * 偏差过大说明这次生成可疑，把 {@code nutritionVerified} 置 false。
 * 这是用户看不到的那道保险。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiDishGenerator implements DishGenerator {

    private static final String SYSTEM_PROMPT_PATH = "prompts/dish-generate-system.txt";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 本地校验时给"调料"补的一份热量（kcal）。
     *
     * <p>为什么要有这个补偿：按方案 A，调料不进 {@code ingredients}，
     * 所以我们本地算出来的热量天然缺了一块 —— 而一次炒菜的食用油约 15g，
     * 就是 135 kcal，对一道 300 kcal 的家常菜来说占比不小。
     * 不补的话，本地值会系统性偏低，校验会一直报"偏差过大"，这道保险就废了。
     *
     * <p>数字本身是粗估，不要当成精确值 —— 它只是为了让"对比"这件事有意义。
     */
    private static final BigDecimal SEASONING_KCAL_COMPENSATION = BigDecimal.valueOf(120);

    private final OllamaChatModel chatModel;
    private final DishDraftValidator validator;
    private final GenerateProperties properties;
    private final ObjectMapper objectMapper;
    private final IngredientMapper ingredientMapper;

    /** 与 {@code spring.ai.ollama.chat.options.model} 是同一个 key，不额外引入配置 */
    @Value("${spring.ai.ollama.chat.options.model:gemma4:e2b}")
    private String model;

    /** 系统提示词是静态内容，启动时读一次缓存，不必每次请求都碰磁盘 */
    private String systemPrompt;

    @PostConstruct
    void loadSystemPrompt() {
        try (InputStream in = new ClassPathResource(SYSTEM_PROMPT_PATH).getInputStream()) {
            this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.info("菜谱生成提示词已加载：{} 字符", systemPrompt.length());
        } catch (IOException e) {
            throw new IllegalStateException("读不到系统提示词：" + SYSTEM_PROMPT_PATH, e);
        }
    }

    @Override
    public GenerateResult generate(GenerateInput input) {
        long start = System.currentTimeMillis();
        String userPrompt = buildUserPrompt(input);
        List<String> requiredNames = input.ingredients().stream()
                .map(IngredientRef::name)
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .toList();

        List<String> lastProblems = List.of();
        int attempts = Math.max(1, properties.getMaxAttempts());

        for (int attempt = 1; attempt <= attempts; attempt++) {
            String json;
            try {
                json = callModel(userPrompt, lastProblems);
            } catch (Exception e) {
                // 调不通（Ollama 没起、模型没拉）是另一类问题，直接抛出去，
                // 让 Service 把原因写进 error_msg —— 重试也不会变好。
                throw new DishGenerationException("调用模型失败：" + brief(e.getMessage()), e);
            }

            DishDraft draft;
            try {
                draft = objectMapper.readValue(json, DishDraft.class);
            } catch (Exception e) {
                lastProblems = List.of("输出的 JSON 无法解析：" + brief(e.getMessage()));
                log.warn("第 {}/{} 次：JSON 解析失败 - {}", attempt, attempts, brief(e.getMessage()));
                continue;
            }

            List<String> problems = validator.validate(draft, requiredNames);
            if (problems.isEmpty()) {
                boolean nutritionVerified = verifyNutrition(draft, input.servings());
                long cost = System.currentTimeMillis() - start;
                log.info("第 {}/{} 次生成通过校验：name={}，耗时 {} ms，营养校验={}",
                        attempt, attempts, draft.getName(), cost, nutritionVerified);
                return new GenerateResult(draft, nutritionVerified, cost, attempt);
            }

            lastProblems = problems;
            log.warn("第 {}/{} 次生成未通过校验（{} 项）：{}",
                    attempt, attempts, problems.size(), String.join("；", problems));
        }

        // 详细原因留给日志：字段级的说明（prepTimeMin=0 不在 2~60 之间）是给重试提示词
        // 和开发者看的，直接甩给用户等于没说 —— 它以前就是被原样存进 error_msg 的
        log.warn("生成失败：{} 次尝试都没通过校验，最后一次的问题：{}",
                attempts, String.join("；", lastProblems));
        throw new DishGenerationException("AI 没能用「" + describe(requiredNames)
                + "」想出合适的做法，换个食材组合或口味再试试");
    }

    /** 把食材列表说成一句人话；太长了就只提前两个，免得报错信息一屏都是菜名 */
    private String describe(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "这组食材";
        }
        if (names.size() <= 3) {
            return String.join("、", names);
        }
        return names.get(0) + "、" + names.get(1) + " 等 " + names.size() + " 种食材";
    }

    // ------------------------------------------------------------------ 调模型

    private String callModel(String userPrompt, List<String> previousProblems) {
        String userText = userPrompt;
        if (!previousProblems.isEmpty()) {
            // 把上次的问题原样告诉它。实测小模型对"具体哪里错了"是有反应的，
            // 但对"格式错误"这种笼统说法没反应 —— 所以校验器的报错必须具体。
            StringBuilder sb = new StringBuilder(userPrompt);
            sb.append("\n\n上一次的输出存在下列问题，请全部修正后重新输出：");
            for (String p : previousProblems) {
                sb.append("\n- ").append(p);
            }
            userText = sb.toString();
        }

        OllamaOptions options = new OllamaOptions();
        options.setModel(model);
        // 约束解码：不设的话输出会被 ```json 代码块包住，解析直接失败
        options.setFormat("json");
        options.setTemperature(properties.getTemperature());

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userText)), options);

        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText().trim();
    }

    /** 拼用户消息。格式与 {@code dish-service/verify-prompt.py} 里实测过的保持一致 */
    private String buildUserPrompt(GenerateInput input) {
        String names = input.ingredients().stream()
                .map(IngredientRef::name)
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .collect(Collectors.joining("、"));
        String taste = (input.tastes() == null || input.tastes().isEmpty())
                ? "不限" : String.join("、", input.tastes());
        return "食材：" + names + "；口味：" + taste + "；人数：" + input.servings() + " 人";
    }

    // ------------------------------------------------------------------ 营养兜底校验

    /**
     * 用「食材主数据 × 用量」本地算一份，与 AI 的值对比。
     *
     * @return true 表示偏差在容差内（或本地根本没数据可算时返回 false，表示"没法确认"）
     */
    private boolean verifyNutrition(DishDraft draft, int servings) {
        try {
            DishDraft.NutritionDraft nutrition = draft.getNutritionPerServing();
            if (nutrition == null || nutrition.getCalories() == null || nutrition.getCalories() <= 0) {
                return false;
            }
            BigDecimal localPerServing = estimateCaloriesPerServing(draft, servings);
            if (localPerServing == null || localPerServing.compareTo(BigDecimal.ZERO) <= 0) {
                // 有食材不在主数据里（用户自由输入的那些），算不出来 —— 不是错，只是没法确认
                return false;
            }
            double diff = Math.abs(nutrition.getCalories() - localPerServing.doubleValue())
                    / localPerServing.doubleValue();
            boolean ok = diff <= properties.getNutritionTolerance();
            if (!ok) {
                log.warn("营养校验偏差过大：AI={} kcal，本地估算={} kcal，偏差 {}%（容差 {}%）",
                        nutrition.getCalories(), localPerServing.setScale(0, RoundingMode.HALF_UP),
                        Math.round(diff * 100), Math.round(properties.getNutritionTolerance() * 100));
            }
            return ok;
        } catch (Exception e) {
            log.warn("营养校验执行失败（不影响生成结果本身）：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 本地估算每人份热量。
     *
     * @return null 表示有食材不在主数据里，估算不可信
     */
    private BigDecimal estimateCaloriesPerServing(DishDraft draft, int servings) {
        List<DishDraft.DraftIngredient> items = draft.getIngredients();
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<String> names = items.stream()
                .map(DishDraft.DraftIngredient::getName)
                .filter(n -> n != null && !n.isBlank())
                .toList();
        if (names.isEmpty()) {
            return null;
        }

        List<Ingredient> rows = ingredientMapper.selectList(
                Wrappers.<Ingredient>lambdaQuery().in(Ingredient::getName, names));
        Map<String, Ingredient> byName = new HashMap<>();
        for (Ingredient row : rows) {
            byName.put(row.getName(), row);
        }

        BigDecimal total = BigDecimal.ZERO;
        for (DishDraft.DraftIngredient item : items) {
            if (item == null || item.getName() == null || item.getAmountGram() == null) {
                continue;
            }
            Ingredient master = byName.get(item.getName().trim());
            // calorie <= 0 也要放弃：自动收录的新食材（见 IngredientCandidateService）
            // 营养字段留的是 0，那表示"还不知道"而不是"零热量"——
            // 拿它参与计算会把整道菜的热量压低，营养校验必然报"偏差过大"，冤枉正常结果
            if (master == null || master.getCalorie() == null || master.getCalorie().signum() <= 0) {
                // 只要有一味食材查不到，整份估算就不可信，直接放弃
                return null;
            }
            BigDecimal gram = BigDecimal.valueOf(item.getAmountGram());
            total = total.add(master.getCalorie().multiply(gram).divide(HUNDRED, 4, RoundingMode.HALF_UP));
        }

        int people = Math.max(1, servings);
        BigDecimal perServing = total.divide(BigDecimal.valueOf(people), 4, RoundingMode.HALF_UP);
        return perServing.add(SEASONING_KCAL_COMPENSATION);
    }

    private String brief(String text) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
    }

    /** 仅供调试：暴露提示词内容，方便确认打包后资源确实在 */
    List<String> promptLines() {
        return new ArrayList<>(List.of(systemPrompt.split("\n")));
    }
}
