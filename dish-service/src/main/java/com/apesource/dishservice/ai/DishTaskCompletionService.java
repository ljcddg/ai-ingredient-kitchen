package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishGeneratePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「占有完成权 + 草稿落库」这两件事的事务边界。
 *
 * <p><b>为什么必须单独抽一个 Service：</b>
 * {@code @Transactional} 靠 Spring 代理生效，同一个类里自己调自己的方法是<b>不走代理</b>的 ——
 * 直接写在 {@link DishGenerationExecutor} 里加注解不会开事务，是个安静的坑。
 *
 * <p><b>为什么这两件事必须在同一个事务里：</b>
 * <ol>
 *   <li>先做条件更新（生成中 → 成功）会拿到该行的排他锁。并发的"取消"请求会阻塞在这里，
 *       等我们提交后它再判断条件 —— 那时状态已经是"成功"，它的条件不成立、影响 0 行，
 *       于是用户得到的是一句如实的"任务已完成，取消没有生效"，
 *       而不是「我取消了但菜还是被发布了」。</li>
 *   <li>落库过程中任何一步失败，事务回滚会连着那条状态更新一起撤销 ——
 *       任务回到"生成中"，外层再按失败处理。不会留下"状态说成功、库里却没有菜"的记录。</li>
 * </ol>
 *
 * <p>反过来，如果用户在生成期间就先取消了，{@code claimSuccess} 影响 0 行、
 * 方法直接返回 null，<b>连草稿都不会写</b> —— 比"写完再判断要不要发布"更干净：
 * 取消之后用户连一条多余草稿都不会看到。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DishTaskCompletionService {

    private final DishTaskStateMachine stateMachine;

    private final DishDraftWriter draftWriter;

    /**
     * 占有完成权并把草稿落库（同一事务）。
     *
     * @return 新建草稿的菜品ID；返回 <b>null</b> 表示任务在生成期间已被取消/中断，
     *         调用方应当丢弃这次生成结果，既不落库也不发布
     */
    @Transactional(rollbackFor = Exception.class)
    public Long claimAndSaveDraft(long taskId, long userId, DishGeneratePayload payload,
                                  DishGenerator.GenerateResult result, int costMs) {

        if (!stateMachine.claimSuccess(taskId, costMs)) {
            log.info("任务 {} 未取得完成权（生成期间已被取消/中断），结果丢弃", taskId);
            return null;
        }

        Long dishId = draftWriter.save(userId, payload, result);

        // 回填菜品ID：此时状态一定是"成功"，且这一行仍被本事务锁着
        if (!stateMachine.attachResult(taskId, dishId)) {
            // 理论上到不了这里。真到了说明有人绕过状态机直改 DB —— 让事务整体回滚，
            // 宁可任务失败，也不要留下"任务没有菜品ID、菜品却已经存在"的孤儿数据
            throw new IllegalStateException("任务 " + taskId + " 回填菜品ID失败：状态被并发修改");
        }
        return dishId;
    }
}
