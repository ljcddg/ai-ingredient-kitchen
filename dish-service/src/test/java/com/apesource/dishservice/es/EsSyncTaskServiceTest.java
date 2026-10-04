package com.apesource.dishservice.es;

import com.apesource.dishservice.entity.EsSyncTask;
import com.apesource.dishservice.mapper.EsSyncTaskMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引同步待办的存取与退避逻辑测试。
 *
 * <p>这里最值得测的是<b>退避与放弃</b>：写错了会变成两种事故 ——
 * 退避算短了，ES 挂着的时候会把日志刷爆、把连接池耗光；
 * 忘记放弃，一条坏数据会永远重试，噪音里再也看不出真正的问题。
 */
class EsSyncTaskServiceTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        // LambdaUpdateWrapper 需要 TableInfo 缓存才能把方法引用解析成列名
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, EsSyncTask.class);
    }

    private EsSyncTaskMapper mapper;
    private EsSyncTaskService service;

    @BeforeEach
    void setUp() {
        mapper = mock(EsSyncTaskMapper.class);
        service = new EsSyncTaskService(mapper);
    }

    private EsSyncTask task(int attempts) {
        EsSyncTask t = new EsSyncTask();
        t.setId(1L);
        t.setDishId(9001L);
        t.setAttempts(attempts);
        t.setStatus(EsSyncTask.STATUS_PENDING);
        return t;
    }

    /** 抓取第 index 次（0 起）update 里写进去的值（MP 把它们放在 paramNameValuePairs 里） */
    private AbstractWrapper<EsSyncTask, ?, ?> captureUpdateAt(int index) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<EsSyncTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        // 注意：verify 默认要求"恰好一次"，多次调用的用例必须按次数断言
        verify(mapper, times(index + 1)).update(isNull(), captor.capture());
        return (AbstractWrapper<EsSyncTask, ?, ?>) captor.getAllValues().get(index);
    }

    private AbstractWrapper<EsSyncTask, ?, ?> captureUpdate() {
        return captureUpdateAt(0);
    }

    private LocalDateTime capturedNextRetry(AbstractWrapper<EsSyncTask, ?, ?> wrapper) {
        return wrapper.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof LocalDateTime)
                .map(v -> (LocalDateTime) v)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new AssertionError("没有写入 next_retry_at"));
    }

    // ------------------------------------------------------------------ 退避

    @Test
    @DisplayName("第一次失败：约 5 秒后重试，状态为失败待重试")
    void firstFailureBacksOffFiveSeconds() {
        boolean retry = service.markFailed(task(0), "connection refused");

        assertThat(retry).isTrue();
        AbstractWrapper<EsSyncTask, ?, ?> wrapper = captureUpdate();
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("状态应为失败待重试").contains(EsSyncTask.STATUS_FAILED);
        long seconds = Duration.between(LocalDateTime.now(), capturedNextRetry(wrapper)).getSeconds();
        assertThat(seconds).isBetween(3L, 7L);
    }

    @Test
    @DisplayName("退避逐次拉长：第二次失败约 15 秒，第三次约 45 秒")
    void backoffGrows() {
        service.markFailed(task(1), "e");
        long second = Duration.between(LocalDateTime.now(), capturedNextRetry(captureUpdateAt(0))).getSeconds();
        assertThat(second).isBetween(13L, 17L);

        service.markFailed(task(2), "e");
        long third = Duration.between(LocalDateTime.now(), capturedNextRetry(captureUpdateAt(1))).getSeconds();
        assertThat(third).isBetween(43L, 47L);
    }

    @Test
    @DisplayName("尝试次数用尽：置为已放弃并返回 false（不再重试，才值得告警）")
    void givesUpAfterMaxAttempts() {
        boolean retry = service.markFailed(task(EsSyncTaskService.MAX_ATTEMPTS - 1), "still down");

        assertThat(retry).isFalse();
        AbstractWrapper<EsSyncTask, ?, ?> wrapper = captureUpdate();
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("状态应为已放弃").contains(EsSyncTask.STATUS_GIVEN_UP);
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("尝试次数应记到上限").contains(EsSyncTaskService.MAX_ATTEMPTS);
    }

    // ------------------------------------------------------------------ 状态流转

    @Test
    @DisplayName("成功：标记为已完成、清掉错误信息，并把「已尝试次数」加一")
    void markDone() {
        service.markDone(1L);

        AbstractWrapper<EsSyncTask, ?, ?> wrapper = captureUpdate();
        assertThat(wrapper.getParamNameValuePairs().values()).contains(EsSyncTask.STATUS_DONE);
        assertThat(wrapper.getSqlSet())
                .as("这一列是「试了几次」而不是「失败几次」，成功也要 +1")
                .contains("attempts = attempts + 1");
    }

    @Test
    @DisplayName("手动补偿：把失败与放弃的待办重置为待同步（attempts 清零、立刻可执行）")
    void retryFailedNowResetsBoth() {
        when(mapper.update(isNull(), any())).thenReturn(3);

        int reset = service.retryFailedNow();

        assertThat(reset).isEqualTo(3);
        AbstractWrapper<EsSyncTask, ?, ?> wrapper = captureUpdate();
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("重置成待同步 + 次数清零")
                .contains(EsSyncTask.STATUS_PENDING)
                .contains(0);
    }

    // ------------------------------------------------------------------ 登记（幂等合并）

    @Test
    @DisplayName("首次登记：插入一条待同步")
    void enqueueInsertsWhenAbsent() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.enqueue(9001L, LocalDateTime.now());

        ArgumentCaptor<EsSyncTask> captor = ArgumentCaptor.forClass(EsSyncTask.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getDishId()).isEqualTo(9001L);
        assertThat(captor.getValue().getStatus()).isEqualTo(EsSyncTask.STATUS_PENDING);
        assertThat(captor.getValue().getAttempts()).isZero();
    }

    @Test
    @DisplayName("重复登记：刷新已有那条，而不是堆出第二条（唯一键 uk_dish）")
    void enqueueRefreshesWhenPresent() {
        when(mapper.selectOne(any())).thenReturn(task(4));

        service.enqueue(9001L, LocalDateTime.now());

        verify(mapper, times(0)).insert(any(EsSyncTask.class));
        AbstractWrapper<EsSyncTask, ?, ?> wrapper = captureUpdate();
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("重新变成待同步、次数清零")
                .contains(EsSyncTask.STATUS_PENDING)
                .contains(0);
    }

    @Test
    @DisplayName("并发登记撞唯一键：退化成更新，不抛异常")
    void enqueueToleratesDuplicateKey() {
        when(mapper.selectOne(any())).thenReturn(null, task(0));
        when(mapper.insert(any(EsSyncTask.class)))
                .thenThrow(new RuntimeException("Duplicate entry for key 'uk_dish'"));

        service.enqueue(9001L, LocalDateTime.now());

        verify(mapper).update(isNull(), any());
    }
}
