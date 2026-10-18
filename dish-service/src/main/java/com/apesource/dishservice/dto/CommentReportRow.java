package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 管理端的一条举报记录（举报 + 被举报评论 + 双方昵称的联表结果）。
 *
 * <p>评论原文直接给管理员看 —— 用户看到的是星号，但管理员必须看到
 * 「他说了什么、命中了什么词」才能判断该下架还是驳回。
 * 星号在审核场景里是障碍，不是保护。
 */
@Data
public class CommentReportRow {

    private Long id;

    private Long commentId;

    private Long dishId;

    private String dishName;

    /** 被举报评论的原文 */
    private String commentContent;

    /** 评论作者昵称 */
    private String commentAuthor;

    /** 评论当前状态：0-待审核，1-正常，2-已隐藏 */
    private Integer commentStatus;

    /** 该评论命中的违规词（逗号分隔） */
    private String hitWords;

    /** 该评论累计被举报次数 */
    private Integer reportCount;

    private Long reporterId;

    private String reporterName;

    private Integer reasonType;

    private String reason;

    /** 举报状态：0-待处理，1-已受理，2-已驳回 */
    private Integer status;

    private String handleRemark;

    private String handleTime;

    private String createTime;
}
