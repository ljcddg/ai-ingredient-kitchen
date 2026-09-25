package com.apesource.dishservice.mapper;

import com.apesource.dishservice.dto.DishDetailView;
import com.apesource.dishservice.entity.DishComment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 评论 Mapper。
 */
@Mapper
public interface DishCommentMapper extends BaseMapper<DishComment> {

    /**
     * 拉某道菜的评论（带昵称），按时间倒序。
     *
     * <p><b>关于这个 JOIN</b>：{@code t_user} 属于 account-service，
     * 严格按微服务划分，这里不该直接读它的表。但两个服务的库是同一个
     * （{@code project0914}），且这里只是只读昵称 —— 先这么办，代价最小。
     * 真要规范化，正确做法是调 account-service 的批量查用户接口，
     * 代价是多一次网络往返 + 要处理它挂掉的情况。
     *
     * <p>直接把结果映射成 {@link DishDetailView.CommentView}，省一个中间 DTO。
     */
    @Select("""
            SELECT c.user_id                                            AS userId,
                   u.nickname                                           AS nickname,
                   c.content                                            AS content,
                   c.rating                                             AS rating,
                   DATE_FORMAT(c.create_time, '%Y-%m-%d %H:%i')         AS createTime
            FROM t_dish_comment c
                     LEFT JOIN t_user u ON u.id = c.user_id
            WHERE c.dish_id = #{dishId}
              AND c.status = 1
              AND c.deleted = 0
            ORDER BY c.create_time DESC
            LIMIT #{limit}
            """)
    List<DishDetailView.CommentView> selectViewsByDish(@Param("dishId") long dishId,
                                                       @Param("limit") int limit);

    /**
     * 取某个用户的昵称 —— 发表评论后要立刻把它显示出来，不能等下一次整体刷新。
     *
     * <p>和上面那个 JOIN 是同一个取舍：跨服务读表，但只是只读一个昵称，代价最小。
     */
    @Select("SELECT IFNULL(nickname, username) FROM t_user WHERE id = #{userId}")
    String selectNickname(@Param("userId") long userId);
}
