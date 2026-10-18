package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 管理员处理举报的请求体。
 *
 * <p>刻意只提供两个动作：{@code remove}（下架评论）与 {@code reject}（驳回举报）。
 * 没有「忽略」这个第三态 —— 一条举报悬着不处理，列表会越积越长，
 * 而「没空处理」和「看过认为没问题」在数据里长得一模一样。
 */
@Data
public class ReportHandleRequest {

    /** remove=下架评论（受理），reject=驳回举报 */
    private String action;

    /** 处理说明。下架建议写清依据，方便日后复盘 */
    private String remark;
}
