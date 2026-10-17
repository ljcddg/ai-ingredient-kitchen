package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 食材替代建议（{@code t_ingredient_substitute}）。
 *
 * <p>离线统计产物：同类（叶菜换叶菜、豆腐换豆干）与共现（常一起做）两路来源，
 * 由 {@code sql/build-stats.py} 生成。服务端只读，不写。
 */
@Data
@TableName("t_ingredient_substitute")
public class IngredientSubstitute {

    private Long id;

    /** 原食材 */
    private Long ingredientId;

    /** 可替代食材 */
    private Long substituteId;

    /** 置信度 0~1，排序用 */
    private BigDecimal score;

    /** same_category / co_occur */
    private String source;

    /** 给人看的理由 */
    private String reason;

    /** 支撑样本数 */
    private Integer sampleCount;

    private LocalDateTime updateTime;
}
