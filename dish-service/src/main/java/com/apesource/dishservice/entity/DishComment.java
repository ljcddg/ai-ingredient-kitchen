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

    /**
     * 命中的违规词，逗号分隔（只有管理员看得到）。
     *
     * <p>{@code content} 存的是<b>脱敏后</b>的文本，所以「为什么这条被屏蔽」
     * 必须靠这一列解释 —— 用户看到的是星号，管理员看到的是"命中了哪几个词"。
     */
    private String hitWords;

    /** 被举报次数。达到阈值自动转待审核，把人力集中在争议内容上 */
    private Integer reportCount;

    /** 管理员处置说明（下架/放行的依据）。被下架的内容不删行，靠它留下处置理由 */
    private String auditRemark;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
