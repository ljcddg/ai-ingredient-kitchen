package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishItem;
import com.apesource.dishservice.dto.DishRecallResult;
import com.apesource.dishservice.dto.DishStep;
import com.apesource.dishservice.dto.GenerateConstraints;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.apesource.dishservice.service.DishSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 参考菜谱检索与渲染测试（P2-1）。
 *
 * <p>守三件事：
 * <ol>
 *   <li><b>片段要够用</b>：菜名、口味、耗时、主料、缺哪样、做法要点都得进提示词；</li>
 *   <li><b>没有参考时提示词里连"参考"两个字都不该出现</b> ——
 *       否则"增强/不增强"两臂的提示词就不是干净对照了；</li>
 *   <li><b>检索出问题不能把生成带崩</b>：ES 挂了、取步骤失败，都只降级成"没有参考"。</li>
 * </ol>
 */
class ReferenceDishRetrieverTest {

    private DishSearchService searchService;
    private DishMapper dishMapper;
    private DishIngredientMapper dishIngredientMapper;
    private GenerateProperties properties;
    private ReferenceDishRetriever retriever;

    @BeforeEach
    void setUp() {
        searchService = mock(DishSearchService.class);
        dishMapper = mock(DishMapper.class);
        dishIngredientMapper = mock(DishIngredientMapper.class);
        properties = new GenerateProperties();
        retriever = new ReferenceDishRetriever(searchService, dishMapper, dishIngredientMapper, properties);
    }

    private DishGenerator.GenerateInput input() {
        return new DishGenerator.GenerateInput(
                List.of(new IngredientRef(101L, "番茄"), new IngredientRef(102L, "鸡蛋")),
                List.of("家常"), 2, GenerateConstraints.none());
    }

    private DishItem item(long dishId, String name) {
        DishItem item = new DishItem();
        item.setDishId(dishId);
        item.setName(name);
        item.setTasteTags(List.of("家常"));
        item.setCookTimeMin(15);
        item.setMatchedIngredients(List.of("番茄", "鸡蛋"));
        item.setMissingIngredients(List.of("松露"));
        return item;
    }

    private DishRecallResult recall(DishItem... items) {
        DishRecallResult result = new DishRecallResult();
        result.setCandidates(List.of(items));
        return result;
    }

    private DishStep step(String text) {
        DishStep step = new DishStep();
        step.setNo(1);
        step.setText(text);
        return step;
    }

    private Dish dishWithSteps(long dishId) {
        Dish dish = new Dish();
        dish.setId(dishId);
        dish.setStepJson(List.of(
                step("鸡蛋打散，热锅热油下锅划散至半凝固盛出。"),
                step("番茄去皮切块，下锅中火炒出汤汁。"),
                step("第三步不该出现在参考里。")));
        return dish;
    }

    // ------------------------------------------------------------------ 渲染

    @Test
    @DisplayName("没有参考时渲染为空串：提示词里不会出现「参考」，两臂才是干净对照")
    void rendersNothingWhenNoReference() {
        assertThat(retriever.render(List.of())).isEmpty();
        assertThat(retriever.render(null)).isEmpty();
    }

    @Test
    @DisplayName("渲染包含菜名、口味、耗时、主料、缺料与做法要点，并带上防照抄的约束")
    void rendersUsableSnippet() {
        ReferenceDishRetriever.Reference ref = new ReferenceDishRetriever.Reference(
                88L, "番茄炒蛋", List.of("番茄", "鸡蛋", "松露"), List.of("松露"),
                List.of("家常"), 15, List.of("先炒蛋液至半凝固", "番茄去皮炒出汁"), List.of());

        String text = retriever.render(List.of(ref));

        assertThat(text)
                .contains("番茄炒蛋")
                .contains("家常")
                .contains("15 分钟")
                .contains("番茄、鸡蛋、松露")
                .contains("你手上没有：松露")
                .contains("先炒蛋液至半凝固");
        assertThat(text).contains("必须自己写一道新菜");
        assertThat(text).contains("菜名不得与上面任何一道相同");
    }

    // ------------------------------------------------------------------ 检索

