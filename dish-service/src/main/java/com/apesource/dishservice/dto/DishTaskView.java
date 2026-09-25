package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 一个生成任务对前端的样子。
 *
 * <p>注意 {@code position} 的三种取值，前端要靠它渲染"排队中第 N 位"：
 * <ul>
 *   <li>{@code > 0} —— 排队中，1 表示下一个就轮到</li>
 *   <li>{@code 0} —— 正在生成</li>
 *   <li>{@code -1} —— 不在队列里（已完成 / 已失败 / 已取消）</li>
 * </ul>
 */
@Data
public class DishTaskView {

    private Long taskId;

    private Integer status;

    /** 状态的中文说明，由后端给，避免前端再维护一份映射表 */
    private String statusText;

    /** 排队位置：>0 排队中，0 正在生成，-1 不在队列 */
    private Integer position;

    /** 成功后落库的菜品 ID */
    private Long dishId;

    /** 菜名（成功后才有） */
    private String dishName;

    /**
     * 请求摘要，如「番茄、鸡蛋、葱」。
     *
     * <p>排队中和生成中的任务还没有菜名，任务列表上如果只显示「排队中」，
     * 用户根本分不清哪条是哪一次请求。
     */
    private String summary;

    private String errorMsg;

    /** AI 调用耗时（ms） */
    private Integer costMs;

    /** 排队等待时长（ms） */
    private Integer queueWaitMs;

    private String createTime;

    /** 是否还能取消（前端据此决定按钮是否可点） */
    private Boolean cancellable;
}
