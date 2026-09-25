package com.apesource.dishservice.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 生成队列的单元测试。
 *
 * <p>刻意**不启动 Spring、不连数据库、不调 AI** —— 队列是纯调度组件，
 * 就应该能用纯单元测试把所有边界情况钉死。这也让测试跑得飞快。
 *
 * <p>用 {@link BlockingExecutor} 把任务卡在 {@code execute()} 里，
 * 这样才能稳定地观察"谁在排队、排第几"这种瞬时状态。
 */
@DisplayName("生成队列 DishTaskQueue")
class DishTaskQueueTest {

    private DishTaskQueue queue;

    /** 假执行器：默认卡住不返回，好让任务停在"执行中" */
    private static class BlockingExecutor implements DishTaskExecutor {
        final CountDownLatch gate = new CountDownLatch(1);
        final List<Long> started = new CopyOnWriteArrayList<>();
        volatile boolean block = true;

        @Override
        public void execute(long taskId) {
            started.add(taskId);
            if (block) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void releaseAndUnblock() {
            block = false;
            gate.countDown();
        }
    }

    private DishTaskQueue newQueue(int concurrency, int maxPerUser, DishTaskExecutor executor) {
        QueueProperties p = new QueueProperties();
        p.setConcurrency(concurrency);
        p.setMaxPerUser(maxPerUser);
        this.queue = new DishTaskQueue(p, executor);
        return this.queue;
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    /** 等待条件成立，最多 3 秒 */
    private static void await(Supplier<Boolean> condition, String message) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail(message);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> waitingView() {
        return (List<Map<String, Object>>) queue.snapshot().get("waiting");
    }

    /** 把等待队列渲染成 "taskId@userId,..." 便于断言顺序 */
    private String waitingOrder() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : waitingView()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(m.get("taskId")).append('@').append(m.get("userId"));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 基本入队

    @Test
    @DisplayName("入队返回排队位置；并发度为 1 时后续任务排在后面")
    void submitAndPosition() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 10, exec);

        assertTrue(q.submit(1L, 100L).accepted());
        await(() -> exec.started.contains(1L), "第一个任务应立刻开始执行");
        assertEquals(0, q.position(1L), "正在执行的任务 position 应为 0");

        assertEquals(1, q.submit(2L, 100L).position());
        assertEquals(2, q.submit(3L, 100L).position());
        assertEquals(2, q.waitingSize());
        assertEquals(1, q.runningSize());
    }

    // ---------------------------------------------------------------- 重排顺序（核心）

