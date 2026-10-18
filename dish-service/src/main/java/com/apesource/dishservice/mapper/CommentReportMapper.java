package com.apesource.dishservice.mapper;

import com.apesource.dishservice.dto.CommentReportRow;
import com.apesource.dishservice.dto.PendingCommentRow;
import com.apesource.dishservice.entity.CommentReport;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 举报 Mapper。
 *
 * <p>审核列表要一次拿到「举报 + 评论原文 + 举报人 + 评论作者 + 菜品名」，
 * 分五次查会变成 N+1；所以这里用联表一次成形。
 *
 * <p>和 {@code DishCommentMapper} 里读 {@code t_user} 是同一个取舍：
 * 跨服务的表按规范不该直读，但两库同属 {@code project0914}、
 * 这里只读昵称，直读的代价最小；真要规范化应改成批量调 account-service。
 */
@Mapper
public interface CommentReportMapper extends BaseMapper<CommentReport> {

    /** 某个状态下的举报，按提交时间倒序 */
    @Select("""
            SELECT r.id                                            AS id,
                   r.comment_id                                    AS commentId,
                   r.dish_id                                       AS dishId,
                   d.name                                          AS dishName,
                   c.content                                       AS commentContent,
                   cu.nickname                                     AS commentAuthor,
                   c.status                                        AS commentStatus,
                   c.hit_words                                     AS hitWords,
                   c.report_count                                  AS reportCount,
                   r.reporter_id                                   AS reporterId,
                   u.nickname                                      AS reporterName,
                   r.reason_type                                   AS reasonType,
                   r.reason                                        AS reason,
                   r.status                                        AS status,
                   r.handle_remark                                 AS handleRemark,
                   DATE_FORMAT(r.handle_time, '%Y-%m-%d %H:%i')    AS handleTime,
                   DATE_FORMAT(r.create_time, '%Y-%m-%d %H:%i')    AS createTime
            FROM t_comment_report r
                     LEFT JOIN t_dish_comment c ON c.id = r.comment_id
                     LEFT JOIN t_dish d ON d.id = r.dish_id
                     LEFT JOIN t_user u ON u.id = r.reporter_id
                     LEFT JOIN t_user cu ON cu.id = c.user_id
            WHERE r.deleted = 0
              AND r.status = #{status}
            ORDER BY r.create_time DESC
            LIMIT #{offset}, #{size}
            """)
    List<CommentReportRow> selectRowsByStatus(@Param("status") int status,
                                              @Param("offset") int offset,
                                              @Param("size") int size);

    @Select("""
            SELECT COUNT(*)
            FROM t_comment_report
            WHERE deleted = 0 AND status = #{status}
            """)
    long countByStatus(@Param("status") int status);

    /** 待审评论：命中拦截词或被举报超阈值的那些 */
    @Select("""
            SELECT c.id                                            AS id,
                   c.dish_id                                       AS dishId,
                   d.name                                          AS dishName,
                   c.user_id                                       AS userId,
                   u.nickname                                      AS nickname,
                   c.content                                       AS content,
                   c.hit_words                                     AS hitWords,
                   c.report_count                                  AS reportCount,
                   DATE_FORMAT(c.create_time, '%Y-%m-%d %H:%i')    AS createTime
            FROM t_dish_comment c
                     LEFT JOIN t_dish d ON d.id = c.dish_id
                     LEFT JOIN t_user u ON u.id = c.user_id
            WHERE c.deleted = 0
              AND c.status = 0
            ORDER BY c.create_time DESC
            LIMIT #{offset}, #{size}
            """)
    List<PendingCommentRow> selectPendingComments(@Param("offset") int offset,
                                                  @Param("size") int size);

    @Select("""
            SELECT COUNT(*)
            FROM t_dish_comment
            WHERE deleted = 0 AND status = 0
            """)
    long countPendingComments();

    /**
     * 评论被举报后计数 +1。
     * <p>用 {@code + 1} 让数据库算，避免「读出来 +1 再写回」在并发下丢更新。
     */
    @Update("UPDATE t_dish_comment SET report_count = report_count + 1 WHERE id = #{commentId} AND deleted = 0")
    int incrementCommentReportCount(@Param("commentId") Long commentId);
}
