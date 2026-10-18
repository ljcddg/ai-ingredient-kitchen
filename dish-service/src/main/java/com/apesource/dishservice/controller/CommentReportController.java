package com.apesource.dishservice.controller;

import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.dto.ReportRequest;
import com.apesource.dishservice.service.CommentModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户侧举报入口。
 *
 * <p>路径挂在 {@code /ai/comment/...} 而不是 {@code /ai/admin/...}：
 * 这是普通用户能调的动作，不该落进管理接口前缀（那样会被拦截器直接 403）。
 *
 * <p><b>为什么举报接口不做频率限制：</b>
 * 同一个人对同一条评论只能举报一次（Service 层查重），
 * 而「换着评论刷举报」需要先有足够多的评论 —— 那种情况下的瓶颈在评论发布，
 * 不在举报。真要防刷，正确位置是全局的写接口限流，而不是在这里堆一个特例。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class CommentReportController {

    private final CommentModerationService moderationService;

    /**
     * 举报一条评论。
     *
     * <p>返回该评论累计被举报次数与是否已自动转待审 —— 前端据此提示
     * 「已提交，管理员会尽快处理」，而不是把用户丢在一个没有反馈的空页面上。
     */
    @PostMapping("/comment/{commentId}/report")
    public Result<Map<String, Object>> report(@RequestHeader("X-User-Id") Long userId,
                                              @PathVariable("commentId") Long commentId,
                                              @RequestBody(required = false) ReportRequest request) {
        return Result.success("举报已提交，管理员会尽快处理",
                moderationService.report(userId, commentId,
                        request == null ? null : request.getReasonType(),
                        request == null ? null : request.getReason()));
    }
}
