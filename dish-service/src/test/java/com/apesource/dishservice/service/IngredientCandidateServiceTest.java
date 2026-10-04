package com.apesource.dishservice.service;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.entity.Ingredient;
import com.apesource.dishservice.entity.IngredientCandidate;
import com.apesource.dishservice.es.EsSyncTaskService;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.IngredientCandidateMapper;
import com.apesource.dishservice.mapper.IngredientMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 食材候选审核流程的单元测试。
 *
 * <p>守住的是这次改动的核心承诺：<b>"出现次数多"不再等于"可以进主数据"</b>。
 * 所以用例围着三件事转：
 * <ul>
 *   <li>通过时必须写主数据 + 回填历史明细 + 留下审核人/时间/理由；</li>
 *   <li>拒绝时必须带理由，且<b>绝不碰主数据</b>；</li>
 *   <li>已经被处理过的候选不能被重复处理（条件更新影响 0 行 → 计入 skipped，不报错）。</li>
 * </ul>
 *
 * <p>纯 Mockito，不启动 Spring：审核逻辑不该依赖 Nacos/Redis，
 * 否则这些用例会因为"本地没起注册中心"而跑不起来，慢慢就没人跑了。
 */
class IngredientCandidateServiceTest {

    private static final long REVIEWER = 1001L;
    private static final long CANDIDATE_ID = 7001L;

