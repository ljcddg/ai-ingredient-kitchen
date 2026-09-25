package com.apesource.dishservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个制作步骤。
 *
 * <p>步骤存成 {@code t_dish.step_json} 里的 JSON 数组而不是独立表：
 * 它有顺序、但从不参与召回或计算，只是"读出来给页面渲染"。
 * 独立表要有主键、外键、排序字段、索引，换来的查询能力这里一条都用不上。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DishStep {
    private Integer no;
    private String text;
}
