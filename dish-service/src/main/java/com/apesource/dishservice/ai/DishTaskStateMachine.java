package com.apesource.dishservice.ai;

import com.apesource.dishservice.entity.AiGenerateTask;
import com.apesource.dishservice.mapper.AiGenerateTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 生成任务的状态机 —— 把"允许的状态迁移"集中到一处。
 *
 * <p><b>它解决的具体问题（原实现的一个真实缺陷）：</b>
 * 原来的 {@link DishGenerationExecutor} 是「读一次取消状态 → 落库 → 无条件 updateById 成成功」。
 * 检查与更新之间有一个几秒到几十秒的窗口（生成本身就要十几秒），
 * 用户在这期间点取消，就会出现：
 * <pre>
 *   用户点取消 → 条件更新把状态改成「已取消」(4)
 *   → 生成结束 → 无条件 updateById 把状态覆盖成「成功」(2)
 *   → 结果：用户明确取消了，菜却照样被发布出去
 * </pre>
 *
 * <p><b>修法：每一次状态迁移都带上"来源状态"作为 WHERE 条件。</b>
 * 这样数据库自己就成了仲裁者 —— 谁的条件先满足谁赢，输的一方影响 0 行、
 * 由调用方明确处理（丢弃结果 / 如实告知用户），而不是互相覆盖。
 * 这就是说明文档里说的"条件更新……增加版本号或等价机制"里的等价机制：
 * 条件更新本身配合 InnoDB 的行锁，就已经能保证同一行上的迁移是串行的，
 * 不需要再额外加版本号列。
 *
 * <p><b>合法迁移一览</b>（括号内是实现它的方法）：
 * <pre>
 *   排队中(0) → 生成中(1)   claimRunning        —— 执行权，防止同一任务被两个 worker 跑
 *   生成中(1) → 成功(2)     claimSuccess        —— 完成权，取消过的任务写不进去
 *   成功(2)   → 回填菜品ID  attachResult        —— 只在自己占有成功态时回填
 *   生成中(1) → 失败(3)     markFailed          —— 不覆盖「已取消」
 *   成功(2)   → 失败(3)     failAfterClaim      —— 落库阶段出错（此时已不可能被取消，锁在自己手里）
 *   排队中/生成中 → 已取消(4)  由 DishGenerateService.cancel 条件更新
 *   排队中/生成中 → 已中断(5)  由 AiTaskCleanupRunner 启动清扫条件更新
 * </pre>
 *
 * <p>注意 {@code update(entity, wrapper)} 里 entity 只 set 需要改的列，
 * MyBatis-Plus 会把它拼成 {@code SET ... WHERE id = ? AND status = ?}，
 * 逻辑删除条件 {@code deleted = 0} 由框架自动追加。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DishTaskStateMachine {

    private final AiGenerateTaskMapper taskMapper;

    /**
     * 排队中 → 生成中：占执行权。
     *
     * @return true 表示抢到了；false 表示任务已被取消、或被另一个 worker 先拿走
     */
    public boolean claimRunning(long taskId, int queueWaitMs) {
        AiGenerateTask set = new AiGenerateTask();
        set.setStatus(AiTaskStatus.RUNNING.getCode());
        set.setQueueWaitMs(Math.max(0, queueWaitMs));
        return update(set, taskId, AiTaskStatus.PENDING) > 0;
    }

    /**
     * 生成中 → 成功：占完成权。
     *
     * <p>它是防"取消被覆盖"的关键一步。注意成功的写入<b>不包含</b>结果菜品ID ——
     * 菜品ID要等草稿真正落库之后才知道，由 {@link #attachResult} 补上，
     * 而这两步在同一个事务里（见 {@link DishTaskCompletionService}）。
     *
     * @return true 表示抢到了完成权；false 表示生成期间已被取消或中断，结果必须丢弃
     */
    public boolean claimSuccess(long taskId, int costMs) {
        AiGenerateTask set = new AiGenerateTask();
        set.setStatus(AiTaskStatus.SUCCESS.getCode());
        set.setCostMs(Math.max(0, costMs));
        return update(set, taskId, AiTaskStatus.RUNNING) > 0;
    }

    /** 成功 → 回填结果菜品ID */
    public boolean attachResult(long taskId, long dishId) {
        AiGenerateTask set = new AiGenerateTask();
        set.setResultDishId(dishId);
        return update(set, taskId, AiTaskStatus.SUCCESS) > 0;
    }

    /**
     * 生成中 → 失败。
     *
     * @return true 表示确实写成了失败；false 表示状态已经不是"生成中"
     *         （多半是用户取消了），此时<b>不能</b>把取消覆盖成失败
     */
    public boolean markFailed(long taskId, String errorMsg, int costMs) {
        AiGenerateTask set = new AiGenerateTask();
        set.setStatus(AiTaskStatus.FAILED.getCode());
        set.setErrorMsg(errorMsg);
        set.setCostMs(Math.max(0, costMs));
        return update(set, taskId, AiTaskStatus.RUNNING) > 0;
    }

    /**
     * 成功 → 失败：落库阶段出错时使用。
     *
     * <p>为什么单独一个方法而不是复用 {@link #markFailed}：进入失败分支时状态已经是"成功"
     * （完成权在我们手里），来源状态不同，不能共用一条条件更新。
     */
    public boolean failAfterClaim(long taskId, String errorMsg, int costMs) {
        AiGenerateTask set = new AiGenerateTask();
        set.setStatus(AiTaskStatus.FAILED.getCode());
        set.setErrorMsg(errorMsg);
        set.setCostMs(Math.max(0, costMs));
        return update(set, taskId, AiTaskStatus.SUCCESS) > 0;
    }

    /** 所有迁移共用的写法：{@code UPDATE ... SET <非空字段> WHERE id = ? AND status = ?} */
    private int update(AiGenerateTask set, long taskId, AiTaskStatus from) {
        return taskMapper.update(set, Wrappers.<AiGenerateTask>lambdaUpdate()
                .eq(AiGenerateTask::getId, taskId)
                .eq(AiGenerateTask::getStatus, from.getCode()));
    }
}
