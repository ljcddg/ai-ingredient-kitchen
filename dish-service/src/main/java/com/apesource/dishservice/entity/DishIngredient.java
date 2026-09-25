package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 菜品-食材明细（{@code t_dish_ingredient}）。
 *
 * <p>{@code ingredientId} 允许为 null —— 用户可以在输入框里自由输入主数据之外的食材
 * （比如"松露"），这类食材没有主数据 ID，但仍然要能正常展示在这道菜里。
 * 所以额外冗余了一个 {@code ingredientName}。
 */
@Data
@TableName("t_dish_ingredient")
public class DishIngredient {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dishId;

    /** 食材主数据 ID；自由输入且未归一时为 null */
    private Long ingredientId;

    /** 食材名（冗余，保证自由输入的食材也能展示） */
    private String ingredientName;

    private BigDecimal amount;

    private String unit;

    /** 折算克数 —— 营养计算的唯一来源 */
    private BigDecimal amountGram;

    /** 是否主料：1-主料，0-辅料 */
    private Integer mainFlag;

    private Integer sortNo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
