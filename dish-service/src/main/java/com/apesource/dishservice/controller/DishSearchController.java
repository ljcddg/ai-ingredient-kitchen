package com.apesource.dishservice.controller;

import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.dto.DishRecallResult;
import com.apesource.dishservice.dto.DishSearchRequest;
import com.apesource.dishservice.service.DishSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
     */
    @PostMapping("/search")
    public Result<DishRecallResult> search(@RequestBody @Valid DishSearchRequest request) {
        return Result.success(dishSearchService.search(request));
    }
}
