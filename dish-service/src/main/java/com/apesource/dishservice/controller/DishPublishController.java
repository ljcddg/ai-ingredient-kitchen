package com.apesource.dishservice.controller;

import com.apesource.commonservice.constant.AuthConstants;
import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.ai.NutritionRecomputeService;
import com.apesource.dishservice.dto.IngredientReviewRequest;
import com.apesource.dishservice.entity.EsSyncTask;
import com.apesource.dishservice.entity.IngredientCandidate;
import com.apesource.dishservice.es.DishEsSyncService;
import com.apesource.dishservice.es.EsSyncTaskService;
import com.apesource.dishservice.es.EsSyncWorker;
import com.apesource.dishservice.security.AdminOnly;
import com.apesource.dishservice.service.DishPublishService;
import com.apesource.dishservice.service.IngredientCandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜品发布相关接口：草稿 → 进找菜库（可被召回）。
 *
 * <p>和生成链路分开放，因为使用者不同：
 * 生成接口在"做菜的时候"被调，这里是"做完了要不要给别人看"。
 *
 * <p>用户身份一律从网关注入的 {@code X-User-Id} 读，和本服务其他接口保持一致。
 *
 * <p><b>这里的接口分两类权限，别混：</b>
 * <ul>
 *   <li>发布 / 撤回 / 发布前校验：<b>归属校验</b> —— 只能动自己的菜；</li>
 *   <li>索引重建、食材转正、候选清单、强制下架：<b>管理员校验</b> ——
 *       由 {@link AdminOnly} + 拦截器在后端强制，判定依据是回源查 {@code t_user.role}，
 *       不是请求头（请求头可以伪造，权限判断不能信它）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class DishPublishController {

    private final DishPublishService publishService;

    private final DishEsSyncService esSyncService;

    private final IngredientCandidateService candidateService;

    /** 索引同步待办的查看与手动补偿 */
    private final EsSyncTaskService esSyncTaskService;

    private final EsSyncWorker esSyncWorker;

    /** P2-2：把库里旧的"AI 估算"营养按成分表重算 */
    private final NutritionRecomputeService nutritionRecomputeService;

    /**
     * 把草稿发布到找菜库。
     *
     * <p>校验不通过时返回具体的缺失项（比如"做法只有 2 条，至少要 3 条"），
     * 而不是一句"参数错误" —— 用户得知道去补什么。
     */
    @PostMapping("/dish/{dishId}/publish")
    public Result<String> publish(@RequestHeader("X-User-Id") Long userId,
                                  @PathVariable("dishId") Long dishId) {
        return Result.success(publishService.publish(userId, dishId));
    }

    /**
     * 从找菜库撤回（回到草稿），菜品本身保留。
     */
    @PostMapping("/dish/{dishId}/unpublish")
    public Result<String> unpublish(@RequestHeader("X-User-Id") Long userId,
                                    @PathVariable("dishId") Long dishId) {
        return Result.success(publishService.unpublish(userId, dishId));
    }

    /**
     * 管理员强制下架：不需要是该菜的作者。
     *
     * <p>为什么不给上面那个 unpublish 加个"管理员就跳过归属校验"的分支：
     * 归属校验是它对普通用户的核心承诺，一个入口里塞两种语义，
     * 以后任何人改判断条件都可能把它改漏。路径分开，权限边界一眼可见，
     * 论文里的权限矩阵也能直接照着列。
     */
    @AdminOnly("强制下架用于处理违规内容，必须能下掉别人发布的菜")
    @PostMapping("/admin/dish/{dishId}/unpublish")
    public Result<String> forceUnpublish(@PathVariable("dishId") Long dishId) {
        return Result.success(publishService.adminUnpublish(dishId));
    }

    /**
     * 预览发布校验结果，不改任何数据。
     *
     * <p>详情页用它来显示「还差什么才能发布」。
     * 单独给一个只读接口而不是让前端"试着发布再解析报错"：
     * 后者会在用户没打算发布的时候触发一堆副作用，也让错误提示变得很难写。
     */
    @GetMapping("/dish/{dishId}/publish-check")
    public Result<DishPublishService.CheckResult> publishCheck(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable("dishId") Long dishId) {
        return Result.success(publishService.checkForOwner(userId, dishId));
    }

    /**
     * 手动触发索引全量重建（运维/调试用）。
     *
     * <p>正常情况下不需要它 —— 发布时增量同步、每天凌晨全量兜底。
     * 但如果怀疑索引和库不一致（比如直接改过数据库），可以立刻重建一次。
     *
     * <p>它是管理员接口：全量重建会扫一遍库里所有已发布的菜、整体刷新索引，
     * 属于运维动作而不是用户行为 —— 普通用户点一下就能重建全库索引显然不合理。
     */
    @AdminOnly("全量重建索引是运维动作，影响整个找菜库的召回结果")
    @PostMapping("/es/reindex")    public Result<DishEsSyncService.ReindexReport> reindex() {
        return Result.success(esSyncService.reindexAll());
    }

    /**
     * 索引同步待办总览：还欠着多少条，一眼可见。
     *
     * <p>挂在运维接口上是有意的 —— "索引落后了多少"应该是<b>随时可查的一个数</b>，
     * 而不是出问题之后去翻日志。givenUp 不为 0 就说明有数据需要人工介入了。
     */
    @AdminOnly("索引同步积压属于运维信息")
    @GetMapping("/es/sync/status")
    public Result<Map<String, Long>> esSyncStatus() {
        return Result.success(esSyncTaskService.countByStatus());
    }

    /** 待办明细：看起来最需要关注的那批（支持按状态过滤） */
    @AdminOnly("查看索引同步待办属于运维操作")
    @GetMapping("/es/sync/tasks")
    public Result<List<EsSyncTask>> esSyncTasks(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Result.success(esSyncTaskService.listByStatus(status, limit));
    }

    /**
     * 手动补偿：把"失败待重试 / 已放弃"的待办重置为立刻可执行，并马上跑一轮。
     *
     * <p>它是 ES 恢复之后要按的那个按钮。后台任务其实也会按退避自己重试，
     * 但放弃之后就不会了 —— 而"ES 挂了半小时"这种事，人知道它已经好了，
     * 不该让那些数据永久留在索引之外，也不该干等下一次调度。
     */
    @AdminOnly("手动补偿会重置失败任务并立刻同步索引")
    @PostMapping("/es/sync/retry")
    public Result<Map<String, Object>> esSyncRetry() {
        int reset = esSyncTaskService.retryFailedNow();
        esSyncWorker.retryPending();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reset", reset);
        data.put("status", esSyncTaskService.countByStatus());
        return Result.success("已重置 " + reset + " 条待办并触发一轮同步", data);
    }

    /**
     * 营养重算：把库里标着"AI 估算"的菜改成"按食材成分表本地计算"。
     *
     * <p>顺带产出「AI 估算 vs 本地计算」的偏差统计 —— 说明文档要求
     * "不把 AI 估算当成实测值"，那首先得知道两者差多少。
     */
    @AdminOnly("营养重算会覆盖菜品表的营养字段，属于数据修正动作")
    @PostMapping("/admin/nutrition/recompute")
    public Result<NutritionRecomputeService.Report> recomputeNutrition() {
        return Result.success("营养重算完成", nutritionRecomputeService.recomputeAll());
    }

    /**
     * 审核通过：把候选食材转正成正式食材。
     *
     * <p>转正做四件事：写 t_ingredient 拿到 ID、回填历史菜品的 ingredient_id、
     * 标记候选已转正并记下审核人/时间/理由、把受影响的菜重写一遍索引。
     * 之后这些食材就走 ID 精确召回了。
     *
     * <p><b>为什么是"显式列出 id"，而不是"把所有够次数的都通过"：</b>
     * 次数多只说明大家敲得多，并不说明这个名字有效（错别字也能被很多人敲）。
     * 主数据是所有归一化与营养计算的基准，进错了很难清干净 ——
     * 所以最终判断必须由人做出，并且留下理由。
     */
    @AdminOnly("食材转正会写正式食材表并回填历史菜品，必须由管理员逐条确认")
    @PostMapping("/ingredients/candidates/approve")
    public Result<IngredientCandidateService.ReviewReport> approveIngredients(
            @RequestHeader(AuthConstants.HEADER_USER_ID) Long reviewerId,
            @RequestBody IngredientReviewRequest request) {
        return Result.success(candidateService.approve(request.ids(), reviewerId, request.reason()));
    }

    /**
     * 审核拒绝：把这个名字挡在主数据之外，并记下理由。
     *
     * <p>拒绝之后 {@code recordUsage} 不再为它计数，所以它不会反复出现在待办里；
     * 但"已审核历史"里查得到是谁、什么时候、为什么拒的。
     */
    @AdminOnly("拒绝候选食材要留下理由，并影响该名字后续是否继续计入待办")
    @PostMapping("/ingredients/candidates/reject")
    public Result<IngredientCandidateService.ReviewReport> rejectIngredients(
            @RequestHeader(AuthConstants.HEADER_USER_ID) Long reviewerId,
            @RequestBody IngredientReviewRequest request) {
        return Result.success(candidateService.reject(request.ids(), reviewerId, request.reason()));
    }

    /**
     * 待收录食材清单，按热度倒序 —— 看看大家都在敲哪些食材表里还没有的东西。
     *
     * <p>这是给运营（管理员）看的待办清单，内容是"用户搜过但库里没有的东西"，
     * 属于运营数据，普通用户看不到。
     */
    @AdminOnly("待收录食材清单是运营数据，只给管理员看")
    @GetMapping("/ingredients/candidates")
    public Result<List<IngredientCandidate>> ingredientCandidates(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.success(candidateService.topCandidates(limit));
    }

    /**
     * 已审核历史（转正 + 拒绝），按审核时间倒序。
     *
     * <p>用途是复盘：同一个名字过一阵子又被用户敲出来了，
     * 运营得能查到上次是谁、因为什么拒的 —— 否则会同一条候选反复拒、反复讨论。
     */
    @AdminOnly("审核历史包含运营决策记录，只给管理员看")
    @GetMapping("/ingredients/candidates/reviewed")
    public Result<List<IngredientCandidate>> reviewedIngredients(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Result.success(candidateService.reviewedHistory(limit));
    }
}
