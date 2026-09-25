package com.apesource.dishservice.ai;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 菜品生成任务的内存调度队列。
 *
 * <h3>为什么不用现成的轮子</h3>
 * <table>
 *   <tr><th>方案</th><th>为什么不行</th></tr>
 *   <tr><td>{@code ThreadPoolExecutor} + 阻塞队列</td>
 *       <td>任务入队后<b>无法调整顺序</b>；而且 {@code execute()} 是立即提交，
 *           只在忙时缓冲，拿不到稳定的"排队位置"</td></tr>
 *   <tr><td>{@code PriorityBlockingQueue}</td>
 *       <td>只在<b>插入时</b>按优先级排序，插入后无法重新排序 ——
 *           与"用户改顺序"直接冲突</td></tr>
 *   <tr><td>MQ（RocketMQ 等）</td>
 *       <td>消息躺在 broker 里，改顺序/取消非常别扭，还得额外维护一份"我排第几"</td></tr>
 * </table>
 *
 * <h3>调度模型</h3>
 * <pre>
 *   submit → waiting（有序 List，可任意重排）
 *                 │  调度线程：waiting 非空 && running &lt; 并发度
 *                 ▼
 *             取列表第一个 → running（执行中）→ worker 线程调 AI → 出队
 * </pre>
 *
 * <h3>两条设计铁律</h3>
 * <ol>
 *   <li><b>调度线程绝不自己执行任务</b> —— 否则它跑一个 10 秒的生成时没人接新任务，
 *       用户提交后连"排队中"的状态都拿不到</li>
 *   <li><b>所有对 waiting / running 的读写都在同一把锁下</b> ——
 *       队列操作频率极低（人手点几下），锁竞争可以忽略；
 *       换来的是"顺序"这件事永远只有一处真相</li>
 * </ol>
 *
 * <p>本类<b>不碰数据库、不认识 Spring AI</b>，是纯粹的调度组件。
 * 状态落库由 {@link DishTaskExecutor} 的实现方负责。
 */
@Slf4j
@Component
public class DishTaskQueue {

    /** 等待中的任务。用有序 List 而不是队列，就是因为要支持「重排」 */
    private final List<TaskHandle> waiting = new ArrayList<>();

    /** 正在执行的任务，size 不超过并发度 */
    private final Map<Long, TaskHandle> running = new LinkedHashMap<>();

    /** 保护 waiting / running 的锁 */
    private final Object lock = new Object();

    private final QueueProperties properties;
    private final DishTaskExecutor taskExecutor;

    /** 真正跑 AI 调用的线程池，与调度线程分开 */
    private final ExecutorService workerPool;

    /** 调度线程：只负责"下一个轮到谁"，不做具体工作 */
    private final Thread dispatcher;

