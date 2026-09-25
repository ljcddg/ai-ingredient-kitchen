package com.apesource.dishservice.mapper;

import com.apesource.dishservice.entity.DishRating;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 评分 Mapper。
 *
 * <p>打分走 upsert：一个人对一道菜只有一行，改分是改那一行而不是新增。
 * 唯一键 {@code uk_dish_user} 是防刷分的最后一道防线。
 */
@Mapper
public interface DishRatingMapper extends BaseMapper<DishRating> {

    /**
     * 打分（首次插入 / 改分覆盖）。
     *
     * <p>并发的两个请求同时打第一次分，会有一个撞唯一键 ——
     * MySQL 的 {@code ON DUPLICATE KEY UPDATE} 会把这种情况收编成"改分"，
     * 所以这里天然是并发安全的，不需要额外的锁。
     *
     * <p>注意这里用了 {@code #{score}} 而不是 {@code VALUES(score)}：
     * 后者在 MySQL 8.0.20+ 已被标记废弃（会有 deprecation 警告），
     * 直接重复传参更简单也更有未来兼容性。
     *
     * @return 受影响行数（1=新插入，2=更新，0=值和原来一样）
     */
    @Insert("""
            INSERT INTO t_dish_rating (id, dish_id, user_id, score, cooked_flag, weight,
                                       deleted, create_time, update_time)
            VALUES (#{id}, #{dishId}, #{userId}, #{score}, #{cookedFlag}, 1.00, 0, NOW(), NOW())
            ON DUPLICATE KEY UPDATE score       = #{score},
                                    cooked_flag = #{cookedFlag},
                                    deleted     = 0,
                                    update_time = NOW()
            """)
    int upsertRating(@Param("id") long id,
                     @Param("dishId") long dishId,
                     @Param("userId") long userId,
                     @Param("score") int score,
                     @Param("cookedFlag") int cookedFlag);
}
