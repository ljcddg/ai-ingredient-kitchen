package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 待审评论（{@code t_dish_comment.status = 0}）。
 *
 * <p>进入这里的有两类：命中「整条拦截」级违规词的、被举报次数超过阈值的。
 * 两类在详情页都不可见，所以管理员是它们唯一的出口 —— 这张列表必须能
 * 一眼看出「为什么进来的」：{@code hitWords} 非空是前者，{@code reportCount} 高是后者。
 */
@Data
public class PendingCommentRow {

    private Long id;

    private Long dishId;

    private String dishName;

    private Long userId;

    private String nickname;

    /** 评论原文（含未替换的违规词，管理员需要看到真实内容） */
    private String content;

    /** 命中的违规词，逗号分隔；为空说明是被举报进来的 */
    private String hitWords;

    private Integer reportCount;

    private String createTime;
}
