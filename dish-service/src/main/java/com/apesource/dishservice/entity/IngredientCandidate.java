package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 食材候选（{@code t_ingredient_candidate}）。
 *
 * <p>用户能在输入框里敲主数据之外的食材（「松露」「香茅」…）。它们没有 ID，
 * 只能靠名字参与召回 —— 能用，但不精确：同一样东西的不同叫法（「松露」/「黑松露」）
 * 会被当成两样，而且名字匹配的召回质量也不如 ID。
 *
 * <p>所以把它们记在这里计数：出现次数够多，说明是真需求，就<b>转正</b>成正式食材
 * （写 {@code t_ingredient} + 回填历史菜品的 {@code ingredient_id}），
 * 之后它们就走 ID 召回了。
 *
 * <p><b>为什么不直接收进主数据</b>：主数据是所有归一化与营养计算的基准，
 * 让垃圾输入（「狗屎」）直接进去会污染它。用「先计数、够了再转正」做一道缓冲，
 * 真实需求自己浮上来，噪声自己沉下去。
 */
@Data
@TableName("t_ingredient_candidate")
public class IngredientCandidate {

    /** 还是候选，没转正 */
    public static final int STATUS_CANDIDATE = 0;

    /** 已转正为正式食材 */
    public static final int STATUS_PROMOTED = 1;

    /** 已拒绝（明显是垃圾输入，人工标记） */
    public static final int STATUS_REJECTED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 食材名（用户输入时的原文） */
    private String name;

    /** 归一化键（去空格小写），唯一，用于计数去重 */
    private String nameKey;

    /** 被使用次数（每出现一次 +1） */
    private Integer useCount;

    /** 状态：0-候选，1-已转正，2-已拒绝 */
    private Integer status;

    /** 转正后对应的 t_ingredient.id */
    private Long ingredientId;

    private LocalDateTime firstSeen;

    private LocalDateTime lastSeen;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
