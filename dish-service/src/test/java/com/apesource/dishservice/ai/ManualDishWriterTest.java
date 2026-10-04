package com.apesource.dishservice.ai;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.dto.DishStep;
import com.apesource.dishservice.dto.ManualDishRequest;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.apesource.dishservice.service.DishPublishService;
import com.apesource.dishservice.service.IngredientCandidateService;
import com.apesource.dishservice.service.IngredientDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手写菜谱（用户自己写的菜）的落库策略测试。
 *
 * <p>要守住的三条：
 * <ol>
 *   <li><b>不写半截数据</b>：表单没填全就拒绝，且一行都不许落库；</li>
 *   <li><b>来源要如实</b>：菜品来源是"用户自定义"(3)，营养来源是"按成分表算"(2)，
 *       覆盖率不够时 {@code nutrition_verified=0} —— 不能让用户填的表单
 *       冒充"精确计算过"；</li>
 *   <li><b>食材要归一化</b>：手输的名字（比如"西红柿"）也要挂上主数据 ID，
 *       脏 ID 不许直接进明细表，表外食材要记进候选表。</li>
 * </ol>
 */
class ManualDishWriterTest {

    private DishMapper dishMapper;
    private DishIngredientMapper dishIngredientMapper;
    private NutritionCalculator calculator;
    private IngredientDirectory directory;
    private IngredientCandidateService candidateService;
    private ManualDishWriter writer;

    @BeforeEach
    void setUp() {
        dishMapper = mock(DishMapper.class);
        dishIngredientMapper = mock(DishIngredientMapper.class);
        calculator = mock(NutritionCalculator.class);
        directory = mock(IngredientDirectory.class);
        candidateService = mock(IngredientCandidateService.class);
        writer = new ManualDishWriter(dishMapper, dishIngredientMapper, calculator,
                directory, candidateService);
        // 模拟 MyBatis-Plus 回填雪花 ID
        when(dishMapper.insert(any(Dish.class))).thenAnswer(inv -> {
            ((Dish) inv.getArgument(0)).setId(888L);
            return 1;
        });
    }

    // ================================================================== 造数据

    private ManualDishRequest request(String name, List<ManualDishRequest.Item> items, List<String> steps) {
        return new ManualDishRequest(name, 2, 12, 5, 1, List.of("家常"), "酸酸甜甜，几分钟上桌",
                List.of("盐", "食用油"), List.of("炒锅"), "番茄去皮更容易出沙", items, steps);
    }

    private ManualDishRequest normalRequest() {
        return request("番茄鸡蛋香炒",
                List.of(new ManualDishRequest.Item("番茄", 101L, new BigDecimal("300")),
                        new ManualDishRequest.Item("鸡蛋", 102L, new BigDecimal("120")),
                        new ManualDishRequest.Item("紫苏叶", null, new BigDecimal("10"))),
                List.of("番茄切块，鸡蛋打散。", "热锅倒油，蛋液炒散盛出。", "下番茄炒出汁，回锅炒匀。"));
    }

    /** 覆盖率 92%，对上 2/3 样（紫苏叶没对上） */
    private void stubCalculator() {
        when(calculator.calculate(anyList(), anyInt())).thenReturn(new NutritionCalculator.Result(
                597.1, 19.9, 30.2, 40.4, 3.1, 812.5, 0.92, 2, 3, List.of("紫苏叶")));
        when(calculator.describe(any(NutritionCalculator.Result.class)))
                .thenReturn("每人份 597 kcal（覆盖率 92%，对上 2/3 样，未对上：紫苏叶）");
    }

    private void stubDirectory() {
        when(directory.nameOf(101L)).thenReturn("番茄");
        when(directory.nameOf(102L)).thenReturn("鸡蛋");
        when(directory.resolveId("紫苏叶")).thenReturn(null);
    }

    // ================================================================== 试算

    @Test
    @DisplayName("试算：算出每人份营养，并逐行标出有没有对上食材库")
    void previewComputesNutritionAndFlagsRows() {
        stubCalculator();
        stubDirectory();

        ManualDishWriter.Preview preview = writer.preview(normalRequest());

        assertThat(preview.calories()).isEqualTo(597.1);
        assertThat(preview.servings()).isEqualTo(2);
        assertThat(preview.trustable()).isTrue();
        assertThat(preview.text()).contains("覆盖率 92%");
        assertThat(preview.items()).hasSize(3);
        assertThat(preview.items().get(0).matched()).isTrue();
        assertThat(preview.items().get(0).masterName()).isEqualTo("番茄");
        assertThat(preview.items().get(2).matched()).isFalse();
        assertThat(preview.problems()).isEmpty();
        assertThat(preview.ready()).isTrue();
        // 没对上成分表是"提醒"不是"错误"：这道菜照样能存，只是数字偏低
        assertThat(preview.warnings()).anyMatch(w -> w.contains("紫苏叶"));
    }

