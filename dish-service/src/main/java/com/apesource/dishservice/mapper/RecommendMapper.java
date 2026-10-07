package com.apesource.dishservice.mapper;

import com.apesource.dishservice.dto.DishCardView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 冷启动推荐池的查询。
 *
 * <p>三件事都在这里：挑候选菜、记推流、算分晋级。
 * 排序逻辑一律交给 SQL，应用层不做二次排序 —— 排序口径必须只有一份，
 * 否则前端看到的顺序和数据库算出来的顺序会各说各话。
 *
 * <p>注意注解里写的是 {@code <script>} 片段，XML 的特殊字符要做转义：
 * {@code <} 写成 {@code &lt;}，{@code >} 写成 {@code &gt;}。
 */
@Mapper
public interface RecommendMapper {

    /**
     * 取候选菜 ID。三级优先级靠参数区分：
     *
     * <ul>
     *   <li>全匹配：{@code minHit = maxHit = ids.size()}</li>
     *   <li>部分匹配：{@code minHit = 1, maxHit = ids.size() - 1}</li>
     *   <li>随机补齐：{@code ids = null}</li>
     * </ul>
     *
     * <p><b>{@code creator_id <> userId}</b>：不把作者自己的菜推给他本人，
     * 否则他刷两下就能给自己刷满 push_count，整个冷启动的计数都不可信了。
     *
     * <p><b>24 小时去重</b>用 NOT EXISTS 子查询，而不是先查出来再在 Java 里过滤 ——
     * 后者要先捞一堆候选再丢掉，白跑一趟。
     */
    @Select("""
            <script>
            SELECT d.id
            FROM t_dish d
            <if test="ids != null and ids.size() &gt; 0">
              JOIN t_dish_ingredient di ON di.dish_id = d.id
            </if>
            WHERE d.deleted = 0 AND d.status = 1 AND d.rank_level IN (1, 2)
              AND (d.creator_id IS NULL OR d.creator_id &lt;&gt; #{userId})
              AND NOT EXISTS (
                    SELECT 1 FROM t_dish_push_log l
                    WHERE l.user_id = #{userId} AND l.dish_id = d.id
                      AND l.push_time &gt; NOW() - INTERVAL 24 HOUR)
              <if test="ids != null and ids.size() &gt; 0">
                AND di.ingredient_id IN
                <foreach collection="ids" item="i" open="(" close=")" separator=",">#{i}</foreach>
              </if>
            <if test="ids != null and ids.size() &gt; 0">
              GROUP BY d.id
              HAVING COUNT(DISTINCT di.ingredient_id) BETWEEN #{minHit} AND #{maxHit}
            </if>
            ORDER BY RAND()
            LIMIT #{limit}
            </script>
            """)
    List<Long> selectCandidateIds(@Param("ids") List<Long> ids,
                                  @Param("minHit") int minHit,
                                  @Param("maxHit") int maxHit,
                                  @Param("userId") Long userId,
                                  @Param("limit") int limit);

    /** 批量给候选菜 +1 推流次数 */
    @Update("""
            <script>
            UPDATE t_dish SET push_count = push_count + 1 WHERE id IN
            <foreach collection="ids" item="i" open="(" close=")" separator=",">#{i}</foreach>
            </script>
            """)
    int addPushCount(@Param("ids") List<Long> ids);

    /** 管理端模拟推流：一次加 N 次，避免演示时手动刷几十次页面 */
    @Update("UPDATE t_dish SET push_count = push_count + #{times} WHERE id = #{dishId}")
    int addPushCountTimes(@Param("dishId") Long dishId, @Param("times") int times);

    /*
     * 浏览计数 addViewCount 已于 2026-10-07 移到 DishMapper。
     * 原因：它原本只有推荐流一个入口在调，导致 view_count（互动率的分母）
     * 只统计推荐来源，而点赞/收藏/打卡统计的是全部入口 —— 互动率系统性虚高。
     * 现在统一由 DishViewCounter 在菜品详情接口里计数，覆盖所有入口。
     */

    @Update("UPDATE t_dish SET like_count = like_count + 1 WHERE id = #{dishId} AND deleted = 0 AND status = 1")
    int addLikeCount(@Param("dishId") Long dishId);

