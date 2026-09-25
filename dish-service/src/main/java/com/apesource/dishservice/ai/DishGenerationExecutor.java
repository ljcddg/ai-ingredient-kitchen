package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishGeneratePayload;
import com.apesource.dishservice.entity.AiGenerateTask;
import com.apesource.dishservice.mapper.AiGenerateTaskMapper;
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
 *   置为生成中
 *     → 读请求快照
 *     → 调 DishGenerator（内部可能重试）
 *     → 交给 DishDraftWriter 落库（草稿态，仅创建者可见）
 *     → 置为成功 / 失败
 * </pre>
 *
 * <p>两个容易被忽略、但漏了就会出问题的点：
 * <ol>
 *   <li><b>异常必须在这里兜住并写进 error_msg</b>。队列虽然也会兜异常（防止 worker 线程
 *       被带走），但那样 DB 里会留下一个永远"生成中"的僵尸任务，前端会一直转圈。</li>
 *   <li><b>落库前要再确认一次任务没被取消</b>。生成要 10 秒上下，这期间用户完全可能
 *       已经点了取消 —— 不检查的话，取消完过两秒又自己冒出一道菜来。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DishGenerationExecutor implements DishTaskExecutor {

    private final AiGenerateTaskMapper taskMapper;
    private final DishGenerator generator;
    private final DishDraftWriter draftWriter;
    private final DishPublishService publishService;
    private final ObjectMapper objectMapper;

    @Override
    public void execute(long taskId) {
        AiGenerateTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务 {} 不存在（可能已被物理删除），跳过", taskId);
            return;
        }

        long start = System.currentTimeMillis();
        markRunning(taskId, start - enqueueTimeOf(task));

        try {
            DishGeneratePayload payload = objectMapper.readValue(
                    task.getRequestJson(), DishGeneratePayload.class);

            DishGenerator.GenerateResult result = generator.generate(new DishGenerator.GenerateInput(
                    payload.ingredients(), payload.tastes(), payload.servings()));

            // 生成期间用户可能已经取消了 —— 这一步不能省
            AiGenerateTask latest = taskMapper.selectById(taskId);
            if (latest != null && AiTaskStatus.CANCELLED.getCode() == latest.getStatus()) {
                log.info("任务 {} 在生成期间被取消，丢弃这一次的生成结果", taskId);
                return;
            }

            Long dishId = draftWriter.save(task.getUserId(), payload, result);

            // 落库之后立刻试一次自动发布。
            // 草稿是"仅创建者可见"的私有内容，只有进了找菜库别人才搜得到 ——
            // 不自动发的话，用户生成一堆菜却一道都搜不出来，功能看起来就是坏的。
            // 校验没过就留在草稿（用户在详情页可以手动再发一次）；
            // 这一步失败绝不能影响"生成成功"这个结果，所以 tryAutoPublish 内部自己兜异常。
            publishService.tryAutoPublish(dishId);

            AiGenerateTask update = new AiGenerateTask();
            update.setId(taskId);
            update.setStatus(AiTaskStatus.SUCCESS.getCode());
            update.setResultDishId(dishId);
            update.setCostMs((int) result.costMs());
            taskMapper.updateById(update);

            log.info("任务 {} 完成：dishId={}，菜名={}，尝试 {} 次，AI 耗时 {} ms",
                    taskId, dishId, result.draft().getName(), result.attempts(), result.costMs());

        } catch (Exception e) {
            String reason = brief(e.getMessage());
            log.error("任务 {} 生成失败：{}", taskId, reason, e);

            AiGenerateTask update = new AiGenerateTask();
            update.setId(taskId);
            update.setStatus(AiTaskStatus.FAILED.getCode());
            update.setErrorMsg(reason);
            update.setCostMs((int) (System.currentTimeMillis() - start));
            taskMapper.updateById(update);
        }
    }

    private void markRunning(long taskId, long waitedMs) {
        AiGenerateTask update = new AiGenerateTask();
        update.setId(taskId);
        update.setStatus(AiTaskStatus.RUNNING.getCode());
        update.setQueueWaitMs((int) Math.max(0, waitedMs));
        taskMapper.updateById(update);
    }

    private long enqueueTimeOf(AiGenerateTask task) {
        LocalDateTime createTime = task.getCreateTime();
        return createTime == null
                ? System.currentTimeMillis()
                : createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String brief(String text) {
        if (text == null) {
            return "未知错误";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 400 ? oneLine : oneLine.substring(0, 400);
    }
}