    private IngredientCandidateMapper candidateMapper;
    private IngredientMapper ingredientMapper;
    private DishIngredientMapper dishIngredientMapper;
    private EsSyncTaskService esSyncTaskService;
    private ApplicationEventPublisher eventPublisher;
    private IngredientCandidateService service;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        // MyBatis-Plus 的 LambdaUpdateWrapper 要靠 TableInfo 缓存，
        // 才能把 `IngredientCandidate::getStatus` 这样的方法引用解析成列名 `status`；
        // 而这个缓存平时是 MyBatis 扫描 Mapper 时建立的。
        // 纯 Mockito 测试不启动 MyBatis，所以这里手动初始化一遍 ——
        // 不初始化的话，第一次调用 .set(...) 就会抛
        // "can not find lambda cache for this entity"。
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IngredientCandidate.class);
        TableInfoHelper.initTableInfo(assistant, Ingredient.class);
        TableInfoHelper.initTableInfo(assistant, DishIngredient.class);
    }

    @BeforeEach
    void setUp() {
        candidateMapper = mock(IngredientCandidateMapper.class);
        ingredientMapper = mock(IngredientMapper.class);
        dishIngredientMapper = mock(DishIngredientMapper.class);
        esSyncTaskService = mock(EsSyncTaskService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new IngredientCandidateService(
                candidateMapper, ingredientMapper, dishIngredientMapper,
                esSyncTaskService, eventPublisher);
    }

    private IngredientCandidate candidate(int status) {
        IngredientCandidate c = new IngredientCandidate();
        c.setId(CANDIDATE_ID);
        c.setName("香茅");
        c.setNameKey("香茅");
        c.setUseCount(5);
        c.setStatus(status);
        return c;
    }

    private DishIngredient detail(long id, long dishId) {
        DishIngredient d = new DishIngredient();
        d.setId(id);
        d.setDishId(dishId);
        d.setIngredientName("香茅");
        return d;
    }

    // ------------------------------------------------------------------ 通过

    @Test
    @DisplayName("审核通过：写主数据 + 回填历史明细 + 重写索引，并留下审核人/时间/理由")
    void approvePromotesAndAudits() {
        when(candidateMapper.selectById(CANDIDATE_ID))
                .thenReturn(candidate(IngredientCandidate.STATUS_CANDIDATE));
        when(candidateMapper.update(isNull(), any())).thenReturn(1);
        // 主数据里没有同名食材 → 需要新建，并模拟 MyBatis-Plus 回填雪花ID
        when(ingredientMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> {
            ((Ingredient) inv.getArgument(0)).setId(5001L);
            return 1;
        }).when(ingredientMapper).insert(any(Ingredient.class));
        // 两条历史明细等着回填
        when(dishIngredientMapper.selectList(any()))
                .thenReturn(List.of(detail(11L, 9001L), detail(12L, 9002L)));

        IngredientCandidateService.ReviewReport report =
                service.approve(List.of(CANDIDATE_ID), REVIEWER, "确认是常用食材");

        assertThat(report.approved()).isEqualTo(1);
        assertThat(report.skipped()).isZero();
        assertThat(report.refilledRows()).isEqualTo(2);
        assertThat(report.touchedDishes()).isEqualTo(2);
        assertThat(report.queuedDishes()).isEqualTo(2);

        // 主数据必须被写：这是"转正"的本体
        ArgumentCaptor<Ingredient> inserted = ArgumentCaptor.forClass(Ingredient.class);
        verify(ingredientMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getName()).isEqualTo("香茅");

        // 回填两条明细的 ingredient_id
        verify(dishIngredientMapper, org.mockito.Mockito.times(2)).update(isNull(), any());

        // 审核留痕：status / reviewed_by / review_time / review_reason 必须都在 SET 子句里
        ArgumentCaptor<LambdaUpdateWrapper<IngredientCandidate>> wrapper =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(candidateMapper, org.mockito.Mockito.atLeastOnce()).update(isNull(), wrapper.capture());
        String claimSet = wrapper.getAllValues().get(0).getSqlSet();
        assertThat(claimSet)
                .as("审核留痕的四个字段都要写进去")
                .contains("status")
                .contains("reviewed_by")
                .contains("review_time")
                .contains("review_reason");
    }

    @Test
    @DisplayName("同名的正式食材已存在：认领它，不重复插入主数据")
    void approveAdoptsExistingMasterData() {
        when(candidateMapper.selectById(CANDIDATE_ID))
                .thenReturn(candidate(IngredientCandidate.STATUS_CANDIDATE));
        when(candidateMapper.update(isNull(), any())).thenReturn(1);
        Ingredient existing = new Ingredient();
        existing.setId(4242L);
        existing.setName("香茅");
        when(ingredientMapper.selectOne(any())).thenReturn(existing);
        when(dishIngredientMapper.selectList(any())).thenReturn(List.of());

        IngredientCandidateService.ReviewReport report =
                service.approve(List.of(CANDIDATE_ID), REVIEWER, null);

        assertThat(report.approved()).isEqualTo(1);
        verify(ingredientMapper, never()).insert(any(Ingredient.class));
    }

    @Test
    @DisplayName("已被处理过的候选：跳过而不是报错（管理页面两人同时点通过是常事）")
    void approveSkipsAlreadyReviewed() {
        when(candidateMapper.selectById(CANDIDATE_ID))
                .thenReturn(candidate(IngredientCandidate.STATUS_REJECTED));
        // 条件更新影响 0 行 —— 状态已经不是「候选」了
        when(candidateMapper.update(isNull(), any())).thenReturn(0);

        IngredientCandidateService.ReviewReport report =
                service.approve(List.of(CANDIDATE_ID), REVIEWER, null);

        assertThat(report.approved()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        verify(ingredientMapper, never()).insert(any(Ingredient.class));
    }

    // ------------------------------------------------------------------ 拒绝

    @Test
    @DisplayName("审核拒绝：必须带理由，且绝不写主数据")
    void rejectRequiresReasonAndNeverTouchesMasterData() {
        when(candidateMapper.update(isNull(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.reject(List.of(CANDIDATE_ID), REVIEWER, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须填写理由");

        verify(candidateMapper, never()).update(isNull(), any());
        verify(ingredientMapper, never()).insert(any(Ingredient.class));
    }

    @Test
    @DisplayName("拒绝成功：状态改成已拒绝并留下理由")
    void rejectMarksRejectedWithReason() {
        when(candidateMapper.update(isNull(), any())).thenReturn(1);

        IngredientCandidateService.ReviewReport report =
                service.reject(List.of(CANDIDATE_ID), REVIEWER, "这是错别字");

        assertThat(report.rejected()).isEqualTo(1);
        assertThat(report.approved()).isZero();

        ArgumentCaptor<LambdaUpdateWrapper<IngredientCandidate>> wrapper =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(candidateMapper).update(isNull(), wrapper.capture());
        assertThat(wrapper.getValue().getSqlSet())
                .contains("status")
                .contains("reviewed_by")
                .contains("review_reason");
        // 拒绝不该碰主数据
        verify(ingredientMapper, never()).insert(any(Ingredient.class));
        verify(dishIngredientMapper, never()).update(isNull(), any());
    }

    // ------------------------------------------------------------------ 入参与计数

    @Test
    @DisplayName("空列表 / 全 null：直接拒绝，不产生任何写操作")
    void emptySelectionRejected() {
        assertThatThrownBy(() -> service.approve(List.of(), REVIEWER, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reject(java.util.Arrays.asList(null, null), REVIEWER, "理由"))
                .isInstanceOf(BusinessException.class);
        verify(candidateMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("已拒绝的候选不再计数（否则它会永远赖在待办清单里）")
    void rejectedCandidateStopsCounting() {
        IngredientCandidate rejected = candidate(IngredientCandidate.STATUS_REJECTED);
        when(candidateMapper.selectOne(any())).thenReturn(rejected);

        service.recordUsage(List.of("香茅"));

        verify(candidateMapper, never()).update(isNull(), any());
        verify(candidateMapper, never()).insert(any(IngredientCandidate.class));
    }

    @Test
    @DisplayName("记录候选时出错绝不外抛：它跑在草稿落库的事务里，抛了会把用户刚生成的菜一起回滚")
    void recordUsageSwallowsExceptions() {
        when(candidateMapper.selectOne(any())).thenThrow(new RuntimeException("库抖了一下"));

        service.recordUsage(List.of("香茅", "柠檬叶"));

        // 没有异常抛出即为通过（一个名字失败不影响继续处理）
        verify(candidateMapper, org.mockito.Mockito.times(2)).selectOne(any());
    }
}
