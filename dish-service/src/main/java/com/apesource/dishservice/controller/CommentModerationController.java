package com.apesource.dishservice.controller;

import com.apesource.commonservice.result.Result;
import com.apesource.dishservice.dto.CommentReportRow;
import com.apesource.dishservice.dto.PageResult;
import com.apesource.dishservice.dto.PendingCommentRow;
import com.apesource.dishservice.dto.ReportHandleRequest;
import com.apesource.dishservice.dto.SensitiveWordRequest;
import com.apesource.dishservice.entity.SensitiveWord;
import com.apesource.dishservice.security.AdminOnly;
import com.apesource.dishservice.service.CommentModerationService;
import com.apesource.dishservice.service.SensitiveWordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 评论治理的管理端接口：违规词库 + 举报审核 + 待审评论。
 *
 * <p>整类标 {@link AdminOnly}，路径又都落在 {@code /ai/admin/} 前缀下 ——
 * 这是刻意的双保险：注解是显式声明（grep 就能列出所有管理接口），
 * 前缀是兜底（将来在这个类里加接口时忘了写注解，仍然拦得住）。
 * 判定逻辑见 {@code AdminAuthInterceptor}。
 *
 * <p>所有写操作都会触发词库重建（{@code SensitiveWordService} 内部做），
 * 所以管理页改完词，下一次评论发布立刻生效，不需要重启服务。
 */
@AdminOnly("评论违规词库与举报审核")
@RestController
@RequestMapping("/ai/admin")
@RequiredArgsConstructor
public class CommentModerationController {

    private final SensitiveWordService wordService;
    private final CommentModerationService moderationService;

    // ================================================================== 词库

    /** 词库概览：总量 / 启用数 / 拦截级数量 / 各类别分布 */
    @GetMapping("/sensitive-words/overview")
    public Result<Map<String, Object>> overview() {
        return Result.success(wordService.overview());
    }

    /**
     * 检索词库。
     *
     * @param keyword  词模糊匹配
     * @param category 类别筛选（AD/PORN/VIOLENCE/ILLEGAL/ABUSE/OTHER），ALL 或空表示全部
     * @param level    1-替换，2-整条拦截
     * @param enabled  0-停用，1-启用
     */
    @GetMapping("/sensitive-words")
    public Result<PageResult<SensitiveWord>> page(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "level", required = false) Integer level,
            @RequestParam(name = "enabled", required = false) Integer enabled,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return Result.success(wordService.page(keyword, category, level, enabled, page, size));
    }

    /** 文本试跑：用当前内存词库扫一段文字，返回替换结果与命中明细 */
    @PostMapping("/sensitive-words/test")
    public Result<Map<String, Object>> test(@RequestBody Map<String, String> body) {
        return Result.success(wordService.test(body == null ? null : body.get("text")));
    }

    @PostMapping("/sensitive-words")
    public Result<SensitiveWord> create(@RequestBody SensitiveWordRequest request) {
        return Result.success("已加入词库", wordService.create(request));
    }

    @PutMapping("/sensitive-words/{id}")
    public Result<SensitiveWord> update(@PathVariable("id") Long id,
                                        @RequestBody SensitiveWordRequest request) {
        return Result.success("已保存", wordService.update(id, request));
    }

    /** 停用 / 启用。停用优于删除：历史评论的 hit_words 还指着这个词 */
    @PostMapping("/sensitive-words/{id}/enabled")
    public Result<SensitiveWord> setEnabled(@PathVariable("id") Long id,
                                            @RequestParam("enabled") boolean enabled) {
        return Result.success(enabled ? "已启用" : "已停用", wordService.setEnabled(id, enabled));
    }

    @DeleteMapping("/sensitive-words/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        wordService.delete(id);
        return Result.success("已删除", null);
    }

    /**
     * 按来源批量停用。
     *
     * <p>批量导入的词混进误伤时，用这个一次收掉整批，
     * 而不是在列表里一条条点（一定会漏）。
     */
    @PostMapping("/sensitive-words/disable-source")
    public Result<Map<String, Object>> disableBySource(@RequestParam("source") String source) {
        int rows = wordService.disableBySource(source);
        return Result.success("已停用 " + rows + " 条", Map.of("disabled", rows));
    }

    /** 手动重建内存词库（正常情况下增删改会自动重建，这个口子用于排查） */
    @PostMapping("/sensitive-words/reload")
    public Result<Map<String, Object>> reload() {
        int count = wordService.reload();
        return Result.success("词库已重建", Map.of("loaded", count));
    }

    // ================================================================== 举报

    /** 举报列表。status：0-待处理（默认），1-已受理，2-已驳回 */
    @GetMapping("/comment-reports")
    public Result<PageResult<CommentReportRow>> reports(
            @RequestParam(name = "status", defaultValue = "0") int status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return Result.success(moderationService.pageReports(status, page, size));
    }

    /** 处理举报：action = remove（受理并下架评论）| reject（驳回举报） */
    @PostMapping("/comment-reports/{id}/handle")
    public Result<Map<String, Object>> handle(@RequestHeader("X-User-Id") Long adminId,
                                              @PathVariable("id") Long id,
                                              @RequestBody ReportHandleRequest request) {
        return Result.success("已处理", moderationService.handle(adminId, id,
                request == null ? null : request.getAction(),
                request == null ? null : request.getRemark()));
    }

    // ================================================================== 待审评论

    /** 待审评论（命中拦截级违规词的 + 被举报超阈值的） */
    @GetMapping("/comments/pending")
    public Result<PageResult<PendingCommentRow>> pendingComments(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return Result.success(moderationService.pagePendingComments(page, size));
    }

    /** 处置待审评论：action = approve（放行）| hide（隐藏） */
    @PostMapping("/comments/{id}/review")
    public Result<Map<String, Object>> reviewComment(@RequestHeader("X-User-Id") Long adminId,
                                                     @PathVariable("id") Long commentId,
                                                     @RequestBody ReportHandleRequest request) {
        return Result.success("已处置", moderationService.reviewComment(adminId, commentId,
                request == null ? null : request.getAction(),
                request == null ? null : request.getRemark()));
    }
}
