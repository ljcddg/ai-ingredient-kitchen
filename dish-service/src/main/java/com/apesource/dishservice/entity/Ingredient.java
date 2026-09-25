package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 食材主数据（{@code t_ingredient}）。
 *
 * <p>营养字段是「每 100g」的值。它的用途有两块：
 * <ol>
 *   <li>前端展示食材分类</li>
 *   <li><b>后台校验</b>：用「每 100g 营养 × 用量克数」算一份，与 AI 给的值对比，
 *       偏差过大就说明这次生成可疑 —— 这是"AI 营养仅供参考"背后那道看不见的保险</li>
 * </ol>
 *
 * <p>注意：这些值目前是常见食物成分参考值，量级正确但不保证逐条精确，
 * 详见 {@code sql/dish-seed-ingredient.sql} 文件头。
 */
@Data
@TableName("t_ingredient")
public class Ingredient {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 标准名（唯一），如"番茄" */
    private String name;

    /** 别名，逗号分隔（"西红柿,tomato"）—— 自由输入归一的第 2 步靠它 */
    private String alias;

    /** 分类：蔬菜/肉类/水产/蛋奶/豆制品/主食/菌菇/水果/调料 */
    private String category;

    /** 标准计量单位：g/ml/个 */
    private String unit;

    /** 单个折算克数（如鸡蛋 1 个 ≈ 50g） */
    private BigDecimal unitGram;

    /** 每 100g 热量（kcal） */
    private BigDecimal calorie;

    /** 每 100g 蛋白质（g） */
    private BigDecimal protein;

    /** 每 100g 脂肪（g） */
    private BigDecimal fat;

    /** 每 100g 碳水化合物（g） */
    private BigDecimal carb;

    /** 每 100g 膳食纤维（g） */
    private BigDecimal fiber;

    /** 每 100g 钠（mg） */
    private BigDecimal sodium;

    /** 是否常用：0-否，1-是（前端首屏展示） */
    private Integer isCommon;

    private Integer sortNo;

    /** 状态：0-停用，1-启用 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
