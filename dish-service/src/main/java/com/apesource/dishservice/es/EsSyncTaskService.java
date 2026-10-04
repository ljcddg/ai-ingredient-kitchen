package com.apesource.dishservice.es;

import com.apesource.dishservice.entity.EsSyncTask;
import com.apesource.dishservice.mapper.EsSyncTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引同步待办的存取。
 *
 * <p>它只负责"记下来、改状态、算退避"，不负责真正写 ES（那是 {@link EsSyncWorker} 的事）——
 * 分开的理由和队列与执行器分开是同一个：存取逻辑要能被单独测试，
 * 而"什么时候重试、退避多久"是最容易写错、也最需要测试的部分。
 *
 * <h3>退避策略</h3>
 * 失败后按 {@link #BACKOFF_SECONDS} 依次等待，用尽即置为
 * {@link EsSyncTask#STATUS_GIVEN_UP}（放弃）。为什么要有"放弃"这个终态：
 * 无限重试会把一条坏数据变成永久的日志噪音，而"放弃"是一次性告警 ——
 * 后者才会被人注意到。放弃之后靠 {@link #retryFailedNow()} 手动补偿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsSyncTaskService {

    /** 退避序列（秒） */
    private static final int[] BACKOFF_SECONDS = {5, 15, 45, 120};

    /** 最多尝试次数（含首次）：1 次立即 + 4 次退避重试 */
    public static final int MAX_ATTEMPTS = BACKOFF_SECONDS.length + 1;

    private static final int MAX_ERROR_LEN = 500;

    private final EsSyncTaskMapper taskMapper;

    /**
     * 登记"这道菜需要在事务提交后重新对一次索引"。
     *
     * <p><b>必须在业务事务里调用</b>（说明文档要求"事务内写入待同步记录"）：
     * 这样"改了库"和"记下要同步"是同生共死的 —— 事务回滚时待办也不会留下，
     * 提交了就一定留得下，不会出现"库改了但没人知道要同步"。
     *
     * <p>同一道菜只有一条待办（唯一键 {@code uk_dish}）：重复变更只刷新它。
     * 顺带把 attempts 清零 —— 新的变更值得重新给满重试次数。
     */
    public void enqueue(long dishId, LocalDateTime version) {
        LocalDateTime now = LocalDateTime.now();
        EsSyncTask exist = find(dishId);
        if (exist == null) {
            EsSyncTask fresh = new EsSyncTask();
            fresh.setDishId(dishId);
            fresh.setVersion(version);
            fresh.setStatus(EsSyncTask.STATUS_PENDING);
            fresh.setAttempts(0);
            fresh.setNextRetryAt(now);
            fresh.setCreateTime(now);
            fresh.setUpdateTime(now);
            try {
                taskMapper.insert(fresh);
                return;
            } catch (Exception e) {
                // 并发登记撞上唯一键：退化成"刷新已有那条"，不当作错误
                log.debug("同步待办已存在（并发登记），改为刷新：dishId={} - {}", dishId, e.getMessage());
                exist = find(dishId);
                if (exist == null) {
                    log.warn("登记索引同步待办失败：dishId={} - {}", dishId, e.getMessage());
                    return;
                }
            }
        }
        taskMapper.update(null, Wrappers.<EsSyncTask>lambdaUpdate()
                .eq(EsSyncTask::getId, exist.getId())
                .set(EsSyncTask::getVersion, version)
                .set(EsSyncTask::getStatus, EsSyncTask.STATUS_PENDING)
                .set(EsSyncTask::getAttempts, 0)
                .set(EsSyncTask::getNextRetryAt, now)
                .set(EsSyncTask::getLastError, null));
    }

    public EsSyncTask find(long dishId) {
        return taskMapper.selectOne(Wrappers.<EsSyncTask>lambdaQuery()
                .eq(EsSyncTask::getDishId, dishId)
                .last("LIMIT 1"));
    }

    /** 到点的待办：待同步或失败待重试，且下次执行时间已到 */
    public List<EsSyncTask> dueTasks(int limit) {
        return taskMapper.selectList(Wrappers.<EsSyncTask>lambdaQuery()
                .in(EsSyncTask::getStatus, EsSyncTask.STATUS_PENDING, EsSyncTask.STATUS_FAILED)
                .le(EsSyncTask::getNextRetryAt, LocalDateTime.now())
                .orderByAsc(EsSyncTask::getNextRetryAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    public void markDone(long id) {
        // 成功也要把 attempts +1：这一列是"试了几次"，不是"失败几次"。
        // 不这样做的话，一次成功完成的待办会显示"尝试 0 次"，事后看数据的人
        // 会以为是没跑过 —— 这是端到端验证里抓出来的（断言"尝试次数为 1"失败）。
        taskMapper.update(null, Wrappers.<EsSyncTask>lambdaUpdate()
                .eq(EsSyncTask::getId, id)
                .set(EsSyncTask::getStatus, EsSyncTask.STATUS_DONE)
                .set(EsSyncTask::getLastError, null)
                .setSql("attempts = attempts + 1"));
    }

    /**
     * 记一次失败并安排下次重试。
     *
     * @return true 表示还会重试；false 表示次数用尽、已放弃（该人工介入了）
     */
    public boolean markFailed(EsSyncTask task, String error) {
        int attempts = (task.getAttempts() == null ? 0 : task.getAttempts()) + 1;
        boolean giveUp = attempts >= MAX_ATTEMPTS;
        LocalDateTime next = giveUp
                ? LocalDateTime.now()
                : LocalDateTime.now().plusSeconds(
                        BACKOFF_SECONDS[Math.min(attempts - 1, BACKOFF_SECONDS.length - 1)]);

        taskMapper.update(null, Wrappers.<EsSyncTask>lambdaUpdate()
                .eq(EsSyncTask::getId, task.getId())
                .set(EsSyncTask::getAttempts, attempts)
                .set(EsSyncTask::getStatus,
                        giveUp ? EsSyncTask.STATUS_GIVEN_UP : EsSyncTask.STATUS_FAILED)
                .set(EsSyncTask::getNextRetryAt, next)
                .set(EsSyncTask::getLastError, truncate(error)));

        if (giveUp) {
            // 放弃才算真问题：打 error，人工补偿入口见 EsSyncController
            log.error("索引同步已放弃（尝试 {} 次）：dishId={}，最后一次错误：{}",
                    attempts, task.getDishId(), error);
        } else {
            log.warn("索引同步失败（第 {} 次）：dishId={}，{} 秒后重试 - {}",
                    attempts, task.getDishId(),
                    java.time.Duration.between(LocalDateTime.now(), next).getSeconds(), error);
        }
        return !giveUp;
    }

    /**
     * 手动补偿：把所有"失败待重试 / 已放弃"的待办重置为立刻可执行。
     *
     * <p>它是 ES 恢复之后的那个"按钮"：后台任务按退避自己也会重试，
     * 但放弃之后就不会了 —— 而"ES 挂了半小时"这种事，人知道它已经好了，
     * 不必等下一次调度，更不该让那些数据永久留在索引之外。
     *
     * @return 被重置的条数
     */
    public int retryFailedNow() {
        return taskMapper.update(null, Wrappers.<EsSyncTask>lambdaUpdate()
                .in(EsSyncTask::getStatus, EsSyncTask.STATUS_FAILED, EsSyncTask.STATUS_GIVEN_UP)
                .set(EsSyncTask::getStatus, EsSyncTask.STATUS_PENDING)
                .set(EsSyncTask::getAttempts, 0)
                .set(EsSyncTask::getNextRetryAt, LocalDateTime.now()));
    }

    /** 手动补偿：只重置某一道菜（排查单条数据时用） */
    public int retryOne(long dishId) {
        return taskMapper.update(null, Wrappers.<EsSyncTask>lambdaUpdate()
                .eq(EsSyncTask::getDishId, dishId)
                .in(EsSyncTask::getStatus, EsSyncTask.STATUS_FAILED, EsSyncTask.STATUS_GIVEN_UP)
                .set(EsSyncTask::getStatus, EsSyncTask.STATUS_PENDING)
                .set(EsSyncTask::getAttempts, 0)
                .set(EsSyncTask::getNextRetryAt, LocalDateTime.now()));
    }

    /** 待办清单（运维查看），按更新时间倒序 */
    public List<EsSyncTask> listByStatus(Integer status, int limit) {
        return taskMapper.selectList(Wrappers.<EsSyncTask>lambdaQuery()
                .eq(status != null, EsSyncTask::getStatus, status)
                .orderByDesc(EsSyncTask::getUpdateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    /** 各状态条数概览（挂在健康检查/运维接口上，一眼看出索引有没有积压） */
    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("pending", count(EsSyncTask.STATUS_PENDING));
        counts.put("done", count(EsSyncTask.STATUS_DONE));
        counts.put("failed", count(EsSyncTask.STATUS_FAILED));
        counts.put("givenUp", count(EsSyncTask.STATUS_GIVEN_UP));
        return counts;
    }

    private long count(int status) {
        return taskMapper.selectCount(Wrappers.<EsSyncTask>lambdaQuery()
                .eq(EsSyncTask::getStatus, status));
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_LEN ? text : text.substring(0, MAX_ERROR_LEN) + "...";
    }
}
