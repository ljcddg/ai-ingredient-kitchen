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
 * 评论举报（{@code t_comment_report}）。
 *
 * <p>一条举报只表态一次，<b>不下架评论</b>。下架是管理员的动作 ——
 * 举报量只用来给内容排序、以及在达到阈值时把评论推进待审队列，
 * 绝不能「举报够 3 次就自动隐藏」：那样任何人拉三个人就能让别人的内容消失。
 *
 * <p>{@code dishId} 是冗余字段。审核列表要显示「哪道菜的评论」，
 * 没有它每条都要回查评论表再回查菜品表。
 */
@Data
@TableName("t_comment_report")
public class CommentReport {

    /** 举报理由：其他 */
    public static final int REASON_OTHER = 0;
    /** 举报理由：广告导流 */
    public static final int REASON_AD = 1;
    /** 举报理由：辱骂攻击 */
    public static final int REASON_ABUSE = 2;
    /** 举报理由：色情低俗 */
    public static final int REASON_PORN = 3;
    /** 举报理由：违法违规 */
    public static final int REASON_ILLEGAL = 4;

    /** 状态：待处理 */
    public static final int STATUS_PENDING = 0;
    /** 状态：已受理（评论已下架） */
    public static final int STATUS_ACCEPTED = 1;
    /** 状态：已驳回 */
    public static final int STATUS_REJECTED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long commentId;

    /** 冗余：评论所属菜品，审核列表展示与跳转用 */
    private Long dishId;

    private Long reporterId;

    /** 理由：0-其他，1-广告导流，2-辱骂攻击，3-色情低俗，4-违法违规 */
    private Integer reasonType;

    /** 补充说明，可空 */
    private String reason;

    /** 状态：0-待处理，1-已受理，2-已驳回 */
    private Integer status;

    private Long handlerId;

    private String handleRemark;

    private LocalDateTime handleTime;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
