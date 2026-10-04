package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import com.apesource.dishservice.dto.GenerateConstraints;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.entity.Ingredient;
import com.apesource.dishservice.mapper.IngredientMapper;
import com.apesource.dishservice.service.IngredientDirectory;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** P2-1：生成前检索库内相似菜谱作为参考 */
    private final ReferenceDishRetriever referenceRetriever;

    /**
     * 模型调用的执行线程池 —— 只用来做超时控制。
     *
     * <p>用 cached 池而不是固定大小：调用本身已经是串行的（队列并发就是边界），
     * 这里的线程数上限等于"同时在跑的调用数"，超时后那些还没返回的调用会各自占一个线程，
     * 有上限反而会把新调用排在自己身后，超时也就失去意义了。
     */
    private final ExecutorService modelCallExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "ai-model-call");
        thread.setDaemon(true);
        return thread;
    });

    /** 只用来把约束里的主数据 ID 翻成人能读的名字（拼提示词用） */
    private final IngredientDirectory directory;

    /** 与 {@code spring.ai.ollama.chat.options.model} 是同一个 key，不额外引入配置 */
    @Value("${spring.ai.ollama.chat.options.model:gemma4:e2b}")
    private String model;

    /** 系统提示词是静态内容，启动时读一次缓存，不必每次请求都碰磁盘 */
    private String systemPrompt;

    /**
     * 提示词版本（内容 MD5 前 8 位）。
     *
     * <p>每次生成都会把它记进生成记录：改了提示词之后，老结果属于哪一版必须查得出来，
     * 否则"效果变好了"这种结论会连着两版提示词混在一起，说不清是谁的功劳。
     */
    private String promptVersion;

    @PostConstruct
    void loadSystemPrompt() {
        try (InputStream in = new ClassPathResource(SYSTEM_PROMPT_PATH).getInputStream()) {
            this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            this.promptVersion = md5Prefix(systemPrompt);
            log.info("菜谱生成提示词已加载：{} 字符，版本 {}（改提示词即换版本，便于区分实验批次）",
                    systemPrompt.length(), promptVersion);
        } catch (IOException e) {
            throw new IllegalStateException("读不到系统提示词：" + SYSTEM_PROMPT_PATH, e);
        }
    }

    @Override
    public GenerateResult generate(GenerateInput input) {
        long start = System.currentTimeMillis();

        // P2-1 参考菜谱增强：先在本库检索几道相似菜谱，把可用片段拼进提示词。
        // 检索失败会返回空列表（见 ReferenceDishRetriever），所以这里不需要额外的兜底。
        List<ReferenceDishRetriever.Reference> references = properties.isEnhanceEnabled()
                ? referenceRetriever.retrieve(input, properties.getReferenceLimit())
                : List.of();
        List<Long> referenceIds = references.stream()
                .map(ReferenceDishRetriever.Reference::dishId)
                .filter(Objects::nonNull)
                .toList();
        if (!references.isEmpty()) {
            log.info("本次生成注入 {} 条参考菜谱：{}", references.size(),
                    references.stream().map(ReferenceDishRetriever.Reference::name).toList());
        }

        String userPrompt = buildUserPrompt(input) + referenceRetriever.render(references);
        List<String> requiredNames = input.ingredients().stream()
                .map(IngredientRef::name)
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .toList();

        List<String> lastProblems = List.of();
        String lastRawOutput = null;
        DishDraftValidator.ValidationResult lastValidation = null;
        int attempts = Math.max(1, properties.getMaxAttempts());

        for (int attempt = 1; attempt <= attempts; attempt++) {
            String json;
            try {
                json = callModel(userPrompt, lastProblems);
            } catch (DishGenerationException e) {
                // 超时/中断这类已经是"能给用户看的结论"了，原样保留消息，
                // 只把记录需要的上下文补上（试了几次、上次输出是什么）
                throw new DishGenerationException(e.getMessage(), e, attempt - 1,
                        lastRawOutput, lastValidation, meta(referenceIds));
            } catch (Exception e) {
                // 调不通（Ollama 没起、模型没拉）是另一类问题，直接抛出去，
                // 让 Service 把原因写进 error_msg —— 重试也不会变好。
                throw new DishGenerationException("调用模型失败：" + brief(e.getMessage()), e,
                        attempt - 1, lastRawOutput, lastValidation, meta(referenceIds));
            }
            lastRawOutput = json;

            DishDraft draft;
            try {
                draft = objectMapper.readValue(json, DishDraft.class);
            } catch (Exception e) {
                lastProblems = List.of("输出的 JSON 无法解析：" + brief(e.getMessage()));
                // 解析不了就是纯粹的格式问题，约束根本没机会被判
                lastValidation = new DishDraftValidator.ValidationResult(lastProblems, List.of());
                log.warn("第 {}/{} 次：JSON 解析失败 - {}", attempt, attempts, brief(e.getMessage()));
                continue;
            }

            DishDraftValidator.ValidationResult validation = validator.validateDetailed(draft, input);
            if (validation.passed()) {
                boolean nutritionVerified = verifyNutrition(draft, input.servings());
                long cost = System.currentTimeMillis() - start;
                log.info("第 {}/{} 次生成通过校验：name={}，耗时 {} ms，营养校验={}",
                        attempt, attempts, draft.getName(), cost, nutritionVerified);
                return new GenerateResult(draft, nutritionVerified, cost, attempt,
                        json, validation, meta(referenceIds));
            }

            lastProblems = validation.problems();
            lastValidation = validation;
            log.warn("第 {}/{} 次生成未通过校验（格式问题 {} 项 / 约束问题 {} 项）：{}",
                    attempt, attempts,
                    validation.problems().size() - validation.constraintProblems().size(),
                    validation.constraintProblems().size(),
                    String.join("；", validation.problems()));
        }

        // 详细原因留给日志：字段级的说明（prepTimeMin=0 不在 2~60 之间）是给重试提示词
        // 和开发者看的，直接甩给用户等于没说 —— 它以前就是被原样存进 error_msg 的。
        // 但与"用户自己提的条件"相关的那些要挑出来附上：条件自相矛盾时（既要求用牛肉、
        // 又忌口牛肉），不说清楚用户只会一遍遍重试。
        log.warn("生成失败：{} 次尝试都没通过校验，最后一次的问题：{}",
                attempts, String.join("；", lastProblems));
        throw new DishGenerationException("AI 没能用「" + describe(requiredNames)
                + "」想出合适的做法，换个食材组合或口味再试试" + constraintHint(lastProblems),
                null, attempts, lastRawOutput, lastValidation, meta(referenceIds));
    }

    /**
     * 这次生成用的配方（模型 / 提示词版本 / 温度 / 重试上限 / 参考菜谱），供生成记录落库。
     *
     * <p>参考菜谱 ID 也放在这里：事后必须能回答"这一版输出到底看了哪些参考" ——
     * 否则"参考增强"本身就成了一个说不清的黑盒。
     */
    private GenerateResult.GenerationMeta meta(List<Long> referenceDishIds) {
        return new GenerateResult.GenerationMeta(model, promptVersion,
                properties.getTemperature(), properties.getMaxAttempts(), referenceDishIds);
    }

    /** 取内容哈希的前 8 位 —— 版本号要短到能塞进日志，又能区分不同版本 */
    private String md5Prefix(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 从校验问题里挑出"和用户条件有关"的那些，附给用户看 */
    private String constraintHint(List<String> problems) {
        List<String> hit = problems.stream()
                .filter(p -> p.startsWith("用户要求必用")
                        || p.startsWith("出现了用户禁用")
                        || p.startsWith("用到了用户没有")
                        || p.startsWith("总耗时"))
                .toList();
        return hit.isEmpty() ? "" : "；与你的条件有关：" + String.join("；", hit);
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

        ChatResponse response = callWithTimeout(prompt);
        return response.getResult().getOutput().getText().trim();
    }

    /**
     * 带超时的模型调用。
     *
     * <p>为什么需要它：实测遇到过单次生成卡到 6 分钟以上（小模型在矛盾约束下反复"思考"），
     * 同步调用会一直占着队列线程，用户界面只显示"生成中"，没有任何提示，
     * 也没法判断是模型慢还是程序卡了。有了超时，用户能在有限时间内拿到明确结论。
     *
     * <p>要说清楚的语义边界：底层 HTTP 请求<b>不会</b>因为这里超时就被取消
     * （和"用户取消"是同一种情况：应用侧放手，模型侧可能还在跑、还占着 GPU）。
     * 这里保证的是应用侧不被一条请求拖住。
     */
    private ChatResponse callWithTimeout(Prompt prompt) {
        // 下限取 1 秒而不是某个"看起来安全"的值：默认 240 秒已经足够宽松，
        // 而把下限抬高只会让"想用短超时验证行为"变得没法测（测试要真等 30 秒）。
        long timeout = Math.max(1, properties.getAttemptTimeoutSeconds());
        Future<ChatResponse> future = modelCallExecutor.submit(() -> chatModel.call(prompt));
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DishGenerationException("模型响应超时（超过 " + timeout
                    + " 秒），已放弃本次生成。可以重试，或换个更常见的食材组合");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new DishGenerationException("调用模型失败：" + brief(cause.getMessage()), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DishGenerationException("生成被中断（服务可能正在重启），请重新提交");
        }
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

        StringBuilder sb = new StringBuilder();
        sb.append("食材：").append(names)
                .append("；口味：").append(taste)
                .append("；人数：").append(input.servings()).append(" 人");
        appendConstraints(sb, input.constraintsOrNone());
        return sb.toString();
    }

    /**
     * 把用户约束原样写进提示词。
     *
     * <p>约束必须"既进提示词、又进校验器"：只在提示词里说，模型可以不理；
     * 只在校验器里卡，模型第一次几乎必然踩雷、白跑一轮重试。
     * 两头都上，第一次命中的概率才高。
     */
    private void appendConstraints(StringBuilder sb, GenerateConstraints c) {
        if (c == null || c.noConstraint()) {
            return;
        }
        sb.append("\n\n用户附加要求（必须全部满足）：");
        List<String> mustUse = mustUseLabels(c);
        if (!mustUse.isEmpty()) {
            sb.append("\n- 必须用上的食材：").append(String.join("、", mustUse));
        }
        Set<String> excludes = new LinkedHashSet<>(c.excludeNames());
        for (Long id : c.excludeIdSet()) {
            String name = directory.nameOf(id);
            excludes.add(name != null ? name : ("食材#" + id));
        }
        if (!excludes.isEmpty()) {
            sb.append("\n- 绝对不能用（过敏或忌口）：").append(String.join("、", excludes));
        }
        if (c.maxCookTimeMin() != null) {
            sb.append("\n- 总耗时不超过 ").append(c.maxCookTimeMin()).append(" 分钟（备菜 + 下锅）");
        }
        if (!c.equipmentNames().isEmpty()) {
            sb.append("\n- 只能用这些厨具：").append(String.join("、", c.equipmentNames()));
        }
    }

    private List<String> mustUseLabels(GenerateConstraints c) {
        List<String> labels = new ArrayList<>(c.mustUseNames());
        for (Long id : c.mustUseIdSet()) {
            String name = directory.nameOf(id);
            labels.add(name != null ? name : ("食材#" + id));
        }
        return labels;
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
