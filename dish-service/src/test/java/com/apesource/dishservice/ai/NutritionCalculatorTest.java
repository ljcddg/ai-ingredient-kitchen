package com.apesource.dishservice.ai;

import com.apesource.dishservice.entity.Ingredient;
import com.apesource.dishservice.mapper.IngredientMapper;
import com.apesource.dishservice.service.IngredientDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 营养本地计算测试（P2-2）。
 *
 * <p>重点是两件事：
 * <ol>
 *   <li><b>算式要对</b>：每 100 克的值 × 实际克数 ÷ 人数，一个环节错都会让数值离谱；</li>
 *   <li><b>算不出来时不能硬算</b>：成分表对不齐（陌生食材）时必须判定为"不可信"，
 *       让上层退回 AI 估算并标注来源，而不是拿残缺数据凑一个看着像样的数。</li>
 * </ol>
 */
class NutritionCalculatorTest {

    private IngredientMapper ingredientMapper;
    private IngredientDirectory directory;
    private NutritionCalculator calculator;

    @BeforeEach
    void setUp() {
        ingredientMapper = mock(IngredientMapper.class);
        directory = mock(IngredientDirectory.class);
        calculator = new NutritionCalculator(ingredientMapper, directory);
    }

    private Ingredient ingredient(long id, String name, double calorie) {
        Ingredient row = new Ingredient();
        row.setId(id);
        row.setName(name);
        // 其余营养素按热量的一半估算即可，测试只关心它们有没有被加上
        row.setCalorie(BigDecimal.valueOf(calorie));
        row.setProtein(BigDecimal.valueOf(calorie / 10.0));
        row.setFat(BigDecimal.valueOf(calorie / 20.0));
        row.setCarb(BigDecimal.valueOf(calorie / 5.0));
        row.setFiber(BigDecimal.valueOf(1));
        row.setSodium(BigDecimal.valueOf(calorie));
        return row;
    }

    private void resolve(String name, Long id) {
        when(directory.resolveId(name)).thenReturn(id);
    }

    @Test
    @DisplayName("已知数值：每 100 克 × 克数 ÷ 人数，六项都要算对")
    void computesPerServing() {
        resolve("番茄", 101L);
        resolve("鸡蛋", 102L);
        when(ingredientMapper.selectBatchIds(any())).thenReturn(List.of(
                ingredient(101L, "番茄", 20), ingredient(102L, "鸡蛋", 144)));

        // 番茄 300g → 60 kcal；鸡蛋 120g → 172.8 kcal；合计 232.8 / 2 人 = 116.4
        NutritionCalculator.Result r = calculator.calculate(List.of(
                new NutritionCalculator.Item("番茄", BigDecimal.valueOf(300)),
                new NutritionCalculator.Item("鸡蛋", BigDecimal.valueOf(120))), 2);

        assertThat(r.calories()).isCloseTo(116.4, org.assertj.core.data.Offset.offset(0.01));
        assertThat(r.proteinGram()).isCloseTo((20 * 3 + 144 * 1.2) / 10.0 / 2, org.assertj.core.data.Offset.offset(0.01));
        assertThat(r.sodiumMg()).isCloseTo(116.4, org.assertj.core.data.Offset.offset(0.01));
        assertThat(r.coverage()).isEqualTo(1.0);
        assertThat(r.resolvedItems()).isEqualTo(2);
        assertThat(r.trustable()).isTrue();
    }