    public DishTaskQueue(QueueProperties properties, DishTaskExecutor taskExecutor) {
        this.properties = properties;
        this.taskExecutor = taskExecutor;

        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable, "dish-gen-worker-" + seq.incrementAndGet());
            // 守护线程：不阻止 JVM 退出
            t.setDaemon(true);
            return t;
        };
        this.workerPool = Executors.newFixedThreadPool(Math.max(1, properties.getConcurrency()), factory);

        this.dispatcher = new Thread(this::dispatchLoop, "dish-queue-dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();

        log.info("生成队列已启动：并发度={}，单用户排队上限={}",
                properties.getConcurrency(), properties.getMaxPerUser());
    }

    // ------------------------------------------------------------------ 调度

    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            TaskHandle next;
            synchronized (lock) {
                while (waiting.isEmpty() || running.size() >= properties.getConcurrency()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                next = waiting.remove(0);
                running.put(next.taskId, next);
            }
            // 交给 worker 执行 —— 调度线程自己绝不跑任务（见类注释铁律 1）
            next.future = workerPool.submit(() -> runOne(next));
        }
    }

    private void runOne(TaskHandle handle) {
        long waitedMs = System.currentTimeMillis() - handle.enqueueAt;
        try {
            if (handle.cancelled.get()) {
                log.info("任务 {} 在开始执行前已被取消（已排队 {} ms），跳过", handle.taskId, waitedMs);
                return;
            }
            log.info("任务 {} 开始执行（排队等待 {} ms）", handle.taskId, waitedMs);
            taskExecutor.execute(handle.taskId);
        } catch (Throwable e) {
            // 兜住一切异常：worker 线程绝不能被业务异常带走，
            // 否则线程池会不断补新线程，最后静默地把并发能力耗光。
            log.error("任务 {} 执行时抛出未捕获异常，已在队列层兜住", handle.taskId, e);
        } finally {
            synchronized (lock) {
                running.remove(handle.taskId);
                // 腾出名额，叫醒调度线程
                lock.notifyAll();
            }
        }
    }

    // ------------------------------------------------------------------ 对外操作

    /**
     * 提交任务入队（追加到队尾）。
     *
     * <p>不做去重（同 signature 的单飞是 Service 层的职责）——
     * 队列只管"来了就排队"。
     */
    public EnqueueResult submit(long taskId, long userId) {
        synchronized (lock) {
            if (contains(taskId)) {
                return EnqueueResult.rejected("该任务已在队列中", waiting.size());
            }
            int mine = 0;
            for (TaskHandle h : waiting) {
                if (h.userId == userId) {
                    mine++;
                }
            }
            for (TaskHandle h : running.values()) {
                if (h.userId == userId) {
                    mine++;
                }
            }
            if (mine >= properties.getMaxPerUser()) {
                return EnqueueResult.rejected(
                        "你已有 " + mine + " 个任务在排队或生成中，最多同时 " + properties.getMaxPerUser() + " 个",
                        waiting.size());
            }

            TaskHandle handle = new TaskHandle(taskId, userId, System.currentTimeMillis());
            waiting.add(handle);
            lock.notifyAll();
            log.info("任务 {} 入队，位置 {}/{}", taskId, waiting.size(), waiting.size());
            return EnqueueResult.ok(waiting.size(), waiting.size());
        }
    }

    /**
     * 取消任务。<b>只允许取消自己的任务</b>（队列权限规则）。
     */
    public CancelOutcome cancel(long taskId, long userId) {
        synchronized (lock) {
            // ① 还在排队 —— 直接移除，这种取消是真省资源
            for (int i = 0; i < waiting.size(); i++) {
                TaskHandle h = waiting.get(i);
                if (h.taskId == taskId) {
                    if (h.userId != userId) {
                        return CancelOutcome.NOT_OWNER;
                    }
                    waiting.remove(i);
                    h.cancelled.set(true);
                    lock.notifyAll();
                    log.info("任务 {} 在排队中被取消（原位置 {}）", taskId, i + 1);
                    return CancelOutcome.CANCELLED_WAITING;
                }
            }

            // ② 正在执行 —— 逻辑取消
            TaskHandle h = running.get(taskId);
            if (h != null) {
                if (h.userId != userId) {
                    return CancelOutcome.NOT_OWNER;
                }
                h.cancelled.set(true);
                Future<?> f = h.future;
                if (f != null) {
                    f.cancel(true);
                }
                // 这里必须手动从 running 移除：
                // 如果任务已经提交给线程池、但 worker 还没取到就 cancel 了，
                // runOne() 根本不会执行，它的 finally 也就不会清理 running ——
                // 那样并发名额会被永久占住，队列直接卡死。
                // 已经在跑的情况下，这里移除是安全的（runOne 的 finally 再 remove 一次是幂等的）。
                running.remove(taskId);
                lock.notifyAll();
                log.info("任务 {} 在执行中被取消（逻辑取消：线程已中断，但底层 HTTP 请求可能仍会跑完）", taskId);
                return CancelOutcome.CANCELLED_RUNNING;
            }

            return CancelOutcome.NOT_FOUND;
        }
    }

    /**
     * 调整排队顺序。
     *
     * <p><b>语义（重要）</b>：这个方法只能调整"<b>属于调用者的那些任务</b>之间的相对顺序"，
     * 别人的任务位置<b>完全不动</b>。
     *
     * <p>实现方式是"把重排后的我的任务，按原下标逐个放回去"，而不是整表插入挪动 ——
     * 后者会把中间别人的任务挤到后面，等于变相插队。
     *
     * @param newOrderAmongMine 想排到"我的任务列表"里的第几位（从 1 开始）
     * @return true 表示调整成功（含"本来就在这个位置"）；false 表示该任务不在等待队列里
     */
    public boolean move(long taskId, long userId, int newOrderAmongMine) {
        synchronized (lock) {
            List<Integer> myIndexes = new ArrayList<>();
            List<TaskHandle> myHandles = new ArrayList<>();
            for (int i = 0; i < waiting.size(); i++) {
                TaskHandle h = waiting.get(i);
                if (h.userId == userId) {
                    myIndexes.add(i);
                    myHandles.add(h);
                }
            }

            int current = -1;
            for (int i = 0; i < myHandles.size(); i++) {
                if (myHandles.get(i).taskId == taskId) {
                    current = i;
                    break;
                }
            }
            if (current < 0) {
                // 不在等待队列里：可能正在执行、已完成，或本来就不属于这个用户
                return false;
            }

            int target = Math.max(0, Math.min(myHandles.size() - 1, newOrderAmongMine - 1));
            if (target == current) {
                return true;
            }

            TaskHandle moving = myHandles.remove(current);
            myHandles.add(target, moving);

            // 按原下标放回 —— 别人的任务一位都不动
            for (int i = 0; i < myIndexes.size(); i++) {
                waiting.set(myIndexes.get(i), myHandles.get(i));
            }
            log.info("任务 {} 调整为「我的第 {} 个」", taskId, target + 1);
            return true;
        }
    }

    /**
     * 查询排队位置。
     *
     * @return &gt;0 表示在等待队列中的位置（1 就是下一个）；0 表示正在执行；-1 表示不在队列里
     */
    public int position(long taskId) {
        synchronized (lock) {
            for (int i = 0; i < waiting.size(); i++) {
                if (waiting.get(i).taskId == taskId) {
                    return i + 1;
                }
            }
            return running.containsKey(taskId) ? 0 : -1;
        }
    }

    public int waitingSize() {
        synchronized (lock) {
            return waiting.size();
        }
    }

    public int runningSize() {
        synchronized (lock) {
            return running.size();
        }
    }

    /** 队列快照 —— 给调试接口和监控用，别拿它做业务判断 */
    public Map<String, Object> snapshot() {
        synchronized (lock) {
            List<Map<String, Object>> waitingView = new ArrayList<>();
            for (int i = 0; i < waiting.size(); i++) {
                TaskHandle h = waiting.get(i);
                waitingView.add(Map.of(
                        "position", i + 1,
                        "taskId", String.valueOf(h.taskId),
                        "userId", String.valueOf(h.userId),
                        "waitedMs", System.currentTimeMillis() - h.enqueueAt));
            }
            return Map.of(
                    "concurrency", properties.getConcurrency(),
                    "runningCount", running.size(),
                    "waitingCount", waiting.size(),
                    "waiting", waitingView);
        }
    }

    private boolean contains(long taskId) {
        for (TaskHandle h : waiting) {
            if (h.taskId == taskId) {
                return true;
            }
        }
        return running.containsKey(taskId);
    }

    @PreDestroy
    public void shutdown() {
        dispatcher.interrupt();
        workerPool.shutdownNow();
        try {
            workerPool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int dropped;
        synchronized (lock) {
            dropped = waiting.size();
            waiting.clear();
        }
        if (dropped > 0) {
            // 这些任务的 DB 记录会在下次启动的清扫里被标成 INTERRUPTED，
            // 队列这边不碰数据库，只如实报告丢了几个。
            log.warn("生成队列关闭：丢弃了 {} 个还在排队的任务（下次启动会被标记为已中断）", dropped);
        }
        log.info("生成队列已停止");
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 队列内的一条任务。做成不可变的值对象，只留"可变的那几个"用原子/volatile 包住。
     */
    private static final class TaskHandle {
        private final long taskId;
        private final long userId;
        /** 入队时间，用来算"用户到底等了多久" */
        private final long enqueueAt;
        /** 用户是否已取消 —— worker 真正开跑前会再看一眼，避免白跑一次 */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** 提交给线程池后拿到的句柄，取消时要靠它打中断 */
        private volatile Future<?> future;

        private TaskHandle(long taskId, long userId, long enqueueAt) {
            this.taskId = taskId;
            this.userId = userId;
            this.enqueueAt = enqueueAt;
        }
    }
}
