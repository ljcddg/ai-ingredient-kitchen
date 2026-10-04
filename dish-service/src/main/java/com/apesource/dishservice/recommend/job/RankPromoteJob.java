package com.apesource.dishservice.recommend.job;

import com.apesource.dishservice.mapper.RecommendMapper;
import com.apesource.dishservice.recommend.RecommendProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日晋级：把推荐池里"被真实流量验证过"的菜升进排行榜。
 *
 * <p>条件三条同时满足才晋级（阈值都从 Nacos 读）：
 * <ol>
 *   <li>{@code push_count >= minPushCount} —— 推得够多，样本量够</li>
 *   <li>{@code view_count >= push_count * minViewRate} —— 查看率达标</li>
 *   <li>{@code view_count >= minViewCount} —— 绝对量兜底，防止小样本偶然达标</li>
 * </ol>
 *
 * <p><b>晋级只看查看率，不看评分。</b>这是刻意的：评分是"看过的人觉得好不好"，
 * 属于内容质量；查看率是"路人愿不愿意点开"，属于吸引力。
 * 冷启动阶段先解决"有没有人愿意看"，评分留给排行榜排序去体现。
 *
 * <p>{@link #promote()} 单独抽出来，管理端手动触发和定时任务跑的是同一段逻辑 ——
 * 分成两份的话，演示时触发的和半夜自动跑的结果可能不一致，那就说不清了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankPromoteJob {

    private final RecommendMapper recommendMapper;
    private final RecommendProperties properties;

    /** 每天 03:00 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduled() {
        promote();
    }

    /**
     * 执行一次晋级。
     *
     * @return 本次晋级的菜品数
     */
    public int promote() {
        RecommendProperties.Promote p = properties.getPromote();
        int n = recommendMapper.promoteToRank(p.getMinPushCount(), p.getMinViewCount(), p.getMinViewRate());
        if (n > 0) {
            log.info("冷启动晋级：{} 道菜升入排行榜（推流≥{} 且 查看率≥{} 且 查看数≥{}）",
                    n, p.getMinPushCount(), p.getMinViewRate(), p.getMinViewCount());
        }
        return n;
    }
}