    @Test
    @DisplayName("人数不同结果不同：同样一锅，一人份是一人份、两人份减半")
    void dividesByServings() {
        resolve("番茄", 101L);
        when(ingredientMapper.selectBatchIds(any()))
                .thenReturn(List.of(ingredient(101L, "番茄", 20)));
        List<NutritionCalculator.Item> items =
                List.of(new NutritionCalculator.Item("番茄", BigDecimal.valueOf(300)));

        assertThat(calculator.calculate(items, 1).calories()).isCloseTo(60.0,
                org.assertj.core.data.Offset.offset(0.01));
        assertThat(calculator.calculate(items, 2).calories()).isCloseTo(30.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("成分表对不齐：覆盖率不足就判为不可信，绝不硬算")
    void notTrustableWhenCoverageLow() {
        resolve("番茄", 101L);
        resolve("香茅", null);   // 陌生食材
        when(ingredientMapper.selectBatchIds(any()))
                .thenReturn(List.of(ingredient(101L, "番茄", 20)));

        // 200g 认得 + 200g 不认得 → 覆盖率 50%
        NutritionCalculator.Result r = calculator.calculate(List.of(
                new NutritionCalculator.Item("番茄", BigDecimal.valueOf(200)),
                new NutritionCalculator.Item("香茅", BigDecimal.valueOf(200))), 2);

        assertThat(r.coverage()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(r.unresolved()).containsExactly("香茅");
        assertThat(r.trustable()).isFalse();
    }

    @Test
    @DisplayName("覆盖率达标的边界：900g 认得 + 100g 不认得（90%）可以用")
    void trustableAtNinetyPercent() {
        resolve("番茄", 101L);
        resolve("香茅", null);
        when(ingredientMapper.selectBatchIds(any()))
                .thenReturn(List.of(ingredient(101L, "番茄", 20)));

        NutritionCalculator.Result r = calculator.calculate(List.of(
                new NutritionCalculator.Item("番茄", BigDecimal.valueOf(900)),
                new NutritionCalculator.Item("香茅", BigDecimal.valueOf(100))), 2);

        assertThat(r.coverage()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.01));
        assertThat(r.trustable()).isTrue();
    }

    @Test
    @DisplayName("没有可算的食材：返回空结果且不可信")
    void emptyInput() {
        assertThat(calculator.calculate(List.of(), 2).trustable()).isFalse();
        assertThat(calculator.calculate(null, 2).trustable()).isFalse();
        assertThat(calculator.calculate(List.of(
                new NutritionCalculator.Item("  ", BigDecimal.TEN),
                new NutritionCalculator.Item("番茄", BigDecimal.ZERO)), 2).trustable()).isFalse();
    }

    @Test
    @DisplayName("成分表查询失败：判为不可信，而不是抛异常打断落库")
    void toleratesMapperFailure() {
        resolve("番茄", 101L);
        when(ingredientMapper.selectBatchIds(any())).thenThrow(new RuntimeException("db down"));

        NutritionCalculator.Result r = calculator.calculate(List.of(
                new NutritionCalculator.Item("番茄", BigDecimal.valueOf(300))), 2);

        assertThat(r.trustable()).isFalse();
        assertThat(r.resolvedItems()).isZero();
        assertThat(r.unresolved()).containsExactly("番茄");
    }

    @Test
    @DisplayName("归一化交给 IngredientDirectory：别名也应该能被解析到同一份成分表")
    void usesDirectoryForAliasResolution() {
        resolve("西红柿", 101L);
        when(ingredientMapper.selectBatchIds(any()))
                .thenReturn(List.of(ingredient(101L, "番茄", 20)));

        NutritionCalculator.Result r = calculator.calculate(List.of(
                new NutritionCalculator.Item("西红柿", BigDecimal.valueOf(200))), 2);

        assertThat(r.resolvedItems()).isEqualTo(1);
        assertThat(r.calories()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.01));
        org.mockito.Mockito.verify(directory).resolveId("西红柿");
    }

    @Test
    @DisplayName("目录本身报错：按「没对上」处理，不影响生成链路")
    void toleratesDirectoryFailure() {
        when(directory.resolveId(anyString())).thenThrow(new RuntimeException("目录没加载"));

        NutritionCalculator.Result r = calculator.calculate(List.of(
                new NutritionCalculator.Item("番茄", BigDecimal.valueOf(300))), 2);

        assertThat(r.trustable()).isFalse();
        assertThat(r.calories()).isZero();
    }
}
