package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.retrieval.RetrievalDecision;
import com.apesource.dishservice.retrieval.RetrievalDecisionLogger;
import com.apesource.dishservice.service.IngredientDirectory;
import com.apesource.dishservice.service.IngredientSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 远程菜谱生成器 —— 调 Python ai-service 完成生成的 {@link DishGenerator} 实现。
 *
 * <p>它是"Java 业务编排 + Python AI 推理"解耦的落点：本类只认 HTTP 契约，
 * 不关心 ai-service 背后是哪个模型。换模型 = 改 Python 一侧，这里一行不用动。
 *
 * <p>推理全部在 Python 侧（提示词、调模型、校验、重试、营养比对）。
 * 原来的 Java 本地实现已删除，本类是 {@link DishGenerator} 唯一的实现。
 *
 * <p>职责边界（与 ai-service 的分工）：
 * <ul>
 *   <li><b>Java 侧</b>：参考菜谱检索（ES DSL 踩过坑，不 duplicated）、
 *       主数据快照组装、结果映射回 {@link GenerateResult}；</li>
 *   <li><b>Python 侧</b>：提示词拼装、调 Ollama、校验、重试一次、
 *       营养本地计算与比对。</li>
 * </ul>
 */
@Slf4j
@Component
public class RemoteDishGenerator implements DishGenerator {

    private final GenerateProperties properties;
    private final ReferenceDishRetriever referenceRetriever;
    private final IngredientDirectory directory;
    private final ObjectMapper objectMapper;
    private final RemoteProperties remoteProperties;
    private final RestClient restClient;
    private final RetrievalDecisionLogger decisionLogger;

    public RemoteDishGenerator(GenerateProperties properties,
                               ReferenceDishRetriever referenceRetriever,
                               IngredientDirectory directory,
                               ObjectMapper objectMapper,
                               RemoteProperties remoteProperties,
                               RestClient aiServiceClient,
                               RetrievalDecisionLogger decisionLogger) {
        this.properties = properties;
        this.referenceRetriever = referenceRetriever;
        this.directory = directory;
        this.objectMapper = objectMapper;
        this.remoteProperties = remoteProperties;
        this.restClient = aiServiceClient;
        this.decisionLogger = decisionLogger;

        log.info("远程生成器已启用：ai-service={}，读超时={}s",
                remoteProperties.getBaseUrl(), remoteProperties.getReadTimeoutSeconds());
    }

    @Override
    public GenerateResult generate(GenerateInput input) {
        long start = System.currentTimeMillis();

        // P2-1 参考菜谱增强：检索留在 Java（ES DSL 踩过坑不 duplicated），
        // 检索失败会返回空列表（见 ReferenceDishRetriever），不需要额外兜底。
        // 渲染进提示词是 Python 的事，这里只把参考列表传过去。
        long retrieveStart = System.currentTimeMillis();
        List<ReferenceDishRetriever.Reference> references = properties.isEnhanceEnabled()
                ? referenceRetriever.retrieve(input, properties.getReferenceLimit())
                : List.of();
        int retrieveCost = (int) (System.currentTimeMillis() - retrieveStart);

        // 生成侧决策：真正喂给模型几条参考 = RAG 到底有没有生效。
        // 与召回侧（SEARCH）是两条记录，用 query_hash 关联 ——
        // 判了 RAG_GENERATE 却在这里变成 0 条参考，就是"判了没做到"。
        recordGenerateDecision(input, references, retrieveCost);

        Map<String, Object> body = buildRequestBody(input, references);

        RemoteResponse response;
        try {
            response = restClient.post()
                    .uri("/api/v1/generate")
                    .body(body)
                    .exchange((request, clientResponse) -> {
                        if (clientResponse.getStatusCode().is2xxSuccessful()) {
                            return clientResponse.bodyTo(RemoteResponse.class);
                        }
                        // 4xx/5xx 也尽量把响应体读出来 —— ok=false 的失败原因在里面
                        try {
                            RemoteResponse error = clientResponse.bodyTo(RemoteResponse.class);
                            if (error != null) {
                                return error;
                            }
                        } catch (Exception ignored) {
                            // 读不出来就走下面的兜底
                        }
                        return new RemoteResponse(false, null, false, null, 0, 0, null,
                                null, null, "internal",
                                "AI 服务返回 " + clientResponse.getStatusCode());
                    });
        } catch (Exception e) {
            // 连不上（ai-service 没起、读超时）是另一类问题，重试也不会变好
            String reason = brief(e.getMessage());
            log.error("调用 ai-service 失败：{}", reason, e);
            throw new DishGenerationException(
                    "AI 服务暂时不可用，请稍后重试（原因：" + reason + "）",
                    null, 0, null, null, null);
        }

        if (response == null) {
            throw new DishGenerationException("AI 服务无响应，请稍后重试",
                    null, 0, null, null, null);
        }

        ValidationResult validation = toValidation(response.validation());
        GenerateResult.GenerationMeta meta = toMeta(response.meta());

        if (!response.ok()) {
            // Python 已经把 attempts / rawOutput / validation / meta 都带回来了，
            // 原样重建异常 —— 生成记录的归因字段一个都不少
            throw new DishGenerationException(
                    response.error() == null ? "AI 服务异常" : response.error(),
                    null, response.attempts(), response.rawOutput(), validation, meta);
        }

        if (response.draft() == null) {
            throw new DishGenerationException("AI 服务返回成功但没有草稿内容",
                    null, response.attempts(), response.rawOutput(), validation, meta);
        }

        return new GenerateResult(response.draft(), response.nutritionVerified(),
                response.costMs(), response.attempts(), response.rawOutput(),
                validation, meta);
    }

