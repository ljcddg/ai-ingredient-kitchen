package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import com.apesource.dishservice.dto.DishGeneratePayload;
import com.apesource.dishservice.dto.GenerateConstraints;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.apesource.dishservice.service.IngredientCandidateService;
import com.apesource.dishservice.service.IngredientDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 草稿落库时的营养来源决策测试（P2-2 的策略部分）。
 *
 * <p>核心政策只有一条：<b>算得出来就用本地值，算不出来才用 AI 估算，并且如实标注来源</b>。
 * 这条政策如果写反了（比如不管三七二十一都用 AI 的值，却标成"本地计算"），
 * 表面上指标很好看，实际上是在把估算当实测值 —— 正是说明文档点名要避免的事。
 */
class DishDraftWriterTest {

    private DishMapper dishMapper;
    private DishIngredientMapper dishIngredientMapper;
    private IngredientCandidateService candidateService;
    private NutritionCalculator calculator;
    private IngredientDirectory directory;
    private DishDraftWriter writer;

    @BeforeEach
    void setUp() {
        dishMapper = mock(DishMapper.class);
        dishIngredientMapper = mock(DishIngredientMapper.class);
        candidateService = mock(IngredientCandidateService.class);
        calculator = mock(NutritionCalculator.class);
        directory = mock(IngredientDirectory.class);
        writer = new DishDraftWriter(dishMapper, dishIngredientMapper, calculator, candidateService, directory);
        // 模拟 MyBatis-Plus 回填雪花 ID
        when(dishMapper.insert(any(Dish.class))).thenAnswer(inv -> {
            ((Dish) inv.getArgument(0)).setId(777L);
            return 1;
        });
    }

    private DishGeneratePayload payload() {
        return new DishGeneratePayload(
                List.of(new IngredientRef(101L, "番茄"), new IngredientRef(102L, "鸡蛋")),
                List.of("家常"), 2, GenerateConstraints.none());
    }

    private DishDraft draft() {
        DishDraft draft = new DishDraft();
        draft.setName("番茄炒蛋");
        draft.setSummary("酸甜多汁的番茄裹着滑嫩蛋块，家常下饭，几分钟就能上桌。");
        draft.setPrepTimeMin(6);
        draft.setCookTimeMin(8);
        draft.setDifficulty(1);
        draft.setSpicyLevel(0);
        draft.setSteps(List.of("番茄去蒂切成滚刀块，鸡蛋打散备用备用备用。",
                "热锅倒油中火烧热，倒入蛋液快速划散至凝固。"));
        DishDraft.DraftIngredient tomato = new DishDraft.DraftIngredient();
        tomato.setName("番茄");
        tomato.setAmountGram(300);
        DishDraft.DraftIngredient egg = new DishDraft.DraftIngredient();
        egg.setName("鸡蛋");
        egg.setAmountGram(120);
        draft.setIngredients(List.of(tomato, egg));
        draft.setSeasonings(List.of("盐", "食用油"));
        draft.setEquipment(List.of("炒锅"));
        draft.setChefTips(List.of("鸡蛋要热锅热油下锅才蓬松。", "番茄去皮再炒更容易出沙。"));
        draft.setDietTags(List.of("家常"));
        draft.setSubstitutes(List.of("没有葱可以用洋葱代替，香味更浓。"));

        DishDraft.NutritionDraft nutrition = new DishDraft.NutritionDraft();
        nutrition.setCalories(210);
        nutrition.setProteinGram(13);
        nutrition.setFatGram(14);
        nutrition.setCarbGram(9);
        nutrition.setFiberGram(2);
        nutrition.setSodiumMg(520);
        draft.setNutritionPerServing(nutrition);
        return draft;
    }

    private DishGenerator.GenerateResult result(boolean aiVerified) {
        return result(draft(), aiVerified);
    }

    /** 用指定草稿构造结果 —— 需要先改草稿（比如改食材名）的用例用这个重载 */
    private DishGenerator.GenerateResult result(DishDraft draft, boolean aiVerified) {
        return new DishGenerator.GenerateResult(draft, aiVerified, 100L, 1, "{}",
                new ValidationResult(List.of(), List.of()),
                new DishGenerator.GenerateResult.GenerationMeta(
                        "gemma4:e2b", "test", 0.7, 2, List.of()));
    }

    private NutritionCalculator.Result local(double coverage) {
        return new NutritionCalculator.Result(180.5, 11.2, 9.8, 6.4, 1.5, 430.0,
                coverage, 2, 2, List.of());
    }

