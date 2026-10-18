package com.apesource.dishservice.service;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.dto.CommentReportRow;
import com.apesource.dishservice.dto.PageResult;
import com.apesource.dishservice.dto.PendingCommentRow;
import com.apesource.dishservice.entity.CommentReport;
import com.apesource.dishservice.entity.DishComment;
import com.apesource.dishservice.mapper.CommentReportMapper;
import com.apesource.dishservice.mapper.DishCommentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论治理：用户举报 + 管理员审核。
 *
 * <p><b>为什么「举报够 N 次」不能自动隐藏：</b>
 * 那等于把下架权交给任意 N 个账号。三个人约好就能让别人的内容消失，
 * 而且攻击者不需要任何成本。所以举报只做两件事：
 * <ol>
 *   <li>把争议内容推进待审队列（{@code status=0}），从公开列表里摘掉；</li>
 *   <li>给管理员一个排序依据（谁被举报得多，先看谁）。</li>
 * </ol>
 * 是否恢复展示、是否彻底隐藏，必须由人决定并留下记录。
 *
 * <p><b>为什么评论下架是 status=2 而不是删行：</b>
 * 被下架的内容是处理依据。删掉之后「当初为什么下架」就只能靠记忆，
 * 用户申诉时也无从核对。{@code status=2} + {@code audit_remark} 两者一起
 * 构成了可追溯的处置记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentModerationService {

    /**
     * 举报达到这个数量，评论自动转待审核。
     *
     * <p>取 3 而不是 1：单条举报可能是误报或个人恩怨，三条独立举报
     * 说明至少存在争议，值得人工看一眼。这个值只影响「是否进入队列」，
     * 不影响「是否下架」—— 下架永远是人做的。
     */
    static final int REPORT_AUTO_PENDING_THRESHOLD = 3;

    private static final int MAX_REASON_LENGTH = 200;
    private static final int COMMENT_STATUS_PENDING = 0;
    private static final int COMMENT_STATUS_NORMAL = 1;
    private static final int COMMENT_STATUS_HIDDEN = 2;

    private final DishCommentMapper commentMapper;
    private final CommentReportMapper reportMapper;

    // ================================================================== 用户侧

    /**
     * 举报一条评论。
     *
     * @return 该评论当前的举报数与是否已转入待审
     */
    @Transactional
    public Map<String, Object> report(long userId, long commentId, Integer reasonType, String reason) {
        DishComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException("这条评论不存在或已被删除");
        }
        if (comment.getStatus() == null || comment.getStatus() != COMMENT_STATUS_NORMAL) {
            // 已经不可见了，再举报没有意义：管理员已经处理过，或者它还在待审队列里
            throw new BusinessException("这条评论当前不可举报");
        }
        if (comment.getUserId() != null && comment.getUserId() == userId) {
            throw new BusinessException("不能举报自己的评论");
        }

        Long duplicated = reportMapper.selectCount(Wrappers.<CommentReport>lambdaQuery()
                .eq(CommentReport::getCommentId, commentId)
                .eq(CommentReport::getReporterId, userId)
                .eq(CommentReport::getStatus, CommentReport.STATUS_PENDING));
        if (duplicated != null && duplicated > 0) {
            throw new BusinessException("你已经举报过这条评论，等管理员处理");
        }

        CommentReport entity = new CommentReport();
        entity.setCommentId(commentId);
        entity.setDishId(comment.getDishId());
        entity.setReporterId(userId);
        entity.setReasonType(normalizeReasonType(reasonType));
        entity.setReason(trimToNull(reason, MAX_REASON_LENGTH));
        entity.setStatus(CommentReport.STATUS_PENDING);
        reportMapper.insert(entity);

        reportMapper.incrementCommentReportCount(commentId);

        // 自增之后重新读一次，拿数据库里的真实值 —— 这次读的是刚写的库，不会脏
        DishComment fresh = commentMapper.selectById(commentId);
        int reportCount = fresh != null && fresh.getReportCount() != null ? fresh.getReportCount() : 1;

        boolean movedToPending = false;
        if (reportCount >= REPORT_AUTO_PENDING_THRESHOLD
                && fresh != null && fresh.getStatus() != null && fresh.getStatus() == COMMENT_STATUS_NORMAL) {
            DishComment update = new DishComment();
            update.setId(commentId);
            update.setStatus(COMMENT_STATUS_PENDING);
            update.setAuditRemark("累计被举报 " + reportCount + " 次，自动转待审核");
            commentMapper.updateById(update);
            movedToPending = true;
            log.info("评论 {} 累计被举报 {} 次，已转待审核", commentId, reportCount);
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reportId", entity.getId());
        view.put("reportCount", reportCount);
        view.put("pending", movedToPending);
        return view;
    }

    // ================================================================== 管理侧

    /** 举报列表（按状态） */
    public PageResult<CommentReportRow> pageReports(int status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int safeStatus = status < 0 || status > 2 ? CommentReport.STATUS_PENDING : status;

        long total = reportMapper.countByStatus(safeStatus);
        List<CommentReportRow> rows = total == 0
                ? List.of()
                : reportMapper.selectRowsByStatus(safeStatus, (safePage - 1) * safeSize, safeSize);
        return PageResult.of(rows, total, safePage, safeSize);
    }

    /**
     * 处理一条举报。
     *
     * @param action {@code remove}=受理并下架评论，{@code reject}=驳回举报
     */
    @Transactional
    public Map<String, Object> handle(long adminId, long reportId, String action, String remark) {
        CommentReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BusinessException("这条举报不存在或已被删除");
        }
        if (report.getStatus() == null || report.getStatus() != CommentReport.STATUS_PENDING) {
            throw new BusinessException("这条举报已经处理过了");
        }

        String act = action == null ? "" : action.trim().toLowerCase();
        String auditRemark = trimToNull(remark, MAX_REASON_LENGTH);

        int closedOthers = 0;
        if ("remove".equals(act)) {
            // 1. 下架评论（不删行：处置依据要留）
            DishComment comment = new DishComment();
            comment.setId(report.getCommentId());
            comment.setStatus(COMMENT_STATUS_HIDDEN);
            comment.setAuditRemark(auditRemark == null ? "举报成立，已下架" : auditRemark);
            commentMapper.updateById(comment);

            // 2. 同一条评论的其他待处理举报一起关掉，否则管理员会把已下架的评论再"下架"一次
            closedOthers = closeOtherPendingReports(report.getCommentId(), adminId, auditRemark);
        }

        CommentReport update = new CommentReport();
        update.setId(reportId);
        update.setStatus("remove".equals(act) ? CommentReport.STATUS_ACCEPTED : CommentReport.STATUS_REJECTED);
        update.setHandlerId(adminId);
        update.setHandleRemark(auditRemark);
        update.setHandleTime(LocalDateTime.now());
        reportMapper.updateById(update);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reportId", reportId);
        view.put("action", "remove".equals(act) ? "remove" : "reject");
        view.put("commentId", report.getCommentId());
        view.put("closedOthers", closedOthers);
        return view;
    }

    /** 待审评论列表 */
    public PageResult<PendingCommentRow> pagePendingComments(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        long total = reportMapper.countPendingComments();
        List<PendingCommentRow> rows = total == 0
                ? List.of()
                : reportMapper.selectPendingComments((safePage - 1) * safeSize, safeSize);
        return PageResult.of(rows, total, safePage, safeSize);
    }

    /**
     * 处置待审评论。
     *
     * @param action {@code approve}=放行（恢复展示），{@code hide}=隐藏
     */
    @Transactional
    public Map<String, Object> reviewComment(long adminId, long commentId, String action, String remark) {
        DishComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException("这条评论不存在或已被删除");
        }
        String act = action == null ? "" : action.trim().toLowerCase();
        if (!"approve".equals(act) && !"hide".equals(act)) {
            throw new BusinessException("处置动作只能是 approve 或 hide");
        }

        String auditRemark = trimToNull(remark, MAX_REASON_LENGTH);
        DishComment update = new DishComment();
        update.setId(commentId);
        update.setStatus("approve".equals(act) ? COMMENT_STATUS_NORMAL : COMMENT_STATUS_HIDDEN);
        update.setAuditRemark(auditRemark == null
                ? ("approve".equals(act) ? "人工审核通过" : "人工审核后隐藏")
                : auditRemark);
        commentMapper.updateById(update);

        // 放行时把还在待处理的举报一起驳回：内容是管理员看过的，举报不再成立
        int closedReports = "approve".equals(act)
                ? closeOtherPendingReports(commentId, adminId, "评论经人工审核后放行，举报驳回")
                : 0;

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("commentId", commentId);
        view.put("action", act);
        view.put("closedReports", closedReports);
        return view;
    }

    // ================================================================== 内部

    private int closeOtherPendingReports(Long commentId, long adminId, String remark) {
        CommentReport update = new CommentReport();
        update.setStatus(CommentReport.STATUS_ACCEPTED);
        update.setHandlerId(adminId);
        update.setHandleRemark(remark);
        update.setHandleTime(LocalDateTime.now());
        return reportMapper.update(update, Wrappers.<CommentReport>lambdaUpdate()
                .eq(CommentReport::getCommentId, commentId)
                .eq(CommentReport::getStatus, CommentReport.STATUS_PENDING));
    }

    private int normalizeReasonType(Integer reasonType) {
        if (reasonType == null) {
            return CommentReport.REASON_OTHER;
        }
        if (reasonType < CommentReport.REASON_OTHER || reasonType > CommentReport.REASON_ILLEGAL) {
            throw new BusinessException("举报理由取值不合法");
        }
        return reasonType;
    }

    private String trimToNull(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
