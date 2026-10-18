package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 用户举报评论的请求体。
 *
 * <p>{@code reasonType} 用枚举值而不是自由文本：审核页要按理由聚合
 * （「这个月广告举报占多少」），自由文本聚不出来。
 * 自由文本只作为补充（{@code reason}），可空。
 */
@Data
public class ReportRequest {

    /** 理由：0-其他，1-广告导流，2-辱骂攻击，3-色情低俗，4-违法违规 */
    private Integer reasonType;

    /** 补充说明，可空，最多 200 字 */
    private String reason;
}