    @Update("UPDATE t_dish SET cook_count = cook_count + 1 WHERE id = #{dishId} AND deleted = 0 AND status = 1")
    int addCookCount(@Param("dishId") Long dishId);

    /**
     * 晋级：推荐池里查看率达标且推流量足够的菜升到 rank_level = 2。
     *
     * <p><b>最后的比较写成 {@code view_count >= push_count * 率}，而不是
     * {@code view_count / push_count >= 率}。</b>
     * MySQL 里两个 INT 相除是整数除法，8/50 等于 0，那样这道菜永远晋不了级 ——
     * 而且不报错，只是静默地什么都不做，极难发现。
     */
    @Update("""
            UPDATE t_dish
            SET rank_level = 2, update_time = NOW()
            WHERE deleted = 0 AND status = 1 AND rank_level = 1
              AND push_count &gt;= #{minPush}
              AND view_count &gt;= #{minViewCount}
              AND view_count &gt;= push_count * #{minRate}
            """)
    int promoteToRank(@Param("minPush") int minPush,
                      @Param("minViewCount") int minViewCount,
                      @Param("minRate") double minRate);

    /**
     * 重算综合得分。公式见 docs/cold-start-design.md §3.2：
     *
     * <pre>
     *   dish_score = (评分归一 × 0.6 × 置信系数 + 互动率 × 0.4) × 100
     * </pre>
     *
     * <p>{@code IF(view_count = 0, 0, ...)} 是防除零：新菜还没人点开时，
     * 不保护的话 dish_score 会变成 NULL，而 NULL 在 ORDER BY 里的位置各版本不一致，
     * 排行榜顺序会莫名其妙。
     */
    @Update("""
            UPDATE t_dish
            SET dish_score = ROUND(
                  ( (rating_avg / 5.0) * 0.6 * LEAST(1.0, rating_count / #{minScoreCount})
                    + IF(view_count = 0, 0,
                         LEAST(1.0, (like_count + collect_count * 2 + cook_count * 3)
                                    / view_count / #{baseline})) * 0.4
                  ) * 100, 2),
                update_time = NOW()
            WHERE deleted = 0 AND status = 1 AND rank_level = 2
            """)
    int recalcDishScore(@Param("minScoreCount") double minScoreCount,
                        @Param("baseline") double baseline);

    /** 按 ID 取卡片（组装推荐流用）。只取一屏用得上的列 */
    @Select("""
            <script>
            SELECT id, name, summary, cover_url AS coverUrl, source, status,
                   difficulty, cook_time_min AS cookTimeMin, creator_id AS creatorId,
                   rank_level AS rankLevel, dish_score AS dishScore,
                   rating_avg AS ratingAvg, rating_count AS ratingCount,
                   collect_count AS collectCount, like_count AS likeCount,
                   cook_count AS cookCount, push_count AS pushCount,
                   view_count AS viewCount
            FROM t_dish WHERE id IN
            <foreach collection="ids" item="i" open="(" close=")" separator=",">#{i}</foreach>
            </script>
            """)
    List<DishCardView> selectCardsByIds(@Param("ids") List<Long> ids);

    /** 冷启动排行榜：按综合得分降序，同分再看查看数 */
    @Select("""
            SELECT id, name, summary, cover_url AS coverUrl, source, status,
                   difficulty, cook_time_min AS cookTimeMin, creator_id AS creatorId,
                   rank_level AS rankLevel, dish_score AS dishScore,
                   rating_avg AS ratingAvg, rating_count AS ratingCount,
                   collect_count AS collectCount, like_count AS likeCount,
                   cook_count AS cookCount, push_count AS pushCount,
                   view_count AS viewCount
            FROM t_dish
            WHERE deleted = 0 AND status = 1 AND rank_level = 2
            ORDER BY dish_score DESC, view_count DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<DishCardView> selectColdRank(@Param("limit") int limit, @Param("offset") int offset);

    /** 冷启动榜总数 */
    @Select("SELECT COUNT(*) FROM t_dish WHERE deleted = 0 AND status = 1 AND rank_level = 2")
    long countColdRank();
}
