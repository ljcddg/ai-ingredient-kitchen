package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 生成记录（{@code t_ai_generation_record}）。
 *
 * <p>一个任务一条（唯一键 {@code uk_task}），记录这次生成"到底发生了什么"：
 * 用的哪个模型、哪版提示词、试了几次、格式与约束分别合不合格、
 * 模型原样输出了什么、最后有没有落库发布。
 *
 * <p>它和 {@link AiGenerateTask} 的分工：
 * <ul>
 *   <li>任务表是<b>队列视角</b> —— 状态、进度、结果ID，前端每 1.5 秒轮询它，越瘦越好；</li>
 *   <li>本表是<b>实验与追溯视角</b> —— 数据大、写得少、只在事后查，
 *       论文里的对照实验、失败归因都从这里出。</li>
 * </ul>
 *
 * <p><b>输入快照不在这里重复存</b>：它就放在任务表的 {@code request_json} 里，
 * 通过 {@link #taskId} 关联即可。同一份数据存两处，迟早会出现两处不一致，
 * 而"到底用了哪份输入"恰恰是追溯里最不能含糊的一件事。
 */
@Data
@TableName("t_ai_generation_record")
public class AiGenerationRecord {

    /** 终态：成功 */
    public static final int STATUS_SUCCESS = 2;

    /** 终态：失败 */
    public static final int STATUS_FAILED = 3;

    /** 终态：用户在生成期间取消（模型结果已产出但被丢弃） */
    public static final int STATUS_CANCELLED = 4;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 对应 {@code t_ai_generate_task.id} */
    private Long taskId;

    /** 发起用户 */
    private Long userId;

    /** 终态：见本类常量 */
    private Integer status;

    /** 模型标识（如 gemma4:e2b） */
    private String model;

    /** 系统提示词版本（内容 MD5 前 8 位）—— 改了提示词，老结果的解释力就不同了 */
    private String promptVersion;

    /** 生成温度 */
    private BigDecimal temperature;

    /** 允许的最大尝试次数 */
    private Integer maxAttempts;

    /** 实际尝试次数（1 表示一次通过） */
    private Integer attempts;

    /** AI 耗时（ms，含重试） */
    private Integer costMs;

    /** 格式合格：字段齐不齐、取值在不在合理区间 */
    private Integer formatOk;

    /** 约束合格：用户的必用/禁用/时长/设备条件与食材覆盖是否满足 */
    private Integer constraintOk;

    /** 最后一次的校验问题（分号分隔），失败归因就看它 */
    private String validationErrors;

    /** 最后一次的模型原始输出（截断保存）—— 出问题时能原样复看，而不是只看一句结论 */
    private String rawOutput;

    /** 成功时落库的菜品ID */
    private Long dishId;

    /** 生成当时的审核/发布状态：0-草稿，1-已发布 */
    private Integer reviewStatus;

    /** 参考菜谱ID（检索增强生成用，逗号分隔） */
    private String referenceDishIds;

    /** 本次注入的烹饪常识条目 id（knowledge/cooking-rules.json 里的 id，逗号分隔） */
    private String knowledgeRuleIds;

    /** 有据项数：用量落在库内参考的 0.5~2 倍区间 */
    private Integer groundedCnt;

    /** 偏离项数：找到参照但用量差得远（炖菜/做汤可能合理，需抽样判断） */
    private Integer deviatedCnt;

    /** 无据项数：库内没有这道菜用到它 */
    private Integer ungroundedCnt;

    /**
     * 有据比例 = grounded / (grounded + deviated + ungrounded)。
     *
     * <p><b>null 表示判不了</b>（没参考 / 参考没带用量），不是 0 分 ——
     * 统计均值时必须过滤 null，否则没有参考的那批会把均值拖到不合理地低。
     */
    private BigDecimal faith;

    /**
     * 答案相关性 = 生成结果在多大程度上真的回答了用户的请求（RAGAS: answer relevance）。
     *
     * <p>由三部分加权：用户食材在菜名/简介/步骤里的出现率、口味契合度、约束契合度。
     * 只算"用到了几成用户食材"会退化成常数 1（校验器本来就拦掉了漏食材的草稿），
     * 那样这个指标永远满分、等于没测。
     *
     * <p>null 表示判不了（没有用户食材也没有口味/约束可对齐），不是 0 分。
     */
    private BigDecimal answerRelevance;

    /**
     * 上下文精确率 = 注入的参考主料里，"用户手上真有"的比例（RAGAS: context precision）。
     *
     * <p>低 → 检索把用不上的菜塞给了模型，提示词里一堆缺料的参考，模型只能硬凑。
     * null 表示判不了（这次没注入参考）。
     */
    private BigDecimal ctxPrecision;

    /**
     * 上下文召回率 = 用户的食材被注入的参考覆盖了多少（RAGAS: context recall）。
     *
     * <p>低 → 用户手里的东西检索没找到对应菜，模型只能从零编（GENERATE_FRESH）。
     * 与 ctxPrecision 是一对：一个看"参考有没有用"，一个看"用户的东西有没有被覆盖"。
     */
    private BigDecimal ctxRecall;

    /** 营养来源：1-AI估算，2-本地计算 */
    private Integer nutritionSource;

    /** 失败原因 */
    private String errorMsg;

    private LocalDateTime createTime;
}
