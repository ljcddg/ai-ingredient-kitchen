package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 打分请求体。
 *
 * <p>{@code cooked} 表示"我是不是真的做过这道菜"，默认不传即为"没做过"。
 * 之所以留着这个字段，是因为它和"收藏"一起构成了做菜意图的完整信号：
 * 收藏 = 想做，评分 + cooked = 做过了。两者在推荐里权重不同。
 */
@Data
public class RateRequest {

    /** 1-5 星 */
    private Integer score;

    /** 是否真的做过 */
    private Boolean cooked;
}
