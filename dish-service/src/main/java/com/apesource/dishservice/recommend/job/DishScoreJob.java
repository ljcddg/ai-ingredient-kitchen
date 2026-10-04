package com.apesource.dishservice.recommend.job;

import com.apesource.dishservice.mapper.RecommendMapper;
import com.apesource.dishservice.recommend.RecommendProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日重算综合得分。
 *
 * <p>公式（详见 {@code docs/cold-start-design.md} §3.2）：
 * <pre>
 *   互动率     = LEAST(1, (赞×1 + 藏×2 + 做×3) / 浏览 / 基准)
 *   置信系数   = LEAST(1, 评分人数 / 评分人数基准)
 *   dish_score = (平均分/5 × 0.6 × 置信系数 + 互动率 × 0.4) × 100
 * </pre>
 *
 * <p><b>只算 rank_level = 2 的菜。</b>推荐池里的菜还没被验证过，
 * 算出来的分也不该拿去排序 —— 它们连上榜资格都还没有。
 *
 * <p>排在晋级任务（03:00）之后半小时，保证当天新晋级的菜也能立刻拿到分数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DishScoreJob {

    private final RecommendMapper recommendMapper;
    private final RecommendProperties properties;

    /** 每天 03:30 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void scheduled() {
        recalc();
    }

    /**
     * 执行一次算分。
     *
     * @return 参与计算的菜品数
     */
    public int recalc() {
        RecommendProperties.Score s = properties.getScore();
        int n = recommendMapper.recalcDishScore(s.getMinScoreCount(), s.getInteractionBaseline());
        log.info("综合得分重算：{} 道菜（评分人数基准 {}，互动基准 {}）",
                n, s.getMinScoreCount(), s.getInteractionBaseline());
        return n;
    }
}
