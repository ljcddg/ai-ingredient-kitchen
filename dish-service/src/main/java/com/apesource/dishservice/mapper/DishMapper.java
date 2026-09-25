package com.apesource.dishservice.mapper;

import com.apesource.dishservice.dto.DishCardView;
import com.apesource.dishservice.entity.Dish;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 菜品表 Mapper。
 *
 * <p>提醒：查推荐池时<b>必须带 {@code status = 1}</b>，
 * 漏掉就会把草稿泄漏给所有人 —— 这是数据安全级别的问题，不是体验问题。
 * 下面三个榜单查询都带了这个条件。
 */
@Mapper
public interface DishMapper extends BaseMapper<Dish> {

    /*
     * 三个榜单查询放在这里，有几个共同的取舍要说明。
     *
     * 【为什么手写 SQL 而不是用 MP 的 Wrapper】
     * 热门榜要按「收藏数 × 3 + 评价数 × 2」这个**计算出来的表达式**排序，
     * Wrapper 只能按字段排（orderByDesc(Dish::getCollectCount)）。
     * 拆成"先按收藏排、收藏相同再按评价排"看着差不多，但语义是不一样的：
     * 一道 100 收藏 0 评价的菜会排在 90 收藏 80 评价的菜前面，而后者明显更热。
     *
     * 【为什么手写 SQL 要自己写 deleted = 0】
     * {@code @TableLogic} 只在 MP 生成的 SQL 里生效；注解里手写的 SQL 它管不到。
     * 漏了这一步，被逻辑删除的菜会照样出现在榜单里。
     *
     * 【为什么只 SELECT 需要的列，而不是 SELECT *】
     * ① t_dish 有四个 JSON 字段（step_json / seasonings / ...），
     *    它们的 JacksonTypeHandler 依赖实体上的 resultMap；
     *    用注解手写 SQL 时拿到的是默认映射，这些字段读出来是 null，
     *    白白浪费带宽。
     * ② 榜单一次要取 20 条，列少了传输量差别很可观。
     */

    /**
     * 热门榜：收藏 × 3 + 评价数 × 2，再按发布时间兜底。
     *
     * <p>权重为什么这么给：收藏是"我想做"，是用户用行动投的票；
     * 评价是"我做过了"，两者都算热度，但收藏的意图更直接。
     */
    @Select("""
            SELECT id, name, summary, cover_url AS coverUrl, source, status,
                   difficulty, prep_time_min AS prepTimeMin, cook_time_min AS cookTimeMin,
                   nutrition_source AS nutritionSource,
                   ROUND(energy_kcal) AS energyKcal,
                   rating_avg AS ratingAvg, rating_count AS ratingCount,
                   collect_count AS collectCount,
                   DATE_FORMAT(create_time, '%Y-%m-%d') AS createTime
            FROM t_dish
            WHERE deleted = 0 AND status = 1
            ORDER BY (collect_count * 3 + rating_count * 2) DESC, publish_time DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<DishCardView> selectHotRank(@Param("limit") int limit, @Param("offset") int offset);

    /**
     * 高分榜：按**威尔逊下界**排序，不是平均分。
     *
     * <p>这是这个榜单最关键的一条。用平均分排的话，1 个人打 5 分（平均分 5.0）
     * 会稳稳压过 100 个人打 4.8 分的菜 —— 榜单头部会被"只有一两条评价的菜"占满，
     * 而且这正是刷分的动力来源。
     * 威尔逊下界对小样本自动向下惩罚，人少时分数被压得很低，评价多了才升上来。
     *
     * <p>{@code rating_count >= 1} 是在此基础上再加一道：一条评价都没有的菜
     * 威尔逊值是 0，本来也排不上来，加上这个条件可以让 SQL 少扫一批行。
     */
    @Select("""
            SELECT id, name, summary, cover_url AS coverUrl, source, status,
                   difficulty, prep_time_min AS prepTimeMin, cook_time_min AS cookTimeMin,
                   nutrition_source AS nutritionSource,
                   ROUND(energy_kcal) AS energyKcal,
                   rating_avg AS ratingAvg, rating_count AS ratingCount,
                   collect_count AS collectCount,
                   DATE_FORMAT(create_time, '%Y-%m-%d') AS createTime
            FROM t_dish
            WHERE deleted = 0 AND status = 1 AND rating_count >= 1
            ORDER BY rating_wilson DESC, rating_count DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<DishCardView> selectTopRank(@Param("limit") int limit, @Param("offset") int offset);

    /** 最新榜：按发布时间倒序 —— 给"刚做出来的菜"一个曝光机会 */
    @Select("""
            SELECT id, name, summary, cover_url AS coverUrl, source, status,
                   difficulty, prep_time_min AS prepTimeMin, cook_time_min AS cookTimeMin,
                   nutrition_source AS nutritionSource,
                   ROUND(energy_kcal) AS energyKcal,
                   rating_avg AS ratingAvg, rating_count AS ratingCount,
                   collect_count AS collectCount,
                   DATE_FORMAT(create_time, '%Y-%m-%d') AS createTime
            FROM t_dish
            WHERE deleted = 0 AND status = 1
            ORDER BY publish_time DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<DishCardView> selectLatestRank(@Param("limit") int limit, @Param("offset") int offset);

    /** 高分榜的计数要带 {@code rating_count >= 1}，和上面那条查询保持一致，否则分页会多出一页空数据 */
    @Select("""
            SELECT COUNT(*) FROM t_dish
            WHERE deleted = 0 AND status = 1 AND rating_count >= 1
            """)
    long countRated();
}
