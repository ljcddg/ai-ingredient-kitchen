package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishGeneratePayload;
import com.apesource.dishservice.entity.AiGenerateTask;
import com.apesource.dishservice.entity.AiGenerationRecord;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.mapper.AiGenerateTaskMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.apesource.dishservice.service.DishPublishService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 队列与生成器之间的编排层 —— 整条异步链路真正"干活"的地方。
 *
 * <p>由 {@link DishTaskQueue} 在 worker 线程里回调，一次调用完成一整轮：
 * <pre>
 *   ① 排队中 → 生成中（条件更新，占执行权）
 *     → 读请求快照
 *     → 调 DishGenerator（内部可能重试）
 *     → ② 生成中 → 成功 + 草稿落库（同一个事务，占完成权）
 *     → ③ 事务提交后试自动发布
 * </pre>
 *
 * <p><b>三个容易被忽略、但漏了就会出问题的点：</b>
 * <ol>
 *   <li><b>异常必须在这里兜住并写进 error_msg</b>。队列虽然也会兜异常（防止 worker 线程
 *       被带走），但那样 DB 里会留下一个永远"生成中"的僵尸任务，前端会一直转圈。</li>
 *   <li><b>每一次状态变更都必须带来源状态</b>（见 {@link DishTaskStateMachine}）。
 *       曾经这里是"先查一次取消状态、再无条件 updateById 成成功"，
 *       检查与更新之间的十几秒窗口里用户点取消，取消就会被成功覆盖 ——
 *       用户取消了，菜却照样发布出去。</li>
 *   <li><b>发布必须放在事务提交之后</b>。发布依赖草稿已经落库（它要读菜品、发事务事件同步 ES），
 *       而且发布失败绝不能把"生成成功"这件事回滚掉 —— 用户等了二十秒，
 *       不该因为索引同步的问题连草稿都拿不到。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DishGenerationExecutor implements DishTaskExecutor {

    private final AiGenerateTaskMapper taskMapper;
    private final DishGenerator generator;
    private final DishTaskStateMachine stateMachine;
    private final DishTaskCompletionService completionService;
    private final DishPublishService publishService;
    private final ObjectMapper objectMapper;
    private final GenerationRecorder recorder;
    private final DishMapper dishMapper;

    @Override
    public void execute(long taskId) {
        AiGenerateTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务 {} 不存在（可能已被物理删除），跳过", taskId);
            return;
        }

        long start = System.currentTimeMillis();

        // ① 占执行权。同一任务即使被投递两次（重启补投、队列重复入队），
        //    也只有一个执行体能拿到 —— 另一个影响 0 行，安静退出。
        if (!stateMachine.claimRunning(taskId, (int) Math.max(0, start - enqueueTimeOf(task)))) {
            log.info("任务 {} 未取得执行权（已被取消或已在执行），跳过", taskId);
            return;
        }

        DishGeneratePayload payload;
        DishGenerator.GenerateResult result;
        try {
            payload = objectMapper.readValue(task.getRequestJson(), DishGeneratePayload.class);
            result = generator.generate(new DishGenerator.GenerateInput(
                    payload.ingredients(), payload.tastes(), payload.servings(),
                    payload.constraintsOrNone()));
        } catch (DishGenerationException e) {
            recordFailure(task, e.getMessage(), e.getAttempts(), e.getRawOutput(),
                    e.getValidation(), e.getMeta());
            String reason = brief(e.getMessage());
            log.error("任务 {} 生成失败：{}", taskId, reason, e);
            // ② 失败同样要带来源状态：不能把用户已经取消的任务改成"失败"
            if (!stateMachine.markFailed(taskId, reason, (int) (System.currentTimeMillis() - start))) {
                log.info("任务 {} 的失败状态未写入：它在生成期间已被取消或中断", taskId);
            }
            return;
        } catch (Exception e) {
            String reason = brief(e.getMessage());
            log.error("任务 {} 生成失败（非模型原因）：{}", taskId, reason, e);
            recordFailure(task, reason, 0, null, null, null);
            if (!stateMachine.markFailed(taskId, reason, (int) (System.currentTimeMillis() - start))) {
                log.info("任务 {} 的失败状态未写入：它在生成期间已被取消或中断", taskId);
            }
            return;
        }

        // ③ 占完成权 + 草稿落库，同一事务。返回 null = 生成期间被取消，结果直接丢弃
        Long dishId;
        try {
            dishId = completionService.claimAndSaveDraft(
                    taskId, task.getUserId(), payload, result, (int) result.costMs());
        } catch (Exception e) {
            String reason = brief(e.getMessage());
            log.error("任务 {} 落库失败：{}", taskId, reason, e);
            stateMachine.failAfterClaim(taskId, reason, (int) (System.currentTimeMillis() - start));
            // 模型这次其实产出合格（格式与约束都过了），失败在落库环节 —— 这条信息对归因很重要
            recorder.record(taskId, task.getUserId(), new GenerationRecorder.Outcome(
                    AiGenerationRecord.STATUS_FAILED, null, null, "落库失败：" + reason,
                    result.attempts(), (int) result.costMs(), result.rawOutput(),
                    result.validation(), result.meta()));
            return;
        }

        if (dishId == null) {
            // 用户在生成期间取消了：结果被丢弃，但"模型这次产出是合格的"这件事值得记下来 ——
            // 取消率与取消时的生成完成度，是评估资源浪费的直接依据
            recorder.record(taskId, task.getUserId(), new GenerationRecorder.Outcome(
                    AiGenerationRecord.STATUS_CANCELLED, null, null, "生成期间被用户取消，结果已丢弃",
                    result.attempts(), (int) result.costMs(), result.rawOutput(),
                    result.validation(), result.meta()));
            log.info("任务 {} 在生成期间被取消，本次生成结果已丢弃（未落库、未发布）", taskId);
            return;
        }

        // ④ 事务已提交，这时才试自动发布 —— 失败也不影响"生成成功"这个结果
        publishService.tryAutoPublish(dishId);

        recorder.record(taskId, task.getUserId(), new GenerationRecorder.Outcome(
                AiGenerationRecord.STATUS_SUCCESS, dishId, currentDishStatus(dishId), null,
                result.attempts(), (int) result.costMs(), result.rawOutput(),
                result.validation(), result.meta()));

        log.info("任务 {} 完成：dishId={}，菜名={}，尝试 {} 次，AI 耗时 {} ms",
                taskId, dishId, result.draft().getName(), result.attempts(), result.costMs());
    }

    /** 失败时的记录：把异常里捎带的原始材料一起落库，事后能归因 */
    private void recordFailure(AiGenerateTask task, String errorMsg, int attempts, String rawOutput,
                               ValidationResult validation,
                               DishGenerator.GenerateResult.GenerationMeta meta) {
        recorder.record(task.getId(), task.getUserId(), new GenerationRecorder.Outcome(
                AiGenerationRecord.STATUS_FAILED, null, null, errorMsg,
                attempts, 0, rawOutput, validation, meta));
    }

    /** 读一下菜品当前状态（发布成功与否），记录里那一栏就是"审核状态" */
    private Integer currentDishStatus(long dishId) {
        try {
            Dish dish = dishMapper.selectById(dishId);
            return dish == null ? null : dish.getStatus();
        } catch (Exception e) {
            log.debug("读取菜品状态失败（不影响生成记录）：{}", e.getMessage());
            return null;
        }
    }

    private long enqueueTimeOf(AiGenerateTask task) {
        LocalDateTime createTime = task.getCreateTime();
        return createTime == null
                ? System.currentTimeMillis()
                : createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String brief(String text) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
    }
}
