package com.apesource.dishservice.ai;

/**
 * 生成失败。
 *
 * <p>刻意不继承 common 的 {@code BusinessException}：那个是给"用户操作不合法"用的，
 * 会被全局异常处理器直接转成给用户看的 Result。而生成失败发生**后台线程**里，
 * 它的归宿是写进 {@code t_ai_generate_task.error_msg}，让前端在任务卡片上展示，
 * 走的是完全不同的通道。
 *
 * <p>除了给用户看的一句话，它还捎带**给记录用的原始材料**：试了几次、
 * 模型最后输出了什么、最后一次的校验结果。这些不写进 error_msg（用户不看），
 * 但要落进 {@code t_ai_generation_record} —— 否则事后只剩一句"生成失败"，
 * 没法归因到底是提示词的问题还是模型的问题。
 */
public class DishGenerationException extends RuntimeException {

    /** 实际尝试次数（0 表示连模型都没调通） */
    private final int attempts;

    /** 最后一次的模型原始输出，可为 null */
    private final String rawOutput;

    /** 最后一次的校验结果，可为 null */
    private final DishDraftValidator.ValidationResult validation;

    /** 本次用的配方，可为 null */
    private final DishGenerator.GenerateResult.GenerationMeta meta;

    public DishGenerationException(String message) {
        this(message, null, 0, null, null, null);
    }

    public DishGenerationException(String message, Throwable cause) {
        this(message, cause, 0, null, null, null);
    }

    public DishGenerationException(String message, Throwable cause, int attempts, String rawOutput,
                                   DishDraftValidator.ValidationResult validation,
                                   DishGenerator.GenerateResult.GenerationMeta meta) {
        super(message, cause);
        this.attempts = attempts;
        this.rawOutput = rawOutput;
        this.validation = validation;
        this.meta = meta;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getRawOutput() {
        return rawOutput;
    }

    public DishDraftValidator.ValidationResult getValidation() {
        return validation;
    }

    public DishGenerator.GenerateResult.GenerationMeta getMeta() {
        return meta;
    }
}