    @Test
    @DisplayName("检索正常：按条数取参考，主料取全，做法要点只取配置的前几步")
    void retrievesAndTrimsSteps() {
        properties.setReferenceStepHints(2);
        when(searchService.search(any())).thenReturn(recall(item(88L, "番茄炒蛋")));
        when(dishMapper.selectBatchIds(any())).thenReturn(List.of(dishWithSteps(88L)));

        List<ReferenceDishRetriever.Reference> refs = retriever.retrieve(input(), 3);

        assertThat(refs).hasSize(1);
        ReferenceDishRetriever.Reference ref = refs.get(0);
        assertThat(ref.dishId()).isEqualTo(88L);
        assertThat(ref.name()).isEqualTo("番茄炒蛋");
        assertThat(ref.mains()).containsExactlyInAnyOrder("番茄", "鸡蛋", "松露");
        assertThat(ref.missing()).containsExactly("松露");
        assertThat(ref.stepHints())
                .as("只给前两步，第三步不该带进来")
                .hasSize(2)
                .noneMatch(hint -> hint.contains("第三步"));
    }

    @Test
    @DisplayName("检索报错（ES 挂了）：返回空列表，绝不把生成带崩")
    void toleratesSearchFailure() {
        when(searchService.search(any())).thenThrow(new RuntimeException("Connection refused"));

        assertThat(retriever.retrieve(input(), 3)).isEmpty();
    }

    @Test
    @DisplayName("取步骤失败：参考照样能用，只是没有做法要点")
    void toleratesStepLoadFailure() {
        when(searchService.search(any())).thenReturn(recall(item(88L, "番茄炒蛋")));
        when(dishMapper.selectBatchIds(any())).thenThrow(new RuntimeException("db down"));

        List<ReferenceDishRetriever.Reference> refs = retriever.retrieve(input(), 3);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).stepHints()).isEmpty();
        assertThat(refs.get(0).mains()).isNotEmpty();
    }

    @Test
    @DisplayName("用户一样食材都没给：不去检索，直接返回空")
    void skipsWhenNoIngredients() {
        DishGenerator.GenerateInput empty = new DishGenerator.GenerateInput(
                List.of(), List.of(), 2, GenerateConstraints.none());

        assertThat(retriever.retrieve(empty, 3)).isEmpty();
    }

    @Test
    @DisplayName("检索没命中：返回空列表（生成按无参考继续）")
    void emptyRecall() {
        when(searchService.search(any())).thenReturn(recall());

        assertThat(retriever.retrieve(input(), 3)).isEmpty();
    }

    // ------------------------------------------------------------------ 用量（grounding 依据）

    @Test
    @DisplayName("参考带上用量：按克数降序取前 8 样，供 Python 侧做依据校验")
    void carriesAmountsForGrounding() {
        when(searchService.search(any())).thenReturn(recall(item(88L, "番茄炒蛋")));
        when(dishMapper.selectBatchIds(any())).thenReturn(List.of(dishWithSteps(88L)));
        when(dishIngredientMapper.selectList(any())).thenReturn(List.of(
                ingredient(88L, "番茄", 200), ingredient(88L, "鸡蛋", 120),
                ingredient(88L, "葱", 10), ingredient(88L, "没克数的", null)));

        ReferenceDishRetriever.Reference ref = retriever.retrieve(input(), 3).get(0);

        assertThat(ref.amounts())
                .as("按用量降序，缺克数的（调料）直接丢掉")
                .extracting(ReferenceDishRetriever.Amount::name)
                .containsExactly("番茄", "鸡蛋", "葱");
        assertThat(ref.amounts().get(0).amountGram()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("取用量失败：参考照样能用，只是这批 amounts 为空（不做依据校验，不影响生成）")
    void toleratesAmountLoadFailure() {
        when(searchService.search(any())).thenReturn(recall(item(88L, "番茄炒蛋")));
        when(dishMapper.selectBatchIds(any())).thenReturn(List.of(dishWithSteps(88L)));
        when(dishIngredientMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        List<ReferenceDishRetriever.Reference> refs = retriever.retrieve(input(), 3);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).amounts()).isEmpty();
        assertThat(refs.get(0).mains()).isNotEmpty();
    }

    private DishIngredient ingredient(long dishId, String name, Integer gram) {
        DishIngredient d = new DishIngredient();
        d.setDishId(dishId);
        d.setIngredientName(name);
        d.setAmountGram(gram == null ? null : new java.math.BigDecimal(gram));
        return d;
    }
}
