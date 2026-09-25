package com.apesource.dishservice.controller;

import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.service.DishRankService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜品榜单接口。
 *
 * <p>三种榜单共用一个入口，靠 {@code type} 区分，而不是开三个接口：
 * 它们的响应结构完全一样（都是分页的菜品卡片），
 * 分成三个接口只会让前端多写两遍请求逻辑。
 *
 * <p>参数都带 defaultValue，所以 {@code GET /ai/rank} 不带任何参数也能用 ——
 * 这是默认进热门榜。
 */
@RestController
@RequestMapping("/ai/rank")
@RequiredArgsConstructor
public class DishRankController {

    private final DishRankService rankService;

    /**
     * @param type hot（热门，默认）/ top（高分）/ latest（最新）
     */
    @GetMapping
    public Result<DishRankService.RankPage> rank(
            @RequestParam(name = "type", defaultValue = "hot") String type,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return Result.success(rankService.rank(type, page, size));
    }
}
