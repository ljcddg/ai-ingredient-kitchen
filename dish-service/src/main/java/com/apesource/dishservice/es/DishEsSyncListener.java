package com.apesource.dishservice.es;

import com.apesource.dishservice.event.DishPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 把「发布」这件事和「同步索引」这件事接起来。
 *
 * <p>三条触发路径，覆盖了索引可能落后的所有情况：
 * <ol>
 *   <li><b>启动时</b>检查索引在不在，不在就按 mapping 建出来</li>
 *   <li><b>发布后</b>（增量）靠事务事件立刻同步一条 —— 这是主路径</li>
 *   <li><b>每天凌晨</b>（全量）兜底一次 —— 增量漏掉的（进程崩溃、ES 短时不可用、
 *       有人直接改库）都由它补齐</li>
 * </ol>
 *
 * <p>为什么增量同步用 {@code @TransactionalEventListener} 而不是普通的 {@code @EventListener}：
 * 普通监听器在事务<b>提交前</b>就会被调用，如果后面事务回滚了，
 * 索引里就留下一条库里根本不存在的菜 —— 用户能搜到、点进去报"菜品不存在"。
 * AFTER_COMMIT 保证只在数据真正落定之后才动作。
 *
 * <p>反过来，也因此有一个必须记住的前提：<b>发布事件的那个方法必须在事务里</b>，
 * 否则 AFTER_COMMIT 监听器会静默不执行（不报错，只是什么都不发生）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DishEsSyncListener {

    private final DishEsSyncService syncService;

    private final DishIndexWriter writer;

    /** 全量重建的开关，默认开。临时关掉可以避免调试期间被定时任务打断 */
    @Value("${es.reindex.enabled:true}")
    private boolean reindexEnabled;

    /**
     * 应用启动后确认索引可用。
     *
     * <p>用 ApplicationReadyEvent 而不是 @PostConstruct：这时候 Web 容器已经起来了，
     * 建索引慢一点也不会拖慢健康检查；而且 ES 没启动时这里只是打一条 warn，
     * 不影响本服务的启动（召回本来就有降级路径）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void prepareIndex() {
        if (writer.ensureIndex()) {
            log.info("ES 索引检查通过，菜品召回可用");
        } else {
            log.warn("ES 索引当前不可用；召回会降级返回空结果，"
                    + "生成/详情/收藏/评分等功能不受影响");
        }
    }

    /** 增量：发布（或下架）之后同步这一条 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDishPublished(DishPublishedEvent event) {
        log.info("收到发布事件，开始同步索引：dishId={}", event.dishId());

        // 这里刻意同步执行而不是 @Async：本地 ES、单条写入是毫秒级，
        // 引入异步反而要多考虑线程池与"任务丢了怎么办"。
        // 如果将来 ES 变远、写入变慢，就该换成丢消息队列（RocketMQ）而不是简单加点 @Async。
        boolean ok = syncService.syncOne(event.dishId());
        if (!ok) {
            log.warn("发布后同步索引失败 dishId={}，等待定时全量补齐", event.dishId());
        }
    }

    /**
     * 全量兜底。默认每天 04:00。
     *
     * <p><b>多实例部署时这里会重复执行</b>（每个实例都会跑一次）。
     * 目前是无害的（写入幂等，只是白跑几遍），但如果菜的数量上来了、
     * 重建耗时超过调度间隔，就该加分布式锁或者换成专门的调度中心
     * （XXL-Job / ElasticJob）来保证只有一个实例执行。
     */
    @Scheduled(cron = "${es.reindex.cron:0 0 4 * * *}")
    public void scheduledReindex() {
        if (!reindexEnabled) {
            log.debug("全量重建已关闭（es.reindex.enabled=false），跳过");
            return;
        }
        log.info("开始定时全量重建菜品索引");
        try {
            syncService.reindexAll();
        } catch (Exception e) {
            // 定时任务里的异常必须自己兜住：抛出去会被调度器吞掉，
            // 而且不影响下一次调度，很容易长期无人发现
            log.error("定时全量重建失败", e);
        }
    }
}
