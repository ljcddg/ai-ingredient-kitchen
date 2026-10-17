package com.apesource.dishservice.ai;

import com.apesource.dishservice.entity.AiGenerationRecord;
import com.apesource.dishservice.mapper.AiGenerationRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成记录的落地器。
 *
 * <p>它只做一件事：把"这次生成发生了什么"写成一行数据。之所以单独一个类，
 * 是为了让 <b>记录失败绝不影响生成结果</b>这条约束有一个唯一的落点 ——
 * 记录是给事后分析用的，用户等了几十秒做出来的菜不该因为写日志失败而丢掉。
 * 这一点和 {@code IngredientCandidateService.recordUsage} 是同一个原则。
 *
 * <p>写入用唯一键 {@code uk_task} 兜底：万一同一任务被执行了两次（状态机的条件更新
 * 已经防住了，但这里是第二道保险），第二条会因为唯一键冲突而失败并被吞掉，
 * 不会在表里留下两条互相矛盾的记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationRecorder {

    /** 原始输出最多存这么多字符：一次生成的 JSON 通常几 KB，再长的部分对归因没有帮助 */
    private static final int MAX_RAW_OUTPUT = 20_000;

    private static final int MAX_VALIDATION_ERRORS = 1_000;

    private static final int MAX_ERROR_MSG = 500;

    /** knowledge_rule_ids 列宽 128，一次最多也就注入 3 条（CookingKnowledge.MAX_RULES），留够余量 */
    private static final int MAX_RULE_IDS = 120;

    private final AiGenerationRecordMapper recordMapper;

    /**
     * 一次生成的结局。
     *
     * @param status        终态：{@link AiGenerationRecord#STATUS_SUCCESS} 等
     * @param dishId        成功时落库的菜品ID，可为 null
     * @param reviewStatus  生成当时的发布状态（0 草稿 / 1 已发布），可为 null
     * @param errorMsg      失败原因，可为 null
     * @param attempts      实际尝试次数
     * @param costMs        AI 耗时（含重试）
     * @param rawOutput     最后一次的模型原始输出，可为 null（比如根本没调通模型）
     * @param validation    最后一次的校验结果，可为 null
     * @param meta          模型与提示词元信息，可为 null
     */
    public record Outcome(int status, Long dishId, Integer reviewStatus, String errorMsg,
                          int attempts, int costMs, String rawOutput,
                          ValidationResult validation,
                          DishGenerator.GenerateResult.GenerationMeta meta) {
    }

    /** 落一条记录；任何异常都只记 warn，不外抛 */
    public void record(long taskId, long userId, Outcome outcome) {
        if (outcome == null) {
            return;
        }
        try {
            AiGenerationRecord row = new AiGenerationRecord();
            row.setTaskId(taskId);
            row.setUserId(userId);
            row.setStatus(outcome.status());
            row.setDishId(outcome.dishId());
            row.setReviewStatus(outcome.reviewStatus());
            row.setErrorMsg(truncate(outcome.errorMsg(), MAX_ERROR_MSG));
            row.setAttempts(Math.max(0, outcome.attempts()));
            row.setCostMs(Math.max(0, outcome.costMs()));
            row.setRawOutput(truncate(outcome.rawOutput(), MAX_RAW_OUTPUT));

            // 格式合格 / 约束合格分开记：一个说明模型不会写结构化输出，
            // 一个说明它没听用户的话，混在一起就没法指导改进
            ValidationResult validation = outcome.validation();
            if (validation != null) {
                row.setFormatOk(validation.formatOk() ? 1 : 0);
                row.setConstraintOk(validation.constraintOk() ? 1 : 0);
                row.setValidationErrors(truncate(String.join("；", validation.problems()),
                        MAX_VALIDATION_ERRORS));
            } else {
                row.setFormatOk(0);
                row.setConstraintOk(0);
            }

            DishGenerator.GenerateResult.GenerationMeta meta = outcome.meta();
            if (meta != null) {
                row.setModel(meta.model());
                row.setPromptVersion(meta.promptVersion());
                row.setTemperature(BigDecimal.valueOf(meta.temperature()));
                row.setMaxAttempts(meta.maxAttempts());
                // P2-1：记下这次生成参考了哪些菜谱，增强生成才追得清
                row.setReferenceDishIds(joinIds(meta.referenceDishIds()));
                // 知识库：记下注入了哪几条烹饪常识，才能按条目比对效果
                List<String> ruleIds = meta.knowledgeRuleIds();
                if (ruleIds != null && !ruleIds.isEmpty()) {
                    row.setKnowledgeRuleIds(truncate(String.join(",", ruleIds), MAX_RULE_IDS));
                }
                // 依据校验：null 表示判不了（没参考/参考没用量），**不能写成 0**
                var grounding = meta.grounding();
                if (grounding != null && grounding.total() > 0) {
                    row.setGroundedCnt(grounding.grounded());
                    row.setDeviatedCnt(grounding.deviated());
                    row.setUngroundedCnt(grounding.ungrounded());
                    row.setFaith(BigDecimal.valueOf(grounding.faithfulness())
                            .setScale(4, java.math.RoundingMode.HALF_UP));
                }
            }

            row.setCreateTime(LocalDateTime.now());
            recordMapper.insert(row);
        } catch (Exception e) {
            log.warn("写生成记录失败（不影响生成结果）：taskId={} - {}", taskId, e.getMessage());
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(截断)";
    }

    /** 参考菜谱 ID 存成逗号分隔（列宽 255 够放几十条） */
    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
