package com.apesource.dishservice.ai;

/**
 * 生成失败。
 *
 * <p>刻意不继承 common 的 {@code BusinessException}：那个是给"用户操作不合法"用的，
 * 会被全局异常处理器直接转成给用户看的 Result。而生成失败发生**后台线程**里，
 * 它的归宿是写进 {@code t_ai_generate_task.error_msg}，让前端在任务卡片上展示，
 * 走的是完全不同的通道。
 */
public class DishGenerationException extends RuntimeException {

    public DishGenerationException(String message) {
        super(message);
    }

    public DishGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
