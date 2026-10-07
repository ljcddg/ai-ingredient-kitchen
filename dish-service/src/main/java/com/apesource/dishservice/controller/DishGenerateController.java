package com.apesource.dishservice.controller;

import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.dto.DishDetailView;
import com.apesource.dishservice.dto.DishGenerateRequest;
import com.apesource.dishservice.dto.DishTaskView;
import com.apesource.dishservice.service.DishGenerateService;
import com.apesource.dishservice.service.DishViewCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 做菜相关接口。
 *
 * <p>用户身份一律从网关注入的 {@code X-User-Id} 读 ——
 * 本服务不对外暴露，自己不做认证。前提是**业务服务只能被网关访问**，
 * 否则别人直连 8082 自己塞一个 X-User-Id 就冒充成功了。
 *
 * <p>所有接口都是「立即返回」的：提交只入队不等结果，查任务只读状态。
 * 真正耗时的生成跑在后台队列里。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class DishGenerateController {

    private final DishGenerateService service;

    /**
     * 浏览计数。挂在详情接口上——这是所有入口（搜索、榜单、收藏夹、推荐流）的汇聚点。
     */
    private final DishViewCounter dishViewCounter;

    /**
     * 提交一次生成请求 —— 立刻返回 taskId 和排队位置，不阻塞。
     *
     * <p>返回体里的 {@code position} 就是"你排在第几"，前端可以直接展示。
     */
    @PostMapping("/dish")
    public Result<DishTaskView> submit(@RequestHeader("X-User-Id") Long userId,
                                       @RequestBody DishGenerateRequest request) {
        return Result.success(service.submit(userId, request));
    }

    /**
     * 我的任务列表（含实时排队位置）。前端靠轮询这个接口更新状态。
     */
    @GetMapping("/tasks")
    public Result<List<DishTaskView>> myTasks(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(service.myTasks(userId));
    }

    /**
     * 调整排队顺序。
     *
     * <p>body: {@code {"orderAmongMine": 1}} —— 表示"想排到我的任务里的第 1 位"。
     * 只能在自己提交的任务之间调整，别人的位置不会动。
     */
    @PutMapping("/tasks/{taskId}/order")
    public Result<Boolean> move(@RequestHeader("X-User-Id") Long userId,
                                @PathVariable("taskId") Long taskId,
                                @RequestBody Map<String, Integer> body) {
        Integer order = body == null ? null : body.get("orderAmongMine");
        return Result.success(service.move(userId, taskId, order == null ? 1 : order));
    }

    /**
     * 取消任务。只对**进行中**的任务有意义 ——
     * 排队中是真正从队列移除（省资源），执行中是逻辑取消（AI 调用已发出，拦不住）。
     *
     * <p>为什么取消走 POST 而不是 DELETE：
     * DELETE 的语义是"把这个资源删掉"，而取消只是让它进入终态、**记录仍然留着**
     * （用户需要看到"这条是我自己取消的"）。把两件事都挂在 DELETE 上，
     * 接口文档怎么写都别扭。
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public Result<String> cancel(@RequestHeader("X-User-Id") Long userId,
                                 @PathVariable("taskId") Long taskId) {
        return Result.success(service.cancel(userId, taskId));
    }

    /**
     * 移除一条**已结束**的任务记录，把它从「我的任务」列表里清掉。
     *
     * <p>排队中 / 生成中的任务走这里会被拒绝（提示先取消）——
     * 否则用户以为任务没了，后台的 AI 调用却还在跑、还会落一道菜进库，
     * 变成一条谁也解释不清的幽灵任务。
     */
    @DeleteMapping("/tasks/{taskId}")
    public Result<String> deleteTask(@RequestHeader("X-User-Id") Long userId,
                                     @PathVariable("taskId") Long taskId) {
        return Result.success(service.deleteTask(userId, taskId));
    }

    /**
     * 一次清空所有**已结束**的任务记录（已完成 / 生成失败 / 已取消 / 已中断）。
     *
     * <p>路径放在 {@code /tasks/{taskId}} 旁边：字面量段比变量段更具体，
     * 所以 {@code /tasks/finished} 不会被当成 taskId 解析（Spring 的 PathPattern 保证）。
     *
     * <p>进行中的任务不在清理范围内 —— 想清掉得先取消，
     * 否则后台的 AI 调用还在跑，会变成谁也解释不清的幽灵任务。
     *
     * @return 实际清掉的条数
     */
    @DeleteMapping("/tasks/finished")
    public Result<Integer> clearFinishedTasks(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(service.clearFinishedTasks(userId));
    }

    /**
     * 菜品详情：营养 / 制作过程 / 大厨建议 / 评论 / 评分，一次给全。
     *
     * <p>草稿（未发布）只有创建者本人能看得到。
     */
    @GetMapping("/dish/{dishId}")
    public Result<DishDetailView> detail(@RequestHeader("X-User-Id") Long userId,
                                         @PathVariable("dishId") Long dishId) {
        DishDetailView detail = service.detail(dishId, userId);

        // 放在查到详情之后：查不到（草稿且非作者）会先抛异常，走到这里说明真的看到了。
        // 计数失败不影响返回——DishViewCounter 内部只更新、不抛异常。
        dishViewCounter.countView(dishId, userId);

        return Result.success(detail);
    }

    /** 调试用：看一眼队列里现在排着什么 */
    @GetMapping("/queue")
    public Result<Map<String, Object>> queue() {
        return Result.success(service.queueSnapshot());
    }
}