    @Test
    @DisplayName("试算：表单没填全时把硬问题一次说全（菜名/食材/步骤）")
    void previewListsAllBlockingProblems() {
        stubCalculator();
        stubDirectory();

        ManualDishWriter.Preview preview = writer.preview(request("",
                List.of(new ManualDishRequest.Item("番茄", 101L, null)),
                List.of("只有一条步骤。")));

        assertThat(preview.ready()).isFalse();
        assertThat(preview.problems()).anyMatch(p -> p.contains("菜名"));
        assertThat(preview.problems()).anyMatch(p -> p.contains("食材"));
        assertThat(preview.problems()).anyMatch(p -> p.contains("做法"));
    }

    @Test
    @DisplayName("试算：只有调料、什么都算不出来时不假装有热量")
    void previewReportsZeroCaloriesWhenNothingMatches() {
        when(calculator.calculate(anyList(), anyInt())).thenReturn(new NutritionCalculator.Result(
                0, 0, 0, 0, 0, 0, 0, 0, 2, List.of("香茅", "柠檬叶")));
        when(calculator.describe(any(NutritionCalculator.Result.class))).thenReturn("每人份 0 kcal");
        when(directory.resolveId("香茅")).thenReturn(null);
        when(directory.resolveId("柠檬叶")).thenReturn(null);

        ManualDishWriter.Preview preview = writer.preview(request("泰式香茅锅",
                List.of(new ManualDishRequest.Item("香茅", null, new BigDecimal("20")),
                        new ManualDishRequest.Item("柠檬叶", null, new BigDecimal("5"))),
                List.of("一", "二", "三")));

        assertThat(preview.trustable()).isFalse();
        assertThat(preview.problems()).anyMatch(p -> p.contains("算不出热量"));
    }

    @Test
    @DisplayName("试算：自己已经有同名菜时提前报出来（别让用户撞唯一键 uk_name_creator）")
    void previewFlagsDuplicateNameForSameCreator() {
        stubCalculator();
        stubDirectory();
        when(dishMapper.selectCount(any())).thenReturn(1L);

        ManualDishWriter.Preview preview = writer.preview(7L, normalRequest());

        assertThat(preview.ready()).isFalse();
        assertThat(preview.problems()).anyMatch(p -> p.contains("换个名字"));
        // 不带身份的那次试算不报重名：上传成功后控制器正是用它回营养，
        // 那时菜已经在库里了，带上身份等于把它自己认成"已占用"
        assertThat(writer.preview(normalRequest()).ready()).isTrue();
    }

    @Test
    @DisplayName("试算：重名检查查库失败也不许抛异常（表单填一半是常态）")
    void previewNeverThrowsWhenNameCheckFails() {
        stubCalculator();
        stubDirectory();
        when(dishMapper.selectCount(any())).thenThrow(new RuntimeException("库连不上"));

        ManualDishWriter.Preview preview = writer.preview(7L, normalRequest());

        assertThat(preview.ready()).isTrue();
        assertThat(preview.problems()).isEmpty();
    }

    // ================================================================== 落库

