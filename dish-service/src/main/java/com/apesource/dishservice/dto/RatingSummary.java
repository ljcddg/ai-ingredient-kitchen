package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 评分聚合结果。
 *
 * <p>一次打分之后要把这几个值一起还给前端，前端就不用再查一次详情。
 *
 * <p>{@link #wilson} 是威尔逊下界，不是平均分 —— 关于它的作用见
 * {@code DishInteractionService#wilsonLowerBound}。
 */
@Data
public class RatingSummary {

    /** 平均分（1-5，保留 2 位） */
    private Double average;

    /** 评价人数 */
    private Integer count;

    /** 威尔逊下界（0-1，用于"值不值得直出"的判定，不直接展示给用户） */
    private Double wilson;

    /** 我打的分，没打过为 null */
    private Integer myScore;
}
