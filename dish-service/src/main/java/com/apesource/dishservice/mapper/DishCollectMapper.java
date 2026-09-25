package com.apesource.dishservice.mapper;

import com.apesource.dishservice.dto.DishCardView;
import com.apesource.dishservice.entity.DishCollect;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 收藏 Mapper。
 *
 * <p>本表的写操作<b>全部手写 SQL，不用 BaseMapper 的 insert/deleteById</b>，
 * 原因见下面 {@link #restoreCollect} 的注释。
 */
@Mapper
public interface DishCollectMapper extends BaseMapper<DishCollect> {

    /**
     * 收藏 —— 实现成「恢复式」upsert。
     *
     * <p><b>为什么不能直接 insert：</b>取消收藏是把 {@code deleted} 置 1（逻辑删除），
     * 记录本身还在。用户再点一次收藏时，{@code INSERT} 会撞上
     * {@code uk_dish_user(dish_id, user_id)} 直接报唯一键冲突 —— 这是典型的上线后才炸的坑。
     *
     * <p><b>但这里的返回值不要拿来判断"这次是不是真的收藏上了"。</b>
     * 原因有两层，叠在一起就彻底不可靠了：
     * <ol>
     *   <li>{@code create_time} 必须写成 {@code IF(deleted = 1, NOW(), create_time)}。
     *       若无条件写 {@code NOW()}，对一条<i>本来就已收藏</i>的记录 create_time 也会变，
     *       MySQL 就认为"这行被更新了"，返回值变成 2（"真的改了值"）——
     *       哪怕用户只是重复点了一下。注意子句里的赋值<b>顺序有意义</b>：
     *       后面的表达式看得到前面赋值后的结果，所以必须先用 deleted 的旧值算
     *       create_time，最后再把它置 0；顺序反了 IF 就永远为真，等于没写条件。</li>
     *   <li>就算上面写对了也还不够 —— MySQL Connector/J 默认启用 CLIENT_FOUND_ROWS
     *       （{@code useAffectedRows=false}），此时"把已有行设为相同值"返回的是
     *       <b>1 而不是 0</b>，跟"新插入"完全撞在一起，从返回值上根本分不出来。
     *       更坑的是这个差异<b>在 mysql 命令行里复现不出来</b>（命令行不开那个标志），
     *       所以会出现"本地手工测没问题、服务跑起来计数就是虚增"。</li>
     * </ol>
     * 结论：收藏数由 {@code DishInteractionService#refreshCollectCount} 按明细表重算，
     * 不依赖这里的返回值。本方法只负责把状态改成"已收藏"。
     *
     * @return 受影响行数，仅供日志参考，<b>不要用于业务判断</b>
     */
    @Insert("""
            INSERT INTO t_dish_collect (id, dish_id, user_id, deleted, create_time)
            VALUES (#{id}, #{dishId}, #{userId}, 0, NOW())
            ON DUPLICATE KEY UPDATE create_time = IF(deleted = 1, NOW(), create_time),
                                    deleted     = 0
            """)
    int restoreCollect(@Param("id") long id,
                       @Param("dishId") long dishId,
                       @Param("userId") long userId);

    /**
     * 取消收藏（逻辑删除）。
     *
     * <p>带了 {@code deleted = 0} 条件，所以重复取消不会重复生效。
     * 返回值同样<b>不用于业务判断</b> —— 收藏数由明细表重算，
     * 这样即使这个返回值因为驱动配置而口径不同，也不影响计数正确性。
     *
     * @return 1 表示这次真的改了行；0 表示本来就不在收藏状态
     */
    @Update("""
            UPDATE t_dish_collect
            SET deleted = 1
            WHERE dish_id = #{dishId} AND user_id = #{userId} AND deleted = 0
            """)
    int softUncollect(@Param("dishId") long dishId, @Param("userId") long userId);

    /** 我有没有收藏这道菜 */
    @Select("""
            SELECT COUNT(*)
            FROM t_dish_collect
            WHERE dish_id = #{dishId} AND user_id = #{userId} AND deleted = 0
            """)
    int existsCollect(@Param("dishId") long dishId, @Param("userId") long userId);

    /**
     * 我的收藏列表。
     *
     * <p>JOIN {@code t_dish} 时带了 {@code d.deleted = 0} —— 菜品被删掉后，
     * 收藏记录还留着，不加这个条件就会出现"点进去说菜品不存在"的幽灵条目。
     *
     * <p>{@code ROUND()} 在 SQL 里做，是为了让 DTO 能安心声明成 Integer/Double，
     * 不用依赖驱动对 DECIMAL 的隐式转换。
     */
    @Select("""
            SELECT d.id                                            AS id,
                   d.name                                          AS name,
                   d.summary                                       AS summary,
                   d.cover_url                                     AS coverUrl,
                   d.source                                        AS source,
                   d.status                                        AS status,
                   d.difficulty                                    AS difficulty,
                   d.prep_time_min                                 AS prepTimeMin,
                   d.cook_time_min                                 AS cookTimeMin,
                   d.nutrition_source                              AS nutritionSource,
                   ROUND(d.energy_kcal)                            AS energyKcal,
                   ROUND(d.rating_avg, 1)                          AS ratingAvg,
                   d.rating_count                                  AS ratingCount,
                   d.collect_count                                 AS collectCount,
                   DATE_FORMAT(d.create_time, '%Y-%m-%d %H:%i')    AS createTime,
                   DATE_FORMAT(c.create_time, '%Y-%m-%d %H:%i')    AS markedTime
            FROM t_dish_collect c
                     JOIN t_dish d ON d.id = c.dish_id AND d.deleted = 0
            WHERE c.user_id = #{userId}
              AND c.deleted = 0
            ORDER BY c.create_time DESC
            LIMIT #{limit}
            """)
    List<DishCardView> selectMyCollects(@Param("userId") long userId, @Param("limit") int limit);
}
