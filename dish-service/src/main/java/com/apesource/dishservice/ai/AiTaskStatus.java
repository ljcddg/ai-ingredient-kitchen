package com.apesource.dishservice.ai;

import lombok.Getter;

/**
 * AI 生成任务的状态。
 *
 * <p>数值与 {@code t_ai_generate_task.status} 一一对应 —— 改这里必须同步改表注释，
 * 否则排查线上问题时会对着一个看不懂的数字发呆。
 *
 * <p><b>两条规则必须遵守</b>：
 * <ol>
 *   <li>{@link #isTerminal()} 为 true 的状态<b>不可再流转</b>（成功了就不能再改成失败）</li>
 *   <li>只有 {@link #PENDING} 和 {@link #RUNNING} 的任务可以被取消</li>
 * </ol>
 */
@Getter
public enum AiTaskStatus {

    /** 已入队，还没轮到 */
    PENDING(0, "排队中"),
    /** 正在调模型 */
    RUNNING(1, "生成中"),
    /** 生成成功，草稿已落库 */
    SUCCESS(2, "已完成"),
    /** 生成失败（含校验重试后仍不合格） */
    FAILED(3, "生成失败"),
    /** 用户主动取消 */
    CANCELLED(4, "已取消"),
    /**
     * 服务重启导致的中断。
     *
     * <p>内存队列一重启就没了，DB 里却还留着"排队中/生成中"的记录，
     * 不处理的话这些任务会永远转圈。启动时统一清扫成这个状态，
     * 让用户看到"已中断，可重试"，而不是自己决定要不要偷偷重跑
     * （任务可能已经写了一半草稿，重跑会产生重复数据）。
     */
    INTERRUPTED(5, "已中断");

    private final int code;
    private final String text;

    AiTaskStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static AiTaskStatus of(Integer code) {
        if (code != null) {
            for (AiTaskStatus s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
        }
        return FAILED;
    }

    /** 终态：不可再流转 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED || this == INTERRUPTED;
    }

    /** 可取消：只有还没结束的任务能取消 */
    public boolean isCancellable() {
        return this == PENDING || this == RUNNING;
    }
}
