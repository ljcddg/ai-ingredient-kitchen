package com.apesource.dishservice.es;

import com.apesource.dishservice.entity.EsSyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 索引同步的补偿执行器。
 *
 * <p>两条触发路径：
 * <ol>
 *   <li><b>发布之后立刻试一次</b>（{@link #syncNow}，由 {@link DishEsSyncListener} 在事务提交后调用）——
 *       保证"发完马上能搜到"的体验不变；</li>
 *   <li><b>定时扫待办</b>（{@link #retryPending}）—— 定时尝试失败的那些按退避重试，
 *       这是"最终一致"真正兜底的地方。</li>
 * </ol>
 *
 * <p>为什么不让请求线程直接重试：用户点一下"发布"，不该因为 ES 抖了三下就等 15 秒。
 * 失败就交给后台，用户那边该成功就成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsSyncWorker {

    /** 一轮最多处理多少条：避免某次积压把单个调度周期拖成几分钟 */
    private static final int BATCH_SIZE = 50;

    private final EsSyncTaskService taskService;

    private final DishEsSyncService syncService;

    /**
     * 处理到期待办。
     *
     * <p>单个任务抛异常不影响同批其他任务 —— 一条坏数据不该把整轮补偿带停。
     */
    @Scheduled(initialDelayString = "${es.sync.retry-interval-ms:10000}",
            fixedDelayString = "${es.sync.retry-interval-ms:10000}")
    public void retryPending() {
        List<EsSyncTask> due;
        try {
            due = taskService.dueTasks(BATCH_SIZE);
        } catch (Exception e) {
            // 连库都查不了（比如 MySQL 抖动），下一轮再说
            log.warn("读取索引同步待办失败：{}", e.getMessage());
            return;
        }
        if (due.isEmpty()) {
            log.debug("索引同步待办：没有到期的任务");
            return;
        }

        int ok = 0;
        for (EsSyncTask task : due) {
            if (syncNow(task)) {
                ok++;
            }
        }
        log.info("索引同步补偿：本轮处理 {} 条（成功 {}，其余已安排退避重试）", due.size(), ok);
    }

    /**
     * 全量重建（定时兜底与手动入口共用）。
     *
     * <p>说明文档要求"每日全量重建保留为核对手段，不作为唯一恢复方式" ——
     * 现在的唯一恢复方式是待办重试，全量只用来核对两边到底差了多少。
     */
    public DishEsSyncService.ReindexReport reindexAll() {
        return syncService.reindexAll();
    }

    /**
     * 同步一条待办，并按结果更新它的状态。
     *
     * @return true 表示同步成功
     */
    public boolean syncNow(EsSyncTask task) {
        try {
            boolean ok = syncService.syncOne(task.getDishId());
            if (ok) {
                taskService.markDone(task.getId());
                return true;
            }
            // syncOne 返回 false 表示"ES 明确拒绝了这次写入"（连不上、mapping 冲突等），
            // 它自己已经把原因打进日志了，这里记一句可读的
            taskService.markFailed(task, "ES 写入未成功（不可用或被拒绝），详见 es 包日志");
            return false;
        } catch (Exception e) {
            taskService.markFailed(task, e.getMessage());
            return false;
        }
    }
}
