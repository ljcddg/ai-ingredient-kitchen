package com.apesource.dishservice.controller;

import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.dto.DishRecallResult;
import com.apesource.dishservice.dto.DishSearchRequest;
import com.apesource.dishservice.service.DishSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜品召回接口。
 *
 * <p>路径说明：网关路由配的是 {@code Path=/api/dish/**} + {@code StripPrefix=2}，
 * 会砍掉 {@code /api/dish} 两级。所以这里写成 {@code /search}，
 * 前端实际请求的是 {@code POST /api/dish/search}。
 *
 * <p>身份从网关注入的 {@code X-User-Id} 请求头读取（本服务不做认证）。
 */
@RestController
@RequiredArgsConstructor
public class DishSearchController {

    private final DishSearchService dishSearchService;

    /**
     * 按食材组合召回菜品。
     *
     * <p>返回里 {@code directReturn} 是关键字段：
     * true 表示可以直接用库里已有的菜，false 表示需要走 AI 生成。
     * 另有 {@code decision} 给出三档决策（直出 / 带参考生成 / 裸生成）。
     *
     * <p>{@code X-User-Id} 只用于决策日志的用户维度统计：拿不到就当匿名，
     * 绝不能因为它缺失或格式不对让检索 400 —— 召回本身与身份无关。
     */
    @PostMapping("/search")
    public Result<DishRecallResult> search(@RequestBody @Valid DishSearchRequest request,
                                           @RequestHeader(value = "X-User-Id", required = false)
                                           String userIdHeader) {
        return Result.success(dishSearchService.search(request, parseUserId(userIdHeader)));
    }

    private Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