    // ------------------------------------------------------------------ 请求组装

    /**
     * 生成侧决策落库：这次到底带了几条参考进提示词。
     *
     * <p>只做记录，不参与任何业务判断 —— 参考条数为 0 也照常生成。
     * 用户维度为空（{@code GenerateInput} 里没有 userId，生成是后台线程，
     * 不为了一列统计去改整条链路的方法签名）；按 query_hash 与召回侧那条对齐即可。
     */
    private void recordGenerateDecision(GenerateInput input,
                                        List<ReferenceDishRetriever.Reference> references,
                                        int retrieveCostMs) {
        if (input == null || input.ingredients() == null || input.ingredients().isEmpty()) {
            return;
        }
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
        decisionLogger.logGenerate(null,
                RetrievalDecisionLogger.queryHash(ids, names),
                references.isEmpty() ? RetrievalDecision.GENERATE_FRESH : RetrievalDecision.RAG_GENERATE,
                references.size(), true, retrieveCostMs);
    }

    private Map<String, Object> buildRequestBody(GenerateInput input,
                                                 List<ReferenceDishRetriever.Reference> references) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        // IngredientRef / GenerateConstraints 都是 record，Jackson 按参数名序列化，
        // 字段名与 ai-service 的 schemas 一字不差；Long 由全局配置序列化成字符串
        body.put("ingredients", input.ingredients());
        body.put("tastes", input.tastes());
        body.put("servings", input.servings());
        body.put("constraints", input.constraintsOrNone());
        // 主数据快照：Python 不连库，校验/归一/营养全靠它
        body.put("directory", directory.snapshot());
        body.put("references", references);
        body.put("options", Map.of(
                "model", remoteProperties.getModel(),
                "temperature", properties.getTemperature(),
                "maxAttempts", properties.getMaxAttempts(),
                "timeoutSeconds", properties.getAttemptTimeoutSeconds()));
        return body;
    }

    // ------------------------------------------------------------------ 响应映射

    private ValidationResult toValidation(RemoteResponse.ValidationDto dto) {
        if (dto == null) {
            return new ValidationResult(List.of(), List.of());
        }
        return new ValidationResult(
                dto.problems() == null ? List.of() : dto.problems(),
                dto.constraintProblems() == null ? List.of() : dto.constraintProblems());
    }

    private GenerateResult.GenerationMeta toMeta(RemoteResponse.MetaDto dto) {
        if (dto == null) {
            return new GenerateResult.GenerationMeta(remoteProperties.getModel(), "unknown",
                    properties.getTemperature(), properties.getMaxAttempts(), List.of());
        }
        // referenceDishIds 按字符串传回来（项目铁律：Long 序列化成字符串），这里解析回 Long
        List<Long> referenceIds = new ArrayList<>();
        if (dto.referenceDishIds() != null) {
            for (String raw : dto.referenceDishIds()) {
                try {
                    referenceIds.add(Long.valueOf(raw));
                } catch (NumberFormatException ignored) {
                    // 单个坏数据不该让整次生成的记录落不了库
                }
            }
        }
        return new GenerateResult.GenerationMeta(dto.model(), dto.promptVersion(),
                dto.temperature(), dto.maxAttempts(), referenceIds);
    }

    private String brief(String text) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
    }

    // ------------------------------------------------------------------ 响应 DTO

    /**
     * ai-service 的响应结构。字段名与 Python routes.py 输出一字不差。
     *
     * <p>成功与失败用同一个结构（{@code ok} 区分）：失败时 attempts/rawOutput/
     * validation/meta 照样带回，生成记录的归因字段一个都不少。
     */
    record RemoteResponse(boolean ok, DishDraft draft, boolean nutritionVerified,
                          LocalNutrition localNutrition, int attempts, long costMs,
                          String rawOutput, ValidationDto validation, MetaDto meta,
                          String errorKind, String error) {

        /** 本地营养计算结果（Python 算好带回；当前 DishDraftWriter 仍用 Java 侧重算，先不用） */
        record LocalNutrition(boolean trustable, double calories, double proteinGram,
                              double fatGram, double carbGram, double fiberGram,
                              double sodiumMg, double coverage, int resolvedItems,
                              int totalItems, List<String> unresolved) {
        }

        record ValidationDto(List<String> problems, List<String> constraintProblems) {
        }

        record MetaDto(String model, String promptVersion, double temperature,
                       int maxAttempts, List<String> referenceDishIds) {
        }
    }
}
