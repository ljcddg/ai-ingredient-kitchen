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
 * 菜品收藏（{@code t_dish_collect}）。
 *
 * <p><b>收藏和评分是两个语义不同的信号，不能混用</b>：
 * 收藏表示"我想做这道菜"，评分表示"我做过了、这是评价"。
 * 把两者压成一个排序特征，推荐会失真 —— 所以这里单独一张表。
 *
 * <p>注意{@link #deleted}上的 {@link TableLogic}：本表的删除是<b>逻辑删除</b>，
 * 而表上又有 {@code uk_dish_user(dish_id, user_id)} 唯一键 ——
 * 这两者放在一起会直接冲突（取消收藏后记录还在，再收藏就撞唯一键）。
 * 所以本表的增删<b>不走 MP 的自动方法</b>，一律用
 * {@code DishCollectMapper} 里手写的 SQL（插入走恢复式 upsert、取消走条件更新）。
 */
@Data
@TableName("t_dish_collect")
public class DishCollect {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dishId;

    private Long userId;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
