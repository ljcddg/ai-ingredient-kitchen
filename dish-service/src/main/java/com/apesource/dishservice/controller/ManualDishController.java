package com.apesource.dishservice.controller;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.ai.ManualDishWriter;
import com.apesource.dishservice.dto.ManualDishRequest;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.mapper.DishMapper;
import com.apesource.dishservice.service.DishPublishService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 「手写菜谱」：用户在食材页里自己写一道菜并上传。
 *
 * <p>两个接口的分工：{@code /preview} 只算不写（边填边试算），
 * {@code POST /ai/dish/manual} 落库成草稿并立刻试一次自动发布。
 *
 * <p>路径说明：本服务类上都是 {@code /ai} 前缀（网关 {@code /api/dish/**} + StripPrefix=2），
 * 所以前端调的是 {@code POST /api/dish/ai/dish/manual}。
 * 这里没有写成 {@code /ai/manual} 是为了让"手写菜谱"和"AI 生成"（{@code /ai/dish}）
 * 在同一层级上并排 —— 它们是同一个功能的两种入口。
 *
 * <p><b>为什么上传后要再查一次库</b>：{@code tryAutoPublish} 的契约是"尽力而为、
 * 失败只记日志不抛异常"，它不返回结果。要知道到底发出去没有，只能回读状态 ——
 * 这也顺便避免了"接口说发布成功、其实还在草稿"这种谎报。
 */
@RestController
@RequestMapping("/ai/dish/manual")
@RequiredArgsConstructor
public class ManualDishController {

    private final ManualDishWriter writer;

    private final DishPublishService publishService;

    private final DishMapper dishMapper;

    /** 上传结果：菜品 ID、有没有发出去、没发出去的原因、营养试算 */
    public record ManualDishResult(Long dishId, boolean published, List<String> problems,
                                   ManualDishWriter.Preview nutrition) {
    }

    /** 试算：算营养、逐行标出有没有对上食材库，不写任何数据 */
    @PostMapping("/preview")
    public Result<ManualDishWriter.Preview> preview(
            // 身份用于查"这个菜名你自己是不是已经有一道了"（唯一键 uk_name_creator）。
            // 用 required=false：没带身份时少一条提示，也不至于把试算打成报错。
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestBody ManualDishRequest request) {
        return Result.success(writer.preview(userId, request));
    }

    /** 上传：落库成草稿 → 立刻试一次自动发布 → 如实告知结果 */
    @PostMapping
    public Result<ManualDishResult> create(@RequestHeader("X-User-Id") Long userId,
                                           @RequestBody ManualDishRequest request) {
        Long dishId = writer.save(userId, request);

        publishService.tryAutoPublish(dishId);

        Dish dish = dishMapper.selectById(dishId);
        if (dish == null) {
            throw new BusinessException("菜品保存失败，请重试");
        }
        boolean published = Objects.equals(dish.getStatus(), DishPublishService.STATUS_PUBLISHED);
        // 没发出去就说清楚差什么（重名这类只有查库才知道的原因也在这里）
        List<String> problems = published ? List.of() : publishService.check(dish).problems();
        // 注意这里**不传 userId**：这道菜刚刚已经落库了，带上身份做重名检查会把它自己认成"已占用"，
        // 于是回给用户一条假的重名提示。要报的重名原因由上面 publishService.check 给（只比已发布的菜）。
        return Result.success(published ? "已发布到找菜库" : "已存为草稿，还没能发布",
                new ManualDishResult(dishId, published, problems, writer.preview(request)));
    }
}