    @Test
    @DisplayName("重排只调整自己的任务，别人的位置纹丝不动")
    void moveKeepsOthersUntouched() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 10, exec);

        q.submit(1L, 100L);                                   // A：会被立刻取走执行
        await(() -> exec.started.contains(1L), "任务 1 应开始执行");

        q.submit(2L, 100L);   // A → waiting[0]
        q.submit(3L, 200L);   // B → waiting[1]
        q.submit(4L, 100L);   // A → waiting[2]
        q.submit(5L, 200L);   // B → waiting[3]

        assertEquals("2@100,3@200,4@100,5@200", waitingOrder(), "初始队列顺序");

        // A 把任务 4 提到「我的第 1 位」
        assertTrue(q.move(4L, 100L, 1));

        // 期望：A 的两个任务互换，B 的任务仍在原下标（1 和 3）
        assertEquals("4@100,3@200,2@100,5@200", waitingOrder(),
                "B 的任务位置必须完全不动，否则就是变相插队");

        // 再改回去，验证可逆
        assertTrue(q.move(4L, 100L, 2));
        assertEquals("2@100,3@200,4@100,5@200", waitingOrder());
    }

    @Test
    @DisplayName("不能调整别人的任务顺序（反例）")
    void moveOthersTaskRejected() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 10, exec);

        q.submit(1L, 100L);
        await(() -> exec.started.contains(1L), "任务 1 应开始执行");
        q.submit(2L, 200L);   // B 的任务

        assertFalse(q.move(2L, 100L, 1), "A 不应该能调整 B 的任务");
    }

    @Test
    @DisplayName("调整正在执行 / 不存在的任务返回 false")
    void moveNonWaitingRejected() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 10, exec);

        q.submit(1L, 100L);
        await(() -> exec.started.contains(1L), "任务 1 应开始执行");

        assertFalse(q.move(1L, 100L, 1), "正在执行的任务不在等待队列里，无法调序");
        assertFalse(q.move(999L, 100L, 1), "不存在的任务应返回 false");
    }

    // ---------------------------------------------------------------- 取消

    @Test
    @DisplayName("取消排队中的任务：真从队列移除")
    void cancelWaiting() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 10, exec);

        q.submit(1L, 100L);
        await(() -> exec.started.contains(1L), "任务 1 应开始执行");
        q.submit(2L, 100L);
        q.submit(3L, 100L);
        assertEquals(2, q.waitingSize());

        assertEquals(CancelOutcome.CANCELLED_WAITING, q.cancel(2L, 100L));
        assertEquals(1, q.waitingSize(), "取消后应从队列里消失");
        assertEquals(-1, q.position(2L), "已取消的任务查不到位置");
    }

    @Test
    @DisplayName("被取消的排队任务，轮到时不会真的执行")
    void cancelledTaskNeverRuns() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 10, exec);

        q.submit(1L, 100L);
        await(() -> exec.started.contains(1L), "任务 1 应开始执行");
        q.submit(2L, 100L);
        q.cancel(2L, 100L);

        // 放行任务 1，让队列空出来 —— 任务 2 不应该被执行
        exec.releaseAndUnblock();
        await(() -> q.runningSize() == 0 && q.waitingSize() == 0, "队列应清空");
        Thread.yield();
        assertFalse(exec.started.contains(2L), "已取消的排队任务绝不能真的跑起来");
    }

    @Test
    @DisplayName("取消正在执行的任务：返回 CANCELLED_RUNNING 并释放并发名额")
    void cancelRunningReleasesSlot() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 10, exec);

        q.submit(1L, 100L);
        await(() -> exec.started.contains(1L), "任务 1 应开始执行");
        q.submit(2L, 100L);

        assertEquals(CancelOutcome.CANCELLED_RUNNING, q.cancel(1L, 100L));

        // 名额必须立刻释放，否则队列会卡死在并发度上
        await(() -> exec.started.contains(2L), "取消执行中的任务后，下一个任务应立刻开始");
    }

    @Test
    @DisplayName("不能取消别人的任务（反例）")
    void cancelOthersTaskRejected() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 10, exec);

        q.submit(1L, 100L);
        await(() -> exec.started.contains(1L), "任务 1 应开始执行");
        q.submit(2L, 200L);

        assertEquals(CancelOutcome.NOT_OWNER, q.cancel(2L, 100L), "A 不能取消 B 的任务");
        assertEquals(1, q.waitingSize(), "越权取消不应真的生效");
    }

    @Test
    @DisplayName("取消不存在的任务返回 NOT_FOUND（反例）")
    void cancelUnknownTask() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 10, exec);
        assertEquals(CancelOutcome.NOT_FOUND, q.cancel(12345L, 100L));
    }

    // ---------------------------------------------------------------- 限流

    @Test
    @DisplayName("超过单用户上限时拒绝入队，且不影响其他用户")
    void maxPerUserLimit() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 2, exec);

        q.submit(1L, 100L);                                  // 执行中（占 1 个）
        await(() -> exec.started.contains(1L), "任务 1 应开始执行");
        assertTrue(q.submit(2L, 100L).accepted());           // 排队中（占第 2 个）

        EnqueueResult third = q.submit(3L, 100L);
        assertFalse(third.accepted(), "超过上限应被拒");
        assertTrue(third.reason().contains("最多同时"), "拒绝原因要说人话：" + third.reason());

        // 换个用户不受影响
        assertTrue(q.submit(4L, 200L).accepted(), "别人的额度不该被 A 占用");
    }

    @Test
    @DisplayName("重复提交同一个任务会被拒绝（幂等）")
    void duplicateSubmitRejected() {
        BlockingExecutor exec = new BlockingExecutor();
        DishTaskQueue q = newQueue(1, 10, exec);

        q.submit(1L, 100L);
        await(() -> exec.started.contains(1L), "任务 1 应开始执行");
        q.submit(2L, 100L);

        assertFalse(q.submit(2L, 100L).accepted(), "同一个任务不该被排两次");
    }
}
