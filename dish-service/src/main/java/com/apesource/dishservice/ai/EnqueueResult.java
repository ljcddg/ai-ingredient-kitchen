package com.apesource.dishservice.ai;

/**
 * 入队结果。
 *
 * @param accepted  是否入队成功
 * @param position  入队后的排队位置（1 表示下一个就轮到它）；被拒时为 0
 * @param queueSize 当前等待队列长度
 * @param reason    被拒原因（成功时为 null）
 */
public record EnqueueResult(boolean accepted, int position, int queueSize, String reason) {

    public static EnqueueResult ok(int position, int queueSize) {
        return new EnqueueResult(true, position, queueSize, null);
    }

    public static EnqueueResult rejected(String reason, int queueSize) {
        return new EnqueueResult(false, 0, queueSize, reason);
    }
}
