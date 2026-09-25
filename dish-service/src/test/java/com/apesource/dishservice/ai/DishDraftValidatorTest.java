package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 草稿校验器的单元测试。
 *
 * <p>它是"约束输出"三道防线里的第三道，写错一个边界值就会造成两种后果：
 * 放过了烂数据（用户看到乱七八糟的菜谱），或者卡住了好数据（用户永远生成不出来）。
 * 所以每种规则都要有<b>正例和反例</b>。
 */
@DisplayName("草稿校验器 DishDraftValidator")
class DishDraftValidatorTest {

    private final DishDraftValidator validator = new DishDraftValidator();

    private static final List<String> REQUIRED = List.of("番茄", "鸡蛋", "葱");

    private static DishDraft.DraftIngredient ing(String name, int gram) {
        DishDraft.DraftIngredient i = new DishDraft.DraftIngredient();
        i.setName(name);
        i.setAmountGram(gram);
        return i;
    }

    /** 一份完全合规的草稿，各用例在它基础上改一个字段来制造问题 */
    private DishDraft validDraft() {
        DishDraft d = new DishDraft();
        d.setName("番茄炒蛋");
        d.setSummary("酸甜多汁的番茄裹着滑嫩蛋块，家常下饭，几分钟就能上桌。");
        d.setPrepTimeMin(6);
        d.setCookTimeMin(8);
        d.setDifficulty(1);
        d.setSpicyLevel(0);
        d.setSteps(new ArrayList<>(List.of(
                "番茄去蒂切成滚刀块，鸡蛋打入碗中加少许盐打散备用。",
                "热锅倒油，中火烧至六成热，倒入蛋液快速划散后盛出。",
                "锅内留底油，放入葱白爆香，倒入番茄块大火翻炒出汁。",
                "倒回炒好的鸡蛋，加盐翻匀，撒上葱花即可出锅。")));
        d.setIngredients(new ArrayList<>(List.of(ing("番茄", 300), ing("鸡蛋", 120), ing("葱", 10))));
        d.setSeasonings(new ArrayList<>(List.of("盐", "食用油")));
        d.setEquipment(new ArrayList<>(List.of("炒锅", "锅铲")));
        d.setDietTags(new ArrayList<>(List.of("家常", "下饭")));
        d.setSubstitutes(new ArrayList<>(List.of("没有葱可以用洋葱代替。")));
        d.setChefTips(new ArrayList<>(List.of(
                "鸡蛋要热锅热油下锅才蓬松，油温不够会出水变老。",
                "番茄先烫一下去皮再炒，更容易出沙，汤汁更浓。")));

        DishDraft.NutritionDraft n = new DishDraft.NutritionDraft();
        n.setCalories(210);
        n.setProteinGram(13);
        n.setFatGram(14);
        n.setCarbGram(9);
        n.setFiberGram(2);
        n.setSodiumMg(520);
        d.setNutritionPerServing(n);
        return d;
    }

    private List<String> validate(DishDraft d) {
        return validator.validate(d, REQUIRED);
    }

    // ---------------------------------------------------------------- 正例

    @Test
    @DisplayName("合规的草稿应当零问题")
    void validDraftPasses() {
        assertTrue(validate(validDraft()).isEmpty(), "正常草稿不该被拦");
    }

    @Test
    @DisplayName("没有输入食材时跳过覆盖校验（只校验字段本身）")
    void emptyRequiredSkipsCoverage() {
        List<String> problems = validator.validate(validDraft(), List.of());
        assertTrue(problems.isEmpty());
    }

    // ---------------------------------------------------------------- 反例：字段规则

    @Test
    @DisplayName("输出为空要报错")
    void nullDraft() {
        assertFalse(validate(null).isEmpty());
        assertTrue(validate(null).get(0).contains("为空"));
    }

    @Test
    @DisplayName("菜名缺失或超长要报错")
    void badName() {
        DishDraft d1 = validDraft();
        d1.setName(null);
        assertTrue(validate(d1).stream().anyMatch(p -> p.startsWith("name 缺失")));

        DishDraft d2 = validDraft();
        d2.setName("这是一道名字特别特别长的菜名超过十二个字了");  // > 12 字
        assertTrue(validate(d2).stream().anyMatch(p -> p.startsWith("name 长度")));
    }

