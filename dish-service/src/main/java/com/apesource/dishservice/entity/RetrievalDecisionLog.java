package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检索决策日志（{@code t_retrieval_decision}）。
 *
 * <p>每发生一次「要不要调模型」的判定就落一行。这张表不是审计用的，
 * 它是论文第 5 章唯一的数据来源：<b>决策分布、直出率、省下的调用数、
 * 判定与实际是否一致</b>，全从这些行数出来。
 *
 * <p>写库失败绝不抛异常影响检索主流程（与 {@code t_ingredient_candidate} 同一原则）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_retrieval_decision")
public class RetrievalDecisionLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID，匿名/后台触发时为空 */
    private Long userId;

    /** 决策来源：SEARCH（召回接口判定）/ GENERATE（生成时实际注入参考） */
    private String source;

    /** 食材集合的稳定指纹：排序后拼接取 MD5，同一次选择永远同一个值 */
    private String queryHash;

    /** 档位：DIRECT / RAG_GENERATE / GENERATE_FRESH */
    private String decision;

    /** 最优候选的主料满足率 0~1 */
    private BigDecimal satisfactionRate;

    /** 命中主料数 */
    private Integer matchedCount;

    /** 还缺几样主料 */
    private Integer missingCount;

    /** ES 召回条数 */
    private Integer esHit;

    /** 实际注入模型的参考菜谱条数（只有 GENERATE 来源会填） */
    private Integer referenceCount;

    /** 检索引擎是否可用：0 = 走了降级 */
    private Integer esAvailable;

    /** 是否发生回退：判了 RAG_GENERATE 但最终没拿到参考，就是回退 */
    private Integer fallback;

    /** 本次决策链路耗时（毫秒） */
    private Integer costMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
