package com.apesource.dishservice.service;

import com.apesource.dishservice.dto.AmountStatRow;
import com.apesource.dishservice.dto.QuantityStatRow;
import com.apesource.dishservice.mapper.AmountStatsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用量统计出口的降级测试。
 *
 * <p>它守的是一条容易被忽略的线：<b>统计是增强，不是生成的前提</b>。
 * 表没建（新环境还没跑 migration）、脚本没跑过、库抖了一下 ——
 * 这些情况下生成必须照常可用，只是校验退回到固定上限。
 * 一旦这里抛异常，整条生成链路会因为一张"可选的派生表"而挂掉，
 * 而且报错信息（Unknown table）跟用户看到的"AI 生成失败"完全对不上。
 */
class AmountStatsServiceTest {

    private AmountStatsMapper mapper;
    private AmountStatsService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AmountStatsMapper.class);
        service = new AmountStatsService(mapper);
    }

    @Test
    @DisplayName("正常：两张统计表原样透出")
    void returnsRows() {
        when(mapper.selectAmountStats()).thenReturn(List.of(
                new AmountStatRow(101L, "1-2", new BigDecimal("150"), new BigDecimal("200"), 17)));
        when(mapper.selectQuantityStats()).thenReturn(List.of(
                new QuantityStatRow("蔬菜", "1-2", new BigDecimal("150"), new BigDecimal("250"), 105)));

        assertThat(service.amountStats()).hasSize(1);
        assertThat(service.quantityStats()).hasSize(1);
        assertThat(service.amountStats().get(0).p90()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("表不存在（没跑 migration）：返回空列表，绝不外抛")
    void degradesWhenTableMissing() {
        when(mapper.selectAmountStats())
                .thenThrow(new RuntimeException("Table 'project0914.t_ingredient_amount_stats' doesn't exist"));
        when(mapper.selectQuantityStats())
                .thenThrow(new RuntimeException("Table 'project0914.t_ingredient_quantity_stats' doesn't exist"));

        assertThat(service.amountStats()).isEmpty();
        assertThat(service.quantityStats()).isEmpty();
    }
}
