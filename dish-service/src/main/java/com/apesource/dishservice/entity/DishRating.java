package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 菜品评分（{@code t_dish_rating}）。
 *
 * <p><b>一人一菜一票</b>，靠表上的 {@code uk_dish_user} 唯一键保证 ——
 * 这是防刷分的核心：不依赖应用层"先查有没有再插"（并发下必然漏），
 * 而是让数据库直接拒绝第二条。
 *
 * <p>用户改分走 {@code ON DUPLICATE KEY UPDATE}，不新增行。
 *
 * <p>{@link #weight} 是给新号/异常号降权用的（默认 1.00）。
 * 第一版不实现权重逻辑，但字段留着 —— 加字段不需要改表，加逻辑要。
 */
@Data
@TableName("t_dish_rating")
public class DishRating {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dishId;

    private Long userId;

    /** 总评 1-5 */
    private Integer score;

    /** 口味分 1-5（可空，预留给后续的细分维度） */
    private Integer tasteScore;

    /** 是否真的做过：0-没做，1-做过（打卡） */
    private Integer cookedFlag;

    private String comment;

    private BigDecimal weight;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
