package com.apesource.dishservice.recommend;

import com.apesource.commonservice.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 推荐池（冷启动）接口。
 *
 * <p>挂在 {@code /api/dish/ai/recommend} 下，网关已有 {@code /api/dish/**} 路由，
 * 不需要新增路由配置。
 *
 * <p>用户身份一律从 {@code X-User-Id} 请求头读（由网关注入），
 * 本服务不做认证 —— 和项目里其他接口保持一致。
 */
@Slf4j
@RestController
@RequestMapping("/ai/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;

    /**
     * 取一屏推荐流。
     *
     * @param ingredientIds   用户勾选的食材 ID，可空（为空就纯随机）
     * @param ingredientNames 用户自由输入的食材名（表外食材），逗号分隔，可空。
     *                        服务端会先按别名归一到主数据 —— 所以「西红柿」和
     *                        点选「番茄」是同一件事，不会因为入口不同给出两套结果。
     */
    @GetMapping
    public Result<RecommendService.RecommendPage> feed(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(name = "ingredientIds", required = false) List<Long> ingredientIds,
            @RequestParam(name = "ingredientNames", required = false) List<String> ingredientNames,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        return Result.success(recommendService.feed(userId, ingredientIds, ingredientNames, page, size));
    }

    /**
     * 【已废弃】浏览上报。
     *
     * <p>计数已迁到菜品详情接口（{@code DishViewCounter}），覆盖全部入口。
     * 这个端点留着只是为了不让还在调它的前端报错，本体不再做任何事 ——
     * 真让它继续计数的话，推荐流点进详情会被算两次（这里一次、详情接口一次）。
     *
     * <p>前端改造完成后，连同调用一起删掉。
     */
    @Deprecated
    @PostMapping("/{dishId}/view")
    public Result<Void> view(@PathVariable Long dishId) {
        log.debug("废弃端点 /ai/recommend/{}/view 被调用，浏览计数已由详情接口负责", dishId);
        return Result.success();
    }

    /**
     * 上报互动。
     *
     * @param type like（点赞）/ collect（收藏）/ cook（试做打卡）
     */
    @PostMapping("/{dishId}/interact")
    public Result<Void> interact(@RequestHeader("X-User-Id") Long userId,
                                 @PathVariable Long dishId,
                                 @RequestParam(name = "type") String type) {
        recommendService.reportInteract(dishId, userId, type);
        return Result.success();
    }
}
