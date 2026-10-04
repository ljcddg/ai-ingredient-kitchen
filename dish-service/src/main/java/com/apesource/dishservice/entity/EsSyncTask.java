package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ES 索引同步待办（{@code t_es_sync_task}）。
 *
 * <p>它是"索引最终一致"的<b>事实来源</b>：只要这条记录还在，就说明库里那份数据和
 * 索引还没对上，后台任务会一直重试到成功或明确放弃。
 *
 * <p>唯一键在 {@code dishId} 上 —— 同一道菜只有一条待办。这一点很关键：
 * 用户连续发布/撤回十次，产生的不是十条任务，而是一条被刷新了十次的记录。
 *
 * <p>它不记录"该写入还是该删除"：同步器每次都按菜品当前状态决定
 * （见 {@code DishEsSyncService#syncOne}）。这样旧任务永远不会把已经撤回的菜
 * 重新塞回索引 —— 任务只表达"这道菜需要重新对一次"，不表达"要做什么"。
 */
@Data
@TableName("t_es_sync_task")
public class EsSyncTask {

    /** 待同步（新建或刷新后的默认状态） */
    public static final int STATUS_PENDING = 0;

    /** 已完成 */
    public static final int STATUS_DONE = 1;

    /** 失败待重试（还没到放弃次数） */
    public static final int STATUS_FAILED = 2;

    /** 已放弃：重试次数用尽，需要人工介入（此时才值得告警） */
    public static final int STATUS_GIVEN_UP = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dishId;

    /** 触发时的菜品版本（{@code t_dish.update_time}），用于追溯是哪次变更引发的 */
    private LocalDateTime version;

    private Integer status;

    /** 已尝试次数 */
    private Integer attempts;

    /** 下次可执行时间（退避用） */
    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
