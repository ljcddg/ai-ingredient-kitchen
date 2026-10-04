package com.apesource.dishservice.recommend;

import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.mapper.RecommendMapper;
import com.apesource.dishservice.recommend.job.DishScoreJob;
import com.apesource.dishservice.recommend.job.RankPromoteJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 冷启动的管理端接口 —— 主要为演示服务。
 *
 * <p>为什么必须有这些：晋级和算分都是每天凌晨跑的定时任务，
 * 答辩现场不可能等到三点。所以把两段逻辑各开一个手动触发口子，
 * <b>和定时任务调用的是同一个方法</b>，不存在"演示走一套、线上走另一套"的问题。
 *
 * <p>路径上的 {@code /admin/} 由网关的前缀规则鉴权，和现有
 * {@code /ai/admin/dish/{id}/unpublish} 是同一套。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RecommendAdminController {

    private final RecommendMapper recommendMapper;
    private final RankPromoteJob rankPromoteJob;
    private final DishScoreJob dishScoreJob;

    /**
     * 模拟推流：直接给 push_count 加 N。
     *
     * <p>没有它的话，演示一次晋级要手动刷十几次推荐页 —— 现场根本来不及。
     */
    @PostMapping("/ai/admin/dish/{dishId}/simulate-push")
    public Result<Map<String, Object>> simulatePush(@PathVariable Long dishId,
                                                    @RequestParam(name = "times", defaultValue = "10") int times) {
        if (times <= 0 || times > 10000) {
            return Result.fail("次数必须在 1 ~ 10000 之间");
        }
        int n = recommendMapper.addPushCountTimes(dishId, times);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dishId", dishId);
        data.put("added", times);
        data.put("updated", n);
        return Result.success(data);
    }

    /** 手动触发晋级（与 03:00 定时任务同一段逻辑） */
    @PostMapping("/ai/admin/rank/promote")
    public Result<Map<String, Object>> promote() {
        int n = rankPromoteJob.promote();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("promoted", n);
        return Result.success(data);
    }

    /** 手动触发算分（与 03:30 定时任务同一段逻辑） */
    @PostMapping("/ai/admin/rank/calc-score")
    public Result<Map<String, Object>> calcScore() {
        int n = dishScoreJob.recalc();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recalculated", n);
        return Result.success(data);
    }
}
