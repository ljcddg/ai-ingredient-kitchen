package com.apesource.dishservice.ai;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.entity.AiGenerateTask;
import com.apesource.dishservice.es.DishSearchRepository;
import com.apesource.dishservice.mapper.AiGenerateTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 降级模式守卫：检索引擎不可用时，管住生成入口。
 *
 * <p>说明文档第四节的要求是"ES 故障时向用户说明检索暂不可用，允许有限的数据库检索或
 * <b>受配额控制的生成入口</b>，避免检索故障把全部请求转移到本地模型"。
 *
 * <p>为什么这件事重要：检索挂掉时，页面上"没有合适的菜"会把人推向"让 AI 现做"。
 * 而本地只有一个模型、队列并发只有 2，单次生成几十秒到几分钟 ——
 * 不限流的话，一次 ES 抖动就能把生成队列堵住几十分钟，
 * 而且用户等到的还可能是失败（本地模型撑不住这么多请求）。
 *
 * <p>配额按<b>用户 + 小时</b>算，用的是任务表里的创建时间，不额外维护计数器：
 * 少一份需要同步的状态，就少一种不一致的可能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DegradedModeGuard {

    private final DishSearchRepository searchRepository;

    private final AiGenerateTaskMapper taskMapper;

    private final GenerateProperties properties;

    /**
     * 当前是否处于降级（检索不可用）状态。
     *
     * <p>探测本身报错也按"不可用"处理 —— 拿不准的时候，保守一点不会错。
     */
    public boolean degraded() {
        try {
            return !searchRepository.ping();
        } catch (Exception e) {
            log.debug("检索引擎可用性探测失败，按不可用处理：{}", e.getMessage());
            return true;
        }
    }

    /**
     * 生成入口的配额检查；<b>检索正常时直接放行</b>，不做任何额外限制。
     *
     * @throws BusinessException 降级期间超出配额
     */
    public void checkGenerationQuota(long userId) {
        if (!degraded()) {
            return;
        }
        int limit = Math.max(0, properties.getDegradedQuotaPerHour());
        long used = taskMapper.selectCount(Wrappers.<AiGenerateTask>lambdaQuery()
                .eq(AiGenerateTask::getUserId, userId)
                .ge(AiGenerateTask::getCreateTime, LocalDateTime.now().minusHours(1)));

        if (used >= limit) {
            // 提示里要说明"为什么被限"和"还能做什么"，否则用户只会觉得功能坏了
            throw new BusinessException("检索引擎暂时不可用，生成入口已限流（每小时 "
                    + limit + " 次，本小时已用 " + used + " 次）。"
                    + "可以先刷新页面重试，或稍后再来 —— 检索恢复后不再限流");
        }
        log.warn("检索引擎不可用，用户 {} 走降级生成入口（本小时第 {} 次，上限 {}）",
                userId, used + 1, limit);
    }
}