    private Dish captureDish() {
        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("本地算得出来：写本地值，来源标 2（本地计算）、核验标 1")
    void usesLocalWhenTrustable() {
        when(calculator.calculate(any(), any(Integer.class))).thenReturn(local(1.0));

        Long dishId = writer.save(1001L, payload(), result(false));

        assertThat(dishId).isEqualTo(777L);
        Dish dish = captureDish();
        assertThat(dish.getNutritionSource()).isEqualTo(DishDraftWriter.SOURCE_LOCAL);
        assertThat(dish.getNutritionVerified()).isEqualTo(1);
        assertThat(dish.getEnergyKcal()).isEqualByComparingTo("180.5");
        assertThat(dish.getProteinG()).isEqualByComparingTo("11.2");
        assertThat(dish.getSodiumMg()).isEqualByComparingTo("430.0");
    }

    @Test
    @DisplayName("本地算不出来：保留 AI 估算值，来源标 1（AI 估算），绝不冒充本地计算")
    void fallsBackToAiWhenNotTrustable() {
        when(calculator.calculate(any(), any(Integer.class))).thenReturn(local(0.3));

        writer.save(1001L, payload(), result(false));

        Dish dish = captureDish();
        assertThat(dish.getNutritionSource()).isEqualTo(DishDraftWriter.SOURCE_AI);
        assertThat(dish.getNutritionVerified()).isZero();
        assertThat(dish.getEnergyKcal()).isEqualByComparingTo("210");
        assertThat(dish.getSodiumMg()).isEqualByComparingTo("520");
    }

    @Test
    @DisplayName("本地算不出来但 AI 估算通过了本地核对：来源仍是 AI，核验标 1")
    void aiEstimateCanBeMarkedVerified() {
        when(calculator.calculate(any(), any(Integer.class))).thenReturn(local(0.3));

        writer.save(1001L, payload(), result(true));

        Dish dish = captureDish();
        assertThat(dish.getNutritionSource()).isEqualTo(DishDraftWriter.SOURCE_AI);
        assertThat(dish.getNutritionVerified()).isEqualTo(1);
    }

    @Test
    @DisplayName("本地计算用的是草稿里的食材与克数，人数取请求里的份数")
    void passesDraftIngredientsAndServings() {
        when(calculator.calculate(any(), any(Integer.class))).thenReturn(local(1.0));

        writer.save(1001L, payload(), result(false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NutritionCalculator.Item>> captor = ArgumentCaptor.forClass(List.class);
        verify(calculator).calculate(captor.capture(), org.mockito.ArgumentMatchers.eq(2));
        assertThat(captor.getValue())
                .extracting(NutritionCalculator.Item::name)
                .containsExactly("番茄", "鸡蛋");
        assertThat(captor.getValue().get(0).grams()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("食材明细照旧落库（营养改动不影响明细写入）")
    void stillWritesIngredients() {
        when(calculator.calculate(any(), any(Integer.class))).thenReturn(local(1.0));

        writer.save(1001L, payload(), result(false));

        ArgumentCaptor<DishIngredient> captor = ArgumentCaptor.forClass(DishIngredient.class);
        verify(dishIngredientMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(DishIngredient::getIngredientName)
                .containsExactly("番茄", "鸡蛋");
    }

    @Test
    @DisplayName("名字不是从列表里选的、但主数据里有：按目录归一化挂上主数据 ID，不当表外食材")
    void normalizesUnselectedNameToMasterId() {
        when(calculator.calculate(any(), any(Integer.class))).thenReturn(local(1.0));
        // AI 写了"西红柿"（用户选的是"番茄"），目录认得这个别名
        when(directory.resolveId("西红柿")).thenReturn(501L);
        DishDraft draft = draft();
        draft.getIngredients().get(0).setName("西红柿");

        writer.save(1001L, payload(), result(draft, false));

        ArgumentCaptor<DishIngredient> captor = ArgumentCaptor.forClass(DishIngredient.class);
        verify(dishIngredientMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        DishIngredient first = captor.getAllValues().get(0);
        assertThat(first.getIngredientName()).isEqualTo("西红柿");
        assertThat(first.getIngredientId()).isEqualTo(501L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> usage = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(candidateService).recordUsage(usage.capture());
        assertThat(usage.getValue()).isEmpty();
    }

    @Test
    @DisplayName("目录也认不出来：仍是表外食材，ID 留空并且记进候选表")
    void keepsTrulyUnknownIngredientAsCandidate() {
        when(calculator.calculate(any(), any(Integer.class))).thenReturn(local(1.0));
        when(directory.resolveId("紫苏叶")).thenReturn(null);
        DishDraft draft = draft();
        draft.getIngredients().get(1).setName("紫苏叶");

        writer.save(1001L, payload(), result(draft, false));

        ArgumentCaptor<DishIngredient> captor = ArgumentCaptor.forClass(DishIngredient.class);
        verify(dishIngredientMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        DishIngredient second = captor.getAllValues().get(1);
        assertThat(second.getIngredientName()).isEqualTo("紫苏叶");
        assertThat(second.getIngredientId()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> usage = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(candidateService).recordUsage(usage.capture());
        assertThat(usage.getValue()).containsExactly("紫苏叶");
    }

    @Test
    @DisplayName("从列表里选的食材优先用请求里的 ID，不经过目录（避免目录改名影响已选食材）")
    void prefersPayloadIdOverDirectory() {
        when(calculator.calculate(any(), any(Integer.class))).thenReturn(local(1.0));
        when(directory.resolveId("番茄")).thenReturn(999L);

        writer.save(1001L, payload(), result(false));

        ArgumentCaptor<DishIngredient> captor = ArgumentCaptor.forClass(DishIngredient.class);
        verify(dishIngredientMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(DishIngredient::getIngredientId)
                .containsExactly(101L, 102L);
    }
}
