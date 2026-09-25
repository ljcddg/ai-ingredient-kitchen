package com.apesource.dishservice.ai;

/**
 * 任务真正被执行的入口 —— 由队列回调，实现方在业务层（Service）。
 *
 * <p>为什么要这个接口，而不是让队列直接调 AI：
 * 队列应该是一个**纯粹的调度组件**，只管"什么时候轮到谁跑"，
 * 不该知道 Spring AI、不该知道数据库、不该知道菜品草稿怎么落。
 * 这样队列能被单独理解、单独测试，将来换成分布式队列也不用动业务代码。
 */
public interface DishTaskExecutor {

    /**
     * 执行一次生成任务。
     *
     * <p>实现方负责完整的一轮：<b>置为生成中 → 调模型 → 落草稿 → 置终态</b>。
     * 包括失败场景：异常也要在实现里捕获并写进 {@code error_msg}，
     * 不要直接往上抛 —— 虽然队列会兜住异常（不让 worker 线程死掉），
     * 但那样 DB 里就会留下一个永远"生成中"的僵尸任务。
     *
     * @param taskId 任务ID（即 {@code t_ai_generate_task.id}）
     */
    void execute(long taskId);
}
