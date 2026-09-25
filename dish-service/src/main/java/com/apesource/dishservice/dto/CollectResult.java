package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 收藏操作的结果。
 *
 * <p>把「当前状态」和「最新收藏数」一起返回，前端就不用为了刷新数字再查一次详情。
 */
@Data
public class CollectResult {

    /** 操作之后是否处于「已收藏」状态 */
    private Boolean collected;

    /** 最新的收藏总数 */
    private Integer collectCount;
}
