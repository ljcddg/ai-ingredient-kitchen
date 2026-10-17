package com.apesource.dishservice.mapper;

import com.apesource.dishservice.dto.AmountStatRow;
import com.apesource.dishservice.dto.QuantityStatRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用量分位统计的只读查询（两张离线统计表）。
 *
 * <p>不继承 {@code BaseMapper}：这两张表由 {@code sql/build-stats.py} 全量重建
 * （先 DELETE 再 REPLACE），服务端没有任何写入路径。继承 BaseMapper 只会让人
 * 误以为这里能改数据，而真正改数据的入口在脚本里 —— 那种"两处都能写"的错觉
 * 是最容易埋坑的地方。
 *
 * <p>SQL 里显式写 AS 别名，不依赖 {@code map-underscore-to-camel-case} 配置：
 * 那个开关配在 Nacos 的公共配置里，本地起服务时未必加载到，别名是自洽的。
 */
@Mapper
public interface AmountStatsMapper {

    @Select("""
            SELECT ingredient_id AS ingredientId,
                   serving_bucket AS bucket,
                   p50, p90,
                   sample_count AS sampleCount
            FROM t_ingredient_amount_stats
            """)
    List<AmountStatRow> selectAmountStats();

    @Select("""
            SELECT category,
                   serving_bucket AS bucket,
                   p50, p90,
                   sample_count AS sampleCount
            FROM t_ingredient_quantity_stats
            """)
    List<QuantityStatRow> selectQuantityStats();
}
