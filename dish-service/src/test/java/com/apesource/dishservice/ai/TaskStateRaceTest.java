package com.apesource.dishservice.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务状态竞争的并发测试。
 *
 * <p><b>为什么这个测试必须连真库、而不是打桩：</b>
 * 这条竞争的仲裁者不是 Java 代码，而是 InnoDB 的<b>行锁</b>加条件更新的 WHERE 语义 ——
 * "后提交的一方会因为条件不再成立而影响 0 行"这件事，
 * 任何 mock 都模拟不出来（mock 只能复述我自己的假设，证明不了数据库真的这么做）。
 *
 * <p>所以这里直接用 JDBC 在真实表 {@code t_ai_generate_task} 上跑，
 * 用的 SQL 条件与 {@link DishTaskStateMachine} 里逐个方法一一对应：
 * <pre>
 *   claimRunning  : WHERE id=? AND status=0
 *   取消          : WHERE id=? AND status IN (0,1)
 *   claimSuccess  : WHERE id=? AND status=1
 *   markFailed    : WHERE id=? AND status=1
 * </pre>
 * 每个用例只插入一行专供自己使用的记录，结束时删掉，不碰真实数据。
 *
 * <p><b>连不上库时自动跳过</b>（{@code Assumptions}）：本机没起 MySQL 的机器上
 * 跑全量测试不该因为它变红 —— 但也正因为会"静默跳过"，
 * 答辩/自查时要看一眼 surefire 输出里它到底跑没跑。
 */
class TaskStateRaceTest {

    private static final String URL =
            "jdbc:mysql://localhost:3306/project0914?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&connectTimeout=2000&socketTimeout=10000";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    /** 状态码与 AiTaskStatus 对齐：0 排队中 / 1 生成中 / 2 成功 / 3 失败 / 4 已取消 */
    private static final int PENDING = 0;
    private static final int RUNNING = 1;
    private static final int SUCCESS = 2;
    private static final int FAILED = 3;
    private static final int CANCELLED = 4;

    private long taskId;

    // ------------------------------------------------------------------ 夹具

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(reachable(), "本机 MySQL 不可用，跳过并发测试");

