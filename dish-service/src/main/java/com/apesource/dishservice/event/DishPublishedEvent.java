package com.apesource.dishservice.event;

/**
 * 菜品发布事件。
 *
 * <p>发布这件事有两件"副作用"要做：写 ES 索引、以及将来可能的推送通知、算分刷新等。
 * 它们都不该塞进发布那个事务里 —— 事务里做的事越少，锁持有时间越短；
 * 而且 ES 写入一旦失败，会把整个发布事务一起拖回滚，这是反过来的依赖：
 * <b>业务数据是主体，索引只是副本，副本失败不该让主体失败。</b>
 *
 * <p>所以用领域事件解耦：发布方只管发出事件，谁感兴趣谁来监听。
 * 监听方用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}，
 * 保证只在事务真正提交之后才动作。
 *
 * <p>注意 {@code @TransactionalEventListener} 有一个很安静的坑：
 * <b>如果发布事件的方法不在事务里，监听器默认根本不会执行</b>（不是报错，是静默跳过）。
 * 所以 {@code DishPublishService} 的发布方法必须带 {@code @Transactional}。
 */
public record DishPublishedEvent(Long dishId) {
}
