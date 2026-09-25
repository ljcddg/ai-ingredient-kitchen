package com.apesource.dishservice.controller;

import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.dto.CollectResult;
import com.apesource.dishservice.dto.CommentRequest;
import com.apesource.dishservice.dto.DishCardView;
import com.apesource.dishservice.dto.DishDetailView;
import com.apesource.dishservice.dto.RateRequest;
import com.apesource.dishservice.dto.RatingSummary;
import com.apesource.dishservice.service.DishInteractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户与菜品的交互接口：收藏 / 评分 / 评论，以及「我的菜品」「我的收藏」。
 *
 * <p>和 {@link DishGenerateController} 一样，用户身份从网关注入的
 * {@code X-User-Id} 读，本服务自己不做认证。
 *
 * <p>注意这里<b>没有</b>"取 userId 的兜底逻辑" —— 如果请求头缺失就直接报错。
 * 加兜底（比如当成 1 号用户）会造成"身份静默错乱"，比直接报错危险得多。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class DishInteractionController {

    private final DishInteractionService service;

    // ================================================================== 收藏

    /** 收藏一道菜。重复调用是幂等的，不会让收藏数虚增 */
    @PostMapping("/dish/{dishId}/collect")
    public Result<CollectResult> collect(@RequestHeader("X-User-Id") Long userId,
                                         @PathVariable("dishId") Long dishId) {
        return Result.success("已收藏", service.collect(userId, dishId));
    }

    /** 取消收藏。本来就没收藏也返回成功，前端连点两下不该看到红字 */
    @DeleteMapping("/dish/{dishId}/collect")
    public Result<CollectResult> uncollect(@RequestHeader("X-User-Id") Long userId,
                                           @PathVariable("dishId") Long dishId) {
        return Result.success("已取消收藏", service.uncollect(userId, dishId));
    }

    // ================================================================== 评分 / 评论

    /** 打分（一人一票，重复打是改分）。返回重算后的评分聚合 */
    @PostMapping("/dish/{dishId}/rating")
    public Result<RatingSummary> rate(@RequestHeader("X-User-Id") Long userId,
                                      @PathVariable("dishId") Long dishId,
                                      @RequestBody RateRequest request) {
        return Result.success("评价成功", service.rate(userId, dishId,
                request == null ? null : request.getScore(),
                request == null ? null : request.getCooked()));
    }

    /** 发表评论。内容长度上限与表字段一致 */
    @PostMapping("/dish/{dishId}/comment")
    public Result<DishDetailView.CommentView> comment(@RequestHeader("X-User-Id") Long userId,
                                                      @PathVariable("dishId") Long dishId,
                                                      @RequestBody CommentRequest request) {
        return Result.success("评论成功", service.comment(userId, dishId,
                request == null ? null : request.getContent(),
                request == null ? null : request.getRating()));
    }

    // ================================================================== 我的

    /** 我创建的菜品（含草稿） */
    @GetMapping("/my/dishes")
    public Result<List<DishCardView>> myDishes(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(service.myDishes(userId));
    }

    /** 我收藏的菜品 */
    @GetMapping("/my/collects")
    public Result<List<DishCardView>> myCollects(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(service.myCollects(userId));
    }
}
