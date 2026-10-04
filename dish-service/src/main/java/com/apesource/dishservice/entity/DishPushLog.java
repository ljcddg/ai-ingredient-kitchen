package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐流推流日志（{@code t_dish_push_log}）。
 *
 * <p>它只回答一个问题：<b>这道菜最近推给过这个用户吗？</b>
 * 用来做 24 小时去重 —— 同一个人短时间内反复刷到同一道菜，
 * 会让推荐流看起来像坏了，也会让 {@code push_count} 虚高。
 *
 * <p><b>唯一键落在 {@code (dish_id, user_id)} 上，一人一菜只保留一行。</b>
 * 重复推流走 {@code ON DUPLICATE KEY UPDATE push_time = NOW()} 刷新时间，
 * 而不是每次插一条新记录 —— 后者会让表随用户刷新次数成倍膨胀。
 *
 * <p>24 小时这个窗口是<b>查询条件</b>而不是唯一键实现的：
 * 唯一键负责"只留一行"，时间窗口负责"多久之后可以再推"。
 */
@Data
@TableName("t_dish_push_log")
public class DishPushLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dishId;

    private Long userId;

    /** 最近一次推给该用户的时间 */
    private LocalDateTime pushTime;
}
