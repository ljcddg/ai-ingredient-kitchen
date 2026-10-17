package com.apesource.dishservice.retrieval;

/**
 * 检索三档决策（docs/thesis/RAG与功能扩展方案.md §3.2）。
 *
 * <p>原来的 {@code directReturn} 只有两个结果：调模型 / 不调模型。它缺一个中间态 ——
 * 「库里有参考，但不够好到能直接给」，这时候最划算的做法是<b>带着参考去生成</b>，
 * 而不是当作没有命中一样裸生成。三档把这件事说清楚，也把论文里
 * 「检索优先到底省了多少调用」变成可统计的量。
 *
 * <table>
 *   <caption>三档口径</caption>
 *   <tr><th>档位</th><th>触发条件</th><th>动作</th></tr>
 *   <tr><td>DIRECT</td><td>缺料为 0 且质量达标（等价满足率 1.0）</td><td>直接返回库内菜品，不调模型</td></tr>
 *   <tr><td>RAG_GENERATE</td><td>有可用候选且满足率 ≥ 0.4</td><td>检索 + 生成（注入参考菜谱）</td></tr>
 *   <tr><td>GENERATE_FRESH</td><td>无可用候选，或满足率 &lt; 0.4</td><td>裸生成（无参考），保底可用</td></tr>
 * </table>
 */
public enum RetrievalDecision {

    /** 直出：库里有现成的，且质量达标 */
    DIRECT("库里有现成的，直接用"),

    /** 带参考生成：找到了相似菜谱，但还不够格直接给 */
    RAG_GENERATE("参考了库里的相似菜，现做一道"),

    /** 裸生成：库里没有可参考的，完全靠模型 */
    GENERATE_FRESH("库里没找到合适的，全新生成");

    /** 满足率门槛：低于它说明候选和用户手上的食材关系太弱，参考价值不大 */
    public static final double RAG_MIN_SATISFACTION = 0.4;

    /** 直出门槛（与 DishSearchService 的质量判定配合，仅用于文档口径与统计） */
    public static final double DIRECT_MIN_SATISFACTION = 0.8;

    private final String hint;

    RetrievalDecision(String hint) {
        this.hint = hint;
    }

    /** 给前端看的一句话说明 */
    public String hint() {
        return hint;
    }

    /**
     * 判定档位。
     *
     * @param directEligible  是否已判定可以直出（缺料为 0 且质量达标）
     * @param satisfaction    最优候选的主料满足率，没有候选时传 0
     * @param usableCandidates 硬约束过滤后还剩几条可用候选
     */
    public static RetrievalDecision decide(boolean directEligible, double satisfaction,
                                           int usableCandidates) {
        if (directEligible) {
            return DIRECT;
        }
        if (usableCandidates > 0 && satisfaction >= RAG_MIN_SATISFACTION) {
            return RAG_GENERATE;
        }
        return GENERATE_FRESH;
    }
}
