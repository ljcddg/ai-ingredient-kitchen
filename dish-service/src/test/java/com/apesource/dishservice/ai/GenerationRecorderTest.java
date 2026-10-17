package com.apesource.dishservice.ai;

import com.apesource.dishservice.entity.AiGenerationRecord;
import com.apesource.dishservice.mapper.AiGenerationRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成记录的落地器测试。
 *
 * <p>守两条底线：
 * <ol>
 *   <li><b>记录必须如实</b>：模型/提示词版本/尝试次数/格式与约束标记都要落进对应的列 ——
 *       论文里的对照实验全靠这些列分组统计，落错了结论就错了；</li>
 *   <li><b>记录失败绝不能影响生成</b>：它跑在用户等了十几秒的生成链路末尾，
 *       写日志失败不该把菜也弄丢（唯一键冲突、库抖动都只记 warn）。</li>
 * </ol>
 */
class GenerationRecorderTest {

    private AiGenerationRecordMapper recordMapper;
    private GenerationRecorder recorder;

    @BeforeEach
    void setUp() {
        recordMapper = mock(AiGenerationRecordMapper.class);
        recorder = new GenerationRecorder(recordMapper);
    }

    private DishGenerator.GenerateResult.GenerationMeta meta() {
        return new DishGenerator.GenerateResult.GenerationMeta(
                "gemma4:e2b", "abcd1234", 0.7, 2, List.of(88L, 91L));
    }

