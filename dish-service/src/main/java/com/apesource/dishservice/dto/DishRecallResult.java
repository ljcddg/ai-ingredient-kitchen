package com.apesource.dishservice.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 召回结果。
 *
 * <p>{@link #directReturn} 是这套设计里最关键的一个字段，它回答的是：
 * <b>这次请求到底能不能用库里的现成菜品，还是必须去调 AI 生成？</b>
 *
 * <p>调用方的动作因此变得很确定：
 * <pre>
 * if (result.isDirectReturn()) {
 *     // 直接用 best，不花钱、不等待
 * } else {
 *     // 走 AI 生成（异步），同时可以把 candidates 作为「先看看这些」的兜底展示
 * }
 * </pre>
 */
@Data
public class DishRecallResult {

    /** 是否命中已有菜品：true = 直接用库里的菜，不需要调 AI */
    private boolean directReturn;

    /**
     * 三档检索决策：{@code DIRECT} / {@code RAG_GENERATE} / {@code GENERATE_FRESH}。
     *
     * <p>它是 {@link #directReturn} 的超集：<b>directReturn 为 true 必然 DIRECT</b>，
     * 反过来不成立 —— RAG_GENERATE 也是"要生成"，但它带着库里的参考去生成，
     * 比裸生成更不容易编。前端用它决定提示语（"库里有现成的" / "参考了相似菜" / "全新生成"）。
     */
    private String decision;

    /** 决策的人话说明，直接可展示 */
    private String decisionHint;

    /**
     * 未直出的原因。directReturn=false 时给出，用于前端提示与线上排查。
     * 例：「最接近的『番茄炒蛋』只覆盖 2/3 种食材」
     */
    private String missReason;

    /** 用户所选食材数 */
    private int requestedIngredientCount;

    /** 精排后的候选总数 */
    private int total;

    /** 当前页码 */
    private int page;

    /** 每页条数 */
    private int size;

    /** 最优候选（直接给出，省得前端自己从列表头取） */
    private DishItem best;

    /** 当前页候选列表 */
    private List<DishItem> candidates = new ArrayList<>();

    /**
     * 检索引擎是否可用。
     * false 表示 ES 连不上、走了降级（或降级也没数据），
     * 前端应提示「推荐结果可能不完整」而不是直接报错。
     */
    private boolean searchEngineAvailable = true;
}
