package com.apesource.dishservice.mapper;

import com.apesource.dishservice.entity.SensitiveWord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 违规词 Mapper。
 *
 * <p>查询走 MyBatis-Plus 的 wrapper 就够了；这里只多一个「命中计数 +1」——
 * 它是并发的（同一条评论可能被多线程同时扫到同一个词），
 * 用 {@code hit_count = hit_count + 1} 让数据库自己算，
 * 而不是先读出来再写回去（读改写会丢更新，且多一次往返）。
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {

    /** 命中计数 +1。不参与业务判断，失败也不该影响评论发布 */
    @Update("UPDATE t_sensitive_word SET hit_count = hit_count + 1 WHERE word = #{word} AND deleted = 0")
    int incrementHitCount(@Param("word") String word);
}