        // 用一个固定的、不可能与雪花ID冲突的高位ID，避免污染真实任务
        // （上限取 9e18：再大就超 long 范围了，编译器会直接报"整数太大"）
        taskId = 9000000000000000000L + (System.nanoTime() % 100000);
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_ai_generate_task "
                        + "(id, user_id, request_signature, request_json, status, cost_ms, queue_wait_ms, deleted,"
                        + " create_time, update_time) "
                        + "VALUES (?, 1, ?, CAST(? AS JSON), ?, 0, 0, 0, NOW(), NOW())")) {
            ps.setLong(1, taskId);
            ps.setString(2, "0123456789abcdef0123456789abcdef");
            ps.setString(3, "{\"servings\":2}");
            ps.setInt(4, PENDING);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!reachable()) {
            return;
        }
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM t_ai_generate_task WHERE id = ?")) {
            ps.setLong(1, taskId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("用户先取消：之后的生成完成写不进去，结果必须丢弃")
    void cancelWinsThenCompletionFails() throws Exception {
        try (Connection conn = open()) {
            // 先进入生成中（模拟 worker 已经开始跑）
            assertThat(claimRunning(conn)).isEqualTo(1);

            // 用户取消：条件更新 0/1 → 4
            assertThat(cancel(conn)).isEqualTo(1);

            // 生成结束，试图占完成权：来源状态是 1，但现在是 4 → 影响 0 行
            assertThat(claimSuccess(conn)).isEqualTo(0);

            // 失败分支同样写不进去（不能把「已取消」覆盖成「失败」）
            assertThat(markFailed(conn)).isEqualTo(0);

            assertThat(statusOf(conn)).isEqualTo(CANCELLED);
        }
    }

    @Test
    @DisplayName("完成权先拿到：并发的取消会阻塞到事务提交，然后影响 0 行")
    void completionWinsThenCancelFails() throws Exception {
        Connection worker = open();
        try {
            worker.setAutoCommit(false);
            // 先把任务推进到"生成中"（0→1），再占完成权（1→2）。
            // 两次条件更新都在同一个未提交事务里，所以这一行的排他锁一直被 worker 持有着。
            assertThat(claimRunning(worker)).as("先占执行权").isEqualTo(1);
            assertThat(claimSuccess(worker)).as("再占完成权").isEqualTo(1);

            AtomicInteger cancelRows = new AtomicInteger(-1);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);

            Thread canceller = new Thread(() -> {
                try (Connection other = open()) {
                    started.countDown();
                    // 这一句会阻塞在行锁上，直到 worker 提交
                    cancelRows.set(cancel(other));
                } catch (Exception ignored) {
                    // 异常时保持 -1，由下面的断言暴露
                } finally {
                    done.countDown();
                }
            });
            canceller.start();

            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            // 给取消线程一点时间真正撞到锁上，再提交 —— 这样测的才是"并发"而不是"顺序"
            Thread.sleep(300);
            assertThat(cancelRows.get()).as("取消应被行锁挡住，还没出结果").isEqualTo(-1);

            worker.commit();

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelRows.get()).as("取消来得太晚，状态已是成功，影响 0 行").isEqualTo(0);

            try (Connection check = open()) {
                assertThat(statusOf(check)).isEqualTo(SUCCESS);
            }
        } finally {
            worker.close();
        }
    }

    @Test
    @DisplayName("同一任务被投递两次：只有一方拿到执行权")
    void onlyOneWorkerClaimsExecution() throws Exception {
        try (Connection first = open(); Connection second = open()) {
            assertThat(claimRunning(first)).as("第一个 worker 拿到执行权").isEqualTo(1);
            assertThat(claimRunning(second)).as("第二个 worker 拿不到").isEqualTo(0);
            assertThat(statusOf(first)).isEqualTo(RUNNING);
        }
    }

    @Test
    @DisplayName("已被取消的任务：worker 连执行权都拿不到（不会白跑一次生成）")
    void cancelledTaskNeverStarts() throws Exception {
        try (Connection conn = open()) {
            assertThat(cancel(conn)).isEqualTo(1);
            assertThat(claimRunning(conn)).as("取消过的任务不该再被 worker 领走").isEqualTo(0);
            assertThat(statusOf(conn)).isEqualTo(CANCELLED);
        }
    }

    // ------------------------------------------------------------------ 与状态机一一对应的 SQL

    /** 对应 {@link DishTaskStateMachine#claimRunning}：0 → 1 */
    private int claimRunning(Connection conn) throws SQLException {
        return exec(conn, "UPDATE t_ai_generate_task SET status = " + RUNNING
                + " WHERE id = ? AND status = " + PENDING);
    }

    /** 对应 {@code DishGenerateService.cancel}：0/1 → 4 */
    private int cancel(Connection conn) throws SQLException {
        return exec(conn, "UPDATE t_ai_generate_task SET status = " + CANCELLED
                + " WHERE id = ? AND status IN (" + PENDING + ", " + RUNNING + ")");
    }

    /** 对应 {@link DishTaskStateMachine#claimSuccess}：1 → 2 */
    private int claimSuccess(Connection conn) throws SQLException {
        return exec(conn, "UPDATE t_ai_generate_task SET status = " + SUCCESS
                + ", cost_ms = 100 WHERE id = ? AND status = " + RUNNING);
    }

    /** 对应 {@link DishTaskStateMachine#markFailed}：1 → 3 */
    private int markFailed(Connection conn) throws SQLException {
        return exec(conn, "UPDATE t_ai_generate_task SET status = " + FAILED
                + " WHERE id = ? AND status = " + RUNNING);
    }

    private int exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, taskId);
            return ps.executeUpdate();
        }
    }

    private int statusOf(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status FROM t_ai_generate_task WHERE id = ?")) {
            ps.setLong(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------ 连接

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static boolean reachable() {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement ignored = conn.createStatement()) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
