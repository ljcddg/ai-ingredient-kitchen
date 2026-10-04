package com.apesource.dishservice.ai;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.es.DishSearchRepository;
import com.apesource.dishservice.mapper.AiGenerateTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 降级模式（检索引擎不可用）下的生成入口限流测试。
 *
 * <p>守两条：
 * <ol>
 *   <li><b>检索正常时不能有任何影响</b>：不能因为加了这个守卫，正常路径多一次统计查询
 *       甚至被误限流；</li>
 *   <li><b>降级时要真的拦住，并且说清原因</b>：一次 ES 抖动不该把本地模型队列堵死
 *       （队列并发只有 2，单次生成几十秒到几分钟）。</li>
 * </ol>
 */
class DegradedModeGuardTest {

    private DishSearchRepository searchRepository;
    private AiGenerateTaskMapper taskMapper;
    private GenerateProperties properties;
    private DegradedModeGuard guard;

    @BeforeEach
    void setUp() {
        searchRepository = mock(DishSearchRepository.class);
        taskMapper = mock(AiGenerateTaskMapper.class);
        properties = new GenerateProperties();
        properties.setDegradedQuotaPerHour(5);
        guard = new DegradedModeGuard(searchRepository, taskMapper, properties);
    }

    @Test
    @DisplayName("检索正常：直接放行，连统计查询都不发")
    void allowsWhenSearchHealthy() {
        when(searchRepository.ping()).thenReturn(true);

        assertThatCode(() -> guard.checkGenerationQuota(1001L)).doesNotThrowAnyException();

        verify(taskMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("检索不可用且还没到配额：放行（降级不等于关闭）")
    void allowsUnderQuota() {
        when(searchRepository.ping()).thenReturn(false);
        when(taskMapper.selectCount(any())).thenReturn(2L);

        assertThatCode(() -> guard.checkGenerationQuota(1001L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("检索不可用且已达配额：拒绝，并说明原因与已用次数")
    void rejectsOverQuota() {
        when(searchRepository.ping()).thenReturn(false);
        when(taskMapper.selectCount(any())).thenReturn(5L);

        assertThatThrownBy(() -> guard.checkGenerationQuota(1001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已限流")
                .hasMessageContaining("5 次")
                .hasMessageContaining("已用 5 次");
    }

    @Test
    @DisplayName("配额配成 0：等于一键关掉降级期间的生成入口（应急开关）")
    void zeroQuotaBlocksEverything() {
        properties.setDegradedQuotaPerHour(0);
        when(searchRepository.ping()).thenReturn(false);
        when(taskMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> guard.checkGenerationQuota(1001L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("可用性探测本身报错：按不可用处理（拿不准时保守）")
    void treatsProbeFailureAsDegraded() {
        when(searchRepository.ping()).thenThrow(new RuntimeException("connect timed out"));
        when(taskMapper.selectCount(any())).thenReturn(5L);

        assertThatThrownBy(() -> guard.checkGenerationQuota(1001L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("degraded() 对外可查：页面提示也要靠它")
    void exposesDegradedState() {
        when(searchRepository.ping()).thenReturn(false);
        org.assertj.core.api.Assertions.assertThat(guard.degraded()).isTrue();

        when(searchRepository.ping()).thenReturn(true);
        org.assertj.core.api.Assertions.assertThat(guard.degraded()).isFalse();
    }
}
