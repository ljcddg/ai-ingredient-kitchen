package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 违规词的新增 / 修改请求。
 *
 * <p>{@code reason} 这类字段故意设了长度上限：词库是「人肉维护的规则集合」，
 * 一条 500 字的备注说明维护者自己也没想清楚要拦什么。
 */
@Data
public class SensitiveWordRequest {

    /** 违规词 */
    private String word;

    /** 类别：AD / PORN / VIOLENCE / ILLEGAL / ABUSE / OTHER */
    private String category;

    /** 处置级别：1-替换为星号，2-整条拦截待审 */
    private Integer level;

    /** 备注：为什么收录 */
    private String remark;

    /** 是否启用，默认启用 */
    private Integer enabled;
}
