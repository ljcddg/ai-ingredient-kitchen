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
 * 菜品评论（{@code t_dish_comment}）。
 *
 * <p>和 {@code t_dish_rating} 是两回事：那边是"一人一票的数值"，靠唯一键防刷；
 * 这边是"可多条的文字"，同一个可以评很多条。合成一张表会让唯一键和业务规则互相打架。
 */
@Data
@TableName("t_dish_comment")
public class DishComment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dishId;

    private Long userId;

    private String content;

    /** 评论时顺带给的分 1-5（可空）。计分只认 t_dish_rating，这里只是展示 */
    private Integer rating;

    private Integer likeCount;

    /** 状态：0-待审核，1-正常，2-已隐藏 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
