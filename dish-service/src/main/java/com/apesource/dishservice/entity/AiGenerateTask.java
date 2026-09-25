package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 生成任务（{@code t_ai_generate_task}）。
 *
 * <p>它同时承担三件事，这也是为什么没把它拆成三张表：
 * <ol>
 *   <li>异步任务的进度（前端靠它轮询）</li>
 *   <li>单飞去重（同一 signature 的并发请求合并成一次调用）</li>
 *   <li>日配额与频控统计</li>
 * </ol>
 *
 * <p>状态值的含义见 {@code AiTaskStatus}，改一处必须改另一处。
 */
@Data
@TableName("t_ai_generate_task")
public class AiGenerateTask {

    /** 也就是返回给前端的 taskId */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 请求参数规范化后的 MD5（食材排序 + 口味 + 人数），用于单飞与精确缓存 */
    private String requestSignature;

    /** 原始请求参数快照（食材 ID/名称、口味、人数） */
    private String requestJson;

    /**
     * 状态：0-排队中，1-生成中，2-成功，3-失败，4-已取消，5-已中断
     *
     * <p>用 Integer 而不是枚举：MP 直接映射枚举需要额外配置，
     * 而这个字段会被到处读写（队列、Service、清扫器），
     * 用 {@code AiTaskStatus.of(...)} 显式转换反而更清楚。
     */
    private Integer status;

    /** 成功后落库的草稿菜品 ID */
    private Long resultDishId;

    private String errorMsg;

    /** AI 调用耗时（ms） */
    private Integer costMs;

    /** 排队等待时长（ms）—— 用来回答"用户实际等了多久" */
    private Integer queueWaitMs;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
