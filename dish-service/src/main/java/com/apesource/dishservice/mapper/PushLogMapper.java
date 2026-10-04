package com.apesource.dishservice.mapper;

import com.apesource.dishservice.entity.DishPushLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 推流日志 Mapper。
 *
 * <p>只有批量 upsert 一个手写方法：推荐流一次返回 10 道菜，
 * 逐条 INSERT 会把一次请求变成十次往返。
 */
@Mapper
public interface PushLogMapper extends BaseMapper<DishPushLog> {

    /**
     * 批量写推流日志。已存在的一行只刷新时间，不新增。
     *
     * <p>{@code VALUES(push_time)} 取的是本次要插入的值，
     * 这样一条 SQL 就能同时处理"新推的"和"重复推的"。
     */
    @Insert("""
            <script>
            INSERT INTO t_dish_push_log (id, dish_id, user_id, push_time) VALUES
            <foreach collection="logs" item="l" separator=",">
                (#{l.id}, #{l.dishId}, #{l.userId}, #{l.pushTime})
            </foreach>
            ON DUPLICATE KEY UPDATE push_time = VALUES(push_time)
            </script>
            """)
    int batchUpsert(@Param("logs") List<DishPushLog> logs);
}
