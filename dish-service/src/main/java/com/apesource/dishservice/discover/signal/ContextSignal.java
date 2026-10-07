package com.apesource.dishservice.discover.signal;

import com.apesource.dishservice.discover.DiscoverFilter;
import com.apesource.dishservice.es.DishDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 情境信号 —— 刻意做浅。
 *
 * <p>它<b>先过滤、后打分</b>，两件事都在这里完成：
 *
 * <ol>
 *   <li><b>过滤</b>：不满足硬性条件的直接出局，打分再高也不进列表。</li>
 *   <li><b>打分</b>：通过过滤的菜按"裕度"给个小权重分——
 *       同样满足"30 分钟内"，15 分钟的比 28 分钟的更该排前面。</li>
 * </ol>
 *
 * <p><b>为什么不做深：</b>实测数据撑不起复杂模型。112 道菜里
 * 75 道是 2 人份、91 道不辣，维度和取值都高度集中，
 * 为它设计一套精细算法的收益接近于零。这里就是个筛子，别加戏。
 */
@Slf4j
@Service
public class ContextSignal {

    /** 没设条件时的中性分 */
    private static final double NEUTRAL = 0.5;

    /**
     * 情境结论。
     *
     * @param pass  是否通过硬过滤
     * @param score 裕度分，0~1，仅在 {@code pass} 为 true 时有意义
     */
    public record ContextResult(boolean pass, double score) {

        public static final ContextResult REJECTED = new ContextResult(false, 0d);
    }

    public ContextResult evaluate(DishDoc doc, DiscoverFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return new ContextResult(true, NEUTRAL);
        }

        if (filter.rejects(doc.getCookTimeMin())
                || filter.tooHard(doc.getDifficulty())
                || filter.wrongServing(doc.getBaseServing())) {
            return ContextResult.REJECTED;
        }

        double timeFit = fitness(doc.getCookTimeMin(), filter.maxCookTimeMin());
        double levelFit = fitness(doc.getDifficulty(), filter.maxDifficulty());
        return new ContextResult(true, (timeFit + levelFit) / 2d);
    }

    /**
     * 离上限还有多少余量。
     *
     * <p>越宽裕分越高：用户能接受 30 分钟时，10 分钟的菜（余量 2/3）
     * 优于 25 分钟的（余量 1/6）。这符合"快手菜优先"的直觉。
     *
     * <p>数据缺失（菜没填时长/难度）或用户没设上限时给中性分，
     * 别让缺失数据既加不了分也减不了分变成隐形惩罚。
     */
    private double fitness(Integer actual, Integer limit) {
        if (limit == null || actual == null || limit <= 0) {
            return NEUTRAL;
        }
        return Math.max(0d, Math.min(1d, 1d - (actual / (double) limit)));
    }
}