    private AiGenerationRecord capture() {
        ArgumentCaptor<AiGenerationRecord> captor = ArgumentCaptor.forClass(AiGenerationRecord.class);
        verify(recordMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("成功记录：菜品ID、发布状态、配方、尝试次数都落库")
    void recordsSuccess() {
        ValidationResult validation =
                new ValidationResult(List.of(), List.of());

        recorder.record(9001L, 1001L, new GenerationRecorder.Outcome(
                AiGenerationRecord.STATUS_SUCCESS, 5555L, 1, null,
                2, 94_750, "{\"name\":\"番茄炒蛋\"}", validation, meta()));

        AiGenerationRecord row = capture();
        assertThat(row.getTaskId()).isEqualTo(9001L);
        assertThat(row.getUserId()).isEqualTo(1001L);
        assertThat(row.getStatus()).isEqualTo(AiGenerationRecord.STATUS_SUCCESS);
        assertThat(row.getDishId()).isEqualTo(5555L);
        assertThat(row.getReviewStatus()).isEqualTo(1);
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getCostMs()).isEqualTo(94_750);
        assertThat(row.getFormatOk()).isEqualTo(1);
        assertThat(row.getConstraintOk()).isEqualTo(1);
        assertThat(row.getModel()).isEqualTo("gemma4:e2b");
        assertThat(row.getPromptVersion()).isEqualTo("abcd1234");
        assertThat(row.getTemperature()).isEqualByComparingTo("0.70");
        assertThat(row.getMaxAttempts()).isEqualTo(2);
        assertThat(row.getRawOutput()).contains("番茄炒蛋");
        assertThat(row.getReferenceDishIds())
                .as("P2-1：参考了哪些菜谱必须记下来，增强生成才追得清")
                .isEqualTo("88,91");
        assertThat(row.getCreateTime()).isNotNull();
    }

    @Test
    @DisplayName("格式合格但约束不合格：两个标记要能分开")
    void splitsFormatAndConstraintFlags() {
        ValidationResult validation = new ValidationResult(
                List.of("没有用上用户提供的食材：葱"), List.of("没有用上用户提供的食材：葱"));

        recorder.record(9002L, 1001L, new GenerationRecorder.Outcome(
                AiGenerationRecord.STATUS_FAILED, null, null, "生成失败",
                2, 0, "{}", validation, meta()));

        AiGenerationRecord row = capture();
        assertThat(row.getFormatOk()).isEqualTo(1);
        assertThat(row.getConstraintOk()).isZero();
        assertThat(row.getValidationErrors()).contains("没有用上用户提供的食材");
        assertThat(row.getErrorMsg()).isEqualTo("生成失败");
    }

    @Test
    @DisplayName("连模型都没调通（没有校验结果）：两个标记都为 0，而不是崩掉")
    void toleratesMissingValidation() {
        recorder.record(9003L, 1001L, new GenerationRecorder.Outcome(
                AiGenerationRecord.STATUS_FAILED, null, null, "调用模型失败：连接被拒绝",
                0, 0, null, null, null));

        AiGenerationRecord row = capture();
        assertThat(row.getFormatOk()).isZero();
        assertThat(row.getConstraintOk()).isZero();
        assertThat(row.getValidationErrors()).isNull();
        assertThat(row.getModel()).isNull();
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    @DisplayName("写失败（唯一键冲突/库抖动）绝不上抛：它跑在生成链路末尾")
    void neverThrows() {
        when(recordMapper.insert(any())).thenThrow(new RuntimeException("Duplicate entry for key 'uk_task'"));

        recorder.record(9004L, 1001L, new GenerationRecorder.Outcome(
                AiGenerationRecord.STATUS_SUCCESS, 1L, 0, null, 1, 10, "{}",
                new ValidationResult(List.of(), List.of()), meta()));

        verify(recordMapper).insert(any());
    }

    @Test
    @DisplayName("原始输出过长要截断，别把几万字符塞进一行记录")
    void truncatesRawOutput() {
        String huge = "x".repeat(25_000);

        recorder.record(9005L, 1001L, new GenerationRecorder.Outcome(
                AiGenerationRecord.STATUS_FAILED, null, null, "e",
                2, 0, huge, null, meta()));

        AiGenerationRecord row = capture();
        assertThat(row.getRawOutput()).hasSizeLessThan(20_100);
        assertThat(row.getRawOutput()).endsWith("(截断)");
    }

    private DishGenerator.GenerateResult.GenerationMeta metaWithGrounding(
            DishGenerator.GenerateResult.GroundingStats grounding) {
        return new DishGenerator.GenerateResult.GenerationMeta(
                "gemma4:e2b", "abcd1234", 0.2, 2, List.of(88L), List.of("season-salt"),
                grounding);
    }

    @Test
    @DisplayName("RAGAS 四项全部落库：faith / answerRelevance / ctxPrecision / ctxRecall")
    void recordsRagasMetrics() {
        var grounding = new DishGenerator.GenerateResult.GroundingStats(
                2, 1, 0, 2.0 / 3, 0.9, 1.0, 0.5);

        recorder.record(9006L, 1001L, new GenerationRecorder.Outcome(
                AiGenerationRecord.STATUS_SUCCESS, 1L, 1, null, 1, 1000, "{}",
                new ValidationResult(List.of(), List.of()), metaWithGrounding(grounding)));

        AiGenerationRecord row = capture();
        assertThat(row.getGroundedCnt()).isEqualTo(2);
        assertThat(row.getDeviatedCnt()).isEqualTo(1);
        assertThat(row.getUngroundedCnt()).isZero();
        assertThat(row.getFaith()).isEqualByComparingTo("0.6667");
        assertThat(row.getAnswerRelevance()).isEqualByComparingTo("0.9000");
        assertThat(row.getCtxPrecision()).isEqualByComparingTo("1.0000");
        assertThat(row.getCtxRecall()).isEqualByComparingTo("0.5000");
        assertThat(row.getKnowledgeRuleIds()).isEqualTo("season-salt");
    }

    @Test
    @DisplayName("判不了的指标必须留 NULL，不能写成 0 分")
    void keepsUnjudgeableMetricsNull() {
        // 无参考（GENERATE_FRESH）：faith 与 ctx 两项都判不了，只有 answerRelevance 成立
        var grounding = new DishGenerator.GenerateResult.GroundingStats(
                0, 0, 0, 0.0, 0.85, null, null);

        recorder.record(9007L, 1001L, new GenerationRecorder.Outcome(
                AiGenerationRecord.STATUS_SUCCESS, 1L, 1, null, 1, 1000, "{}",
                new ValidationResult(List.of(), List.of()), metaWithGrounding(grounding)));

        AiGenerationRecord row = capture();
        assertThat(row.getFaith()).as("没参考时写 0 会把冷启动记录拖成质量事故").isNull();
        assertThat(row.getGroundedCnt()).isNull();
        assertThat(row.getCtxPrecision()).isNull();
        assertThat(row.getCtxRecall()).isNull();
        assertThat(row.getAnswerRelevance()).as("对照组数据必须留下").isEqualByComparingTo("0.8500");
    }
}
