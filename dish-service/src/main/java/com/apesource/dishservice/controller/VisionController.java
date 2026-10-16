package com.apesource.dishservice.controller;

import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.dto.VisionResult;
import com.apesource.dishservice.service.VisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 识菜接口（拍照 → 候选项 → 用户勾选 → 回填生成）。
 *
 * <p>与 {@link DishGenerateController} 同一套身份约定：用户从网关注入的
 * {@code X-User-Id} 读，本服务不做认证。
 *
 * <p>为什么是**同步**接口而不是异步任务：YOLO 单图 GPU 4.4ms、CPU 秒级，
 * 9 张图上限内同步返回即可；异步任务表（t_ai_generate_task）是给 gemma
 * 12~26 秒/道 准备的，识别复用它是高射炮打蚊子。
 */
@RestController
@RequestMapping("/ai/vision")
@RequiredArgsConstructor
public class VisionController {

    private final VisionService visionService;

    /**
     * 多图识别。
     *
     * <p>multipart/form-data，字段名 {@code files}（可多值）。
     * 上限/类型/大小在 service 校验，不合格抛 BusinessException → 统一 Result。
     *
     * <p>这个接口**不进 gateway 白名单**：要记 userId、将来要限流，必须登录。
     */
    @PostMapping("/dishes")
    public Result<VisionResult> detect(@RequestHeader("X-User-Id") Long userId,
                                      @RequestParam("files") List<MultipartFile> files) {
        return Result.success(visionService.detect(userId, files));
    }
}