    @Test
    @DisplayName("落库：来源=用户自定义(3)、营养=本地计算(2)、草稿态、步骤序号重排")
    void saveWritesUserDishAsDraft() {
        stubCalculator();
        stubDirectory();

        Long dishId = writer.save(7L, normalRequest());

        assertThat(dishId).isEqualTo(888L);
        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).insert(captor.capture());
        Dish dish = captor.getValue();
        assertThat(dish.getName()).isEqualTo("番茄鸡蛋香炒");
        assertThat(dish.getNameKey()).isEqualTo("番茄鸡蛋香炒");
        assertThat(dish.getSource()).isEqualTo(ManualDishWriter.SOURCE_USER);
        assertThat(dish.getCreatorId()).isEqualTo(7L);
        assertThat(dish.getStatus()).isEqualTo(DishPublishService.STATUS_DRAFT);
        assertThat(dish.getBaseServing()).isEqualTo(2);
        assertThat(dish.getTasteTag()).isEqualTo("家常");
        assertThat(dish.getNutritionSource()).isEqualTo(DishDraftWriter.SOURCE_LOCAL);
        assertThat(dish.getNutritionVerified()).isEqualTo(1);
        assertThat(dish.getEnergyKcal()).isEqualByComparingTo("597.1");
        assertThat(dish.getStepJson()).extracting(DishStep::getNo).containsExactly(1, 2, 3);
        assertThat(dish.getSeasonings()).containsExactly("盐", "食用油");
    }

    @Test
    @DisplayName("落库：覆盖率不足时照样写数值，但 nutrition_verified=0")
    void saveKeepsUnverifiedFlagWhenCoverageLow() {
        when(calculator.calculate(anyList(), anyInt())).thenReturn(new NutritionCalculator.Result(
                120.0, 3.0, 2.0, 8.0, 0.5, 200.0, 0.45, 1, 3, List.of("紫苏叶", "香茅")));
        when(calculator.describe(any(NutritionCalculator.Result.class))).thenReturn("每人份 120 kcal");
        when(directory.nameOf(101L)).thenReturn("番茄");
        when(directory.resolveId("紫苏叶")).thenReturn(null);
        when(directory.resolveId("香茅")).thenReturn(null);

        ManualDishRequest req = request("番茄香草汤",
                List.of(new ManualDishRequest.Item("番茄", 101L, new BigDecimal("200")),
                        new ManualDishRequest.Item("紫苏叶", null, new BigDecimal("10")),
                        new ManualDishRequest.Item("香茅", null, new BigDecimal("10"))),
                List.of("一", "二", "三"));

        writer.save(7L, req);

        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).insert(captor.capture());
        assertThat(captor.getValue().getNutritionVerified()).isZero();
        assertThat(captor.getValue().getNutritionSource()).isEqualTo(DishDraftWriter.SOURCE_LOCAL);
        assertThat(captor.getValue().getEnergyKcal()).isEqualByComparingTo("120.0");
    }

    @Test
    @DisplayName("落库：第一样视为主料，表外食材记进候选表")
    void saveMarksFirstAsMainAndRecordsUnknown() {
        stubCalculator();
        stubDirectory();

        writer.save(7L, normalRequest());

        ArgumentCaptor<DishIngredient> captor = ArgumentCaptor.forClass(DishIngredient.class);
        verify(dishIngredientMapper, times(3)).insert(captor.capture());
        List<DishIngredient> rows = captor.getAllValues();
        assertThat(rows).extracting(DishIngredient::getMainFlag).containsExactly(1, 0, 0);
        assertThat(rows).extracting(DishIngredient::getSortNo).containsExactly(0, 1, 2);
        assertThat(rows).extracting(DishIngredient::getIngredientId).containsExactly(101L, 102L, null);
        assertThat(rows.get(0).getUnit()).isEqualTo("g");
        assertThat(rows.get(0).getAmountGram()).isEqualByComparingTo("300");
        verify(candidateService).recordUsage(List.of("紫苏叶"));
    }

    @Test
    @DisplayName("落库：脏的主数据 ID 不认，回退按名字归一（西红柿 → 101）")
    void saveIgnoresDirtyIdAndNormalizesByName() {
        stubCalculator();
        // 注意：Mockito 对**没打桩**的 Long 返回值给的是 0L（不是 null），
        // 所以"主数据里没有这个名字"必须显式打桩成 null，否则测试会自己造出一个 0 号食材
        when(directory.nameOf(999L)).thenReturn(null);
        when(directory.resolveId("西红柿")).thenReturn(101L);
        when(directory.resolveId("鸡蛋")).thenReturn(null);

        writer.save(7L, request("西红柿炒蛋",
                List.of(new ManualDishRequest.Item("西红柿", 999L, new BigDecimal("250")),
                        new ManualDishRequest.Item("鸡蛋", null, new BigDecimal("120"))),
                List.of("一", "二", "三")));

        ArgumentCaptor<DishIngredient> captor = ArgumentCaptor.forClass(DishIngredient.class);
        verify(dishIngredientMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(DishIngredient::getIngredientId)
                .containsExactly(101L, null);
    }

    @Test
    @DisplayName("落库：面板上加了却没填的行会被丢掉，空行不进明细表")
    void saveSkipsEmptyRows() {
        stubCalculator();
        stubDirectory();

        writer.save(7L, request("番茄鸡蛋香炒",
                List.of(new ManualDishRequest.Item("番茄", 101L, new BigDecimal("300")),
                        new ManualDishRequest.Item("  ", null, null),
                        new ManualDishRequest.Item("鸡蛋", 102L, new BigDecimal("120"))),
                List.of("一", "二", "三")));

        verify(dishIngredientMapper, times(2)).insert(any(DishIngredient.class));
    }

    @Test
    @DisplayName("落库：表单没填全直接拒绝，一行都不许写")
    void saveRejectsIncompleteForm() {
        stubCalculator();
        stubDirectory();

        ManualDishRequest bad = request("番茄鸡蛋香炒",
                List.of(new ManualDishRequest.Item("番茄", 101L, new BigDecimal("300"))),
                List.of("只有一条。"));

        assertThatThrownBy(() -> writer.save(7L, bad))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("食材");
        verify(dishMapper, never()).insert(any(Dish.class));
        verify(dishIngredientMapper, never()).insert(any(DishIngredient.class));
    }

    @Test
    @DisplayName("落库：重名直接拒绝并说人话，一行都不写")
    void saveRejectsDuplicateNameWithoutInserting() {
        stubCalculator();
        stubDirectory();
        when(dishMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> writer.save(7L, normalRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("换个名字");
        verify(dishMapper, never()).insert(any(Dish.class));
        verify(dishIngredientMapper, never()).insert(any(DishIngredient.class));
    }
}
