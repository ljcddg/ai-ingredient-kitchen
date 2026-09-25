package com.apesource.dishservice.controller;

import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.entity.IngredientCandidate;
import com.apesource.dishservice.es.DishEsSyncService;
import com.apesource.dishservice.service.DishPublishService;
import com.apesource.dishservice.service.IngredientCandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜品发布相关接口：草稿 → 进找菜库（可被召回）。
 *
 * <p>和生成链路分开放，因为使用者不同：
 * 生成接口在"做菜的时候"被调，这里是"做完了要不要给别人看"。
 *
 * <p>用户身份一律从网关注入的 {@code X-User-Id} 读，和本服务其他接口保持一致。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class DishPublishController {

    private final DishPublishService publishService;

    private final DishEsSyncService esSyncService;

    private final IngredientCandidateService candidateService;

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
     * <p>注意它没有做权限控制，只适合内网调试；对外暴露前必须加上管理员校验。
     */
    @PostMapping("/es/reindex")
    public Result<DishEsSyncService.ReindexReport> reindex() {
        return Result.success(esSyncService.reindexAll());
    }

    /**
     * 把够次数的食材候选转正成正式食材。
     *
     * <p>转正做四件事：写 t_ingredient 拿到 ID、回填历史菜品的 ingredient_id、
     * 标记候选已转正、把受影响的菜重写一遍索引。之后这些食材就走 ID 精确召回了。
     * <p>同样没有权限控制，只适合内网调试；对外暴露前必须加管理员校验。
     */
    @PostMapping("/ingredients/promote")
    public Result<IngredientCandidateService.PromoteReport> promoteIngredients() {
        return Result.success(candidateService.promoteReady());
    }

    /**
     * 待收录食材清单，按热度倒序 —— 看看大家都在敲哪些食材表里还没有的东西。
     */
    @GetMapping("/ingredients/candidates")
    public Result<List<IngredientCandidate>> ingredientCandidates(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.success(candidateService.topCandidates(limit));
    }
}