    @Test
    @DisplayName("步骤数量不在 4~8 之间要报错，且报出具体是哪一条超长")
    void badSteps() {
        DishDraft d1 = validDraft();
        d1.setSteps(new ArrayList<>(List.of("只有一步")));
        assertTrue(validate(d1).stream().anyMatch(p -> p.startsWith("steps 有 1 项")));

        DishDraft d2 = validDraft();
        d2.getSteps().set(0, "太短");
        assertTrue(validate(d2).stream().anyMatch(p -> p.startsWith("steps[1] 长度")),
                "要指出是第几条有问题，重试提示词才有效");
    }

    @Test
    @DisplayName("难度/辣度/时间越界要报错")
    void badNumbers() {
        DishDraft d = validDraft();
        d.setDifficulty(5);        // 只允许 1~3
        d.setSpicyLevel(9);
        d.setPrepTimeMin(0);
        List<String> problems = validate(d);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("difficulty=")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("spicyLevel=")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("prepTimeMin=")));
    }

    // ---------------------------------------------------------------- 反例：食材

    @Test
    @DisplayName("少用了用户给的食材要报错，并点名是哪一个")
    void missingRequiredIngredient() {
        DishDraft d = validDraft();
        d.setIngredients(new ArrayList<>(List.of(ing("番茄", 300), ing("鸡蛋", 120))));  // 少了葱
        List<String> problems = validate(d);
        assertTrue(problems.stream().anyMatch(p -> p.contains("没有用上用户提供的食材") && p.contains("葱")),
                "错误信息必须点名缺哪样，实际：" + problems);
    }

    @Test
    @DisplayName("调料出现在 seasonings 里也算命中")
    void seasoningCountsAsCovered() {
        List<String> problems = validator.validate(validDraft(), List.of("番茄", "鸡蛋", "葱", "盐"));
        assertTrue(problems.isEmpty(), "盐在 seasonings 里，应该算用上了：" + problems);
    }

    @Test
    @DisplayName("用量夸张要报错（实测出现过「料酒 300g」）")
    void absurdAmount() {
        DishDraft d = validDraft();
        d.getIngredients().add(ing("料酒", 300));
        List<String> problems = validate(d);
        assertTrue(problems.stream().anyMatch(p -> p.contains("料酒") && p.contains("明显过大")),
                "300g 料酒必须被拦下：" + problems);
    }

    @Test
    @DisplayName("不该出现的无营养液体要报错")
    void waterRejected() {
        DishDraft d = validDraft();
        d.getIngredients().add(ing("清水", 500));
        assertTrue(validate(d).stream().anyMatch(p -> p.contains("不含营养的液体")));
    }

    // ---------------------------------------------------------------- 反例：营养

    @Test
    @DisplayName("营养缺失或越界要报错")
    void badNutrition() {
        DishDraft d1 = validDraft();
        d1.setNutritionPerServing(null);
        assertTrue(validate(d1).stream().anyMatch(p -> p.contains("nutritionPerServing 缺失")));

        DishDraft d2 = validDraft();
        d2.getNutritionPerServing().setCalories(5000);       // 远超家常菜
        assertTrue(validate(d2).stream().anyMatch(p -> p.contains("calories=5000")));

        DishDraft d3 = validDraft();
        d3.getNutritionPerServing().setSodiumMg(null);
        assertTrue(validate(d3).stream().anyMatch(p -> p.contains("sodiumMg 缺失")));
    }

    @Test
    @DisplayName("一次报多个问题（重试时能一次性告诉模型全部要改的地方）")
    void reportsAllProblems() {
        DishDraft d = validDraft();
        d.setDifficulty(8);
        d.setChefTips(new ArrayList<>(List.of("只有一条")));
        d.getIngredients().clear();
        List<String> problems = validate(d);
        assertTrue(problems.size() >= 3, "应该一次报出全部问题，实际只报了：" + problems);
        assertEquals(problems.size(), problems.stream().distinct().count(), "不该有重复条目");
    }
}
