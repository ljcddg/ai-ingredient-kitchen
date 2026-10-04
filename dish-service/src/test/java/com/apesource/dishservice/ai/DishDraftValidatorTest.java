package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import com.apesource.dishservice.dto.GenerateConstraints;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.service.IngredientDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 草稿校验器的单元测试。
 *
 * <p>它是"约束输出"三道防线里的第三道，写错一个边界值就会造成两种后果：
 * 放过了烂数据（用户看到乱七八糟的菜谱），或者卡住了好数据（用户永远生成不出来）。
 * 所以每种规则都要有<b>正例和反例</b>。
 *
 * <p>说明文档第三节新增的规则在这里逐条有对应用例：必用/禁用食材、
 * 耗时上限、可用设备、配料表与步骤一致性、用量按人数与类别、
 * 以及"食材匹配走主数据 ID / 别名"（这一条还专门留了一个反向用例：
 * 旧实现会把「鸡蛋面」误判成命中了「鸡蛋」）。
 *
 * <p>食材目录用 mock：校验逻辑不该依赖 MySQL，否则这些用例会因为
 * "本地没起库"而跑不起来。
 */
@DisplayName("草稿校验器 DishDraftValidator")
class DishDraftValidatorTest {

    private static final long TOMATO = 101L;
    private static final long EGG = 102L;
    private static final long SCALLION = 103L;

    private IngredientDirectory directory;
    private DishDraftValidator validator;

    private static final List<IngredientRef> REQUIRED =
            List.of(new IngredientRef(TOMATO, "番茄"),
                    new IngredientRef(EGG, "鸡蛋"),
                    new IngredientRef(SCALLION, "葱"));

    @BeforeEach
    void setUp() {
        directory = mock(IngredientDirectory.class);
        validator = new DishDraftValidator(directory);

        // 默认：目录里没有的名字一律解析不到。
        // ⚠️ 这一条必须有：Mockito 对**未打桩的 Long 方法返回 0 而不是 null**，
        // 不显式桩住的话，"目录外的食材"会带着 ID 0 混进集合，
        // 和另一个同样为 0 的食材被判成同一个 —— 校验会静默放过
        when(directory.resolveId(anyString())).thenReturn(null);

        // 只桩住用例用到的名字，等价于库里那几行主数据
        when(directory.resolveId("番茄")).thenReturn(TOMATO);
        when(directory.resolveId("西红柿")).thenReturn(TOMATO);
        when(directory.resolveId("鸡蛋")).thenReturn(EGG);
        when(directory.resolveId("葱")).thenReturn(SCALLION);
        when(directory.nameOf(TOMATO)).thenReturn("番茄");
        when(directory.nameOf(EGG)).thenReturn("鸡蛋");
        when(directory.nameOf(SCALLION)).thenReturn("葱");
        when(directory.categoryOf(TOMATO)).thenReturn("蔬菜");
        when(directory.categoryOf(EGG)).thenReturn("蛋奶");
        when(directory.categoryOf(SCALLION)).thenReturn("蔬菜");
        when(directory.isSeasoningName("盐")).thenReturn(true);
        when(directory.isSeasoningName("食用油")).thenReturn(true);
        when(directory.isSeasoningName("料酒")).thenReturn(true);

        // "食材词典"：步骤扫描用的词表。故意放进几个**没出现在草稿里**的名字，
        // 用来证明扫描不会把没提到的食材也算成"多出来的"
        when(directory.canonicalNames()).thenReturn(
                Set.of("番茄", "鸡蛋", "葱", "牛肉", "奶酪", "松露", "香菇", "盐", "食用油", "料酒"));
    }

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
        return validator.validate(d, input(REQUIRED, 2, GenerateConstraints.none()));
    }

    private DishGenerator.GenerateInput input(List<IngredientRef> refs, int servings, GenerateConstraints c) {
        return new DishGenerator.GenerateInput(refs, List.of("家常"), servings, c);
    }

    private static boolean has(List<String> problems, String fragment) {
        return problems.stream().anyMatch(p -> p.contains(fragment));
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
        List<String> problems = validator.validate(validDraft(),
                input(List.of(), 2, GenerateConstraints.none()));
        assertTrue(problems.isEmpty(), "实际：" + problems);
    }

    @Test
    @DisplayName("食材匹配走主数据 ID：用户写别名「西红柿」，模型写「番茄」也算用上了")
    void aliasMatchesByIngredientId() {
        List<String> problems = validator.validate(validDraft(),
                input(List.of(new IngredientRef(TOMATO, "西红柿"),
                        new IngredientRef(EGG, "鸡蛋"),
                        new IngredientRef(SCALLION, "葱")), 2, GenerateConstraints.none()));
        assertTrue(problems.isEmpty(), "别名应当算命中（旧实现按字符串比会误判成没用到）：" + problems);
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
        assertTrue(has(problems, "没有用上用户提供的食材") && has(problems, "葱"),
                "错误信息必须点名缺哪样，实际：" + problems);
    }

    @Test
    @DisplayName("调料出现在 seasonings 里也算命中")
    void seasoningCountsAsCovered() {
        List<String> problems = validator.validate(validDraft(),
                input(List.of(new IngredientRef(TOMATO, "番茄"), new IngredientRef(EGG, "鸡蛋"),
                        new IngredientRef(SCALLION, "葱"), new IngredientRef(118L, "盐")),
                        2, GenerateConstraints.none()));
        assertTrue(problems.isEmpty(), "盐在 seasonings 里，应该算用上了：" + problems);
    }

    @Test
    @DisplayName("用量夸张要报错（实测出现过「料酒 300g」）")
    void absurdAmount() {
        DishDraft d = validDraft();
        d.getIngredients().add(ing("料酒", 300));
        List<String> problems = validate(d);
        assertTrue(has(problems, "料酒") && has(problems, "明显过大"),
                "300g 料酒必须被拦下：" + problems);
    }

    @Test
    @DisplayName("用量上限随人数放大：800g 番茄 1 人份算夸张，3 人份就正常")
    void amountLimitScalesWithServings() {
        DishDraft one = validDraft();
        one.getIngredients().set(0, ing("番茄", 800));
        List<String> atOne = validator.validate(one, input(REQUIRED, 1, GenerateConstraints.none()));
        assertTrue(has(atOne, "明显过大"), "1 人份 800g 番茄该被拦：" + atOne);

        DishDraft three = validDraft();
        three.getIngredients().set(0, ing("番茄", 800));
        List<String> atThree = validator.validate(three, input(REQUIRED, 3, GenerateConstraints.none()));
        assertFalse(atThree.stream().anyMatch(p -> p.contains("番茄") && p.contains("明显过大")),
                "3 人份 800g 番茄是合理的，不该拦：" + atThree);
    }

    @Test
    @DisplayName("主料总量与人数不匹配要报错（草稿没有人数字段，用用量反推）")
    void totalAmountMustMatchServings() {
        // 同一份 430g 的草稿：2 人份合理，6 人份明显不够吃
        List<String> two = validator.validate(validDraft(), input(REQUIRED, 2, GenerateConstraints.none()));
        assertFalse(has(two, "不匹配"), "2 人份不该报人数不匹配：" + two);

        List<String> six = validator.validate(validDraft(), input(REQUIRED, 6, GenerateConstraints.none()));
        assertTrue(has(six, "与 6 人份不匹配"), "6 人份只给 430g 主料应当报出来：" + six);
    }

    @Test
    @DisplayName("不该出现的无营养液体要报错")
    void waterRejected() {
        DishDraft d = validDraft();
        d.getIngredients().add(ing("清水", 500));
        assertTrue(validate(d).stream().anyMatch(p -> p.contains("不含营养的液体")));
    }

    // ---------------------------------------------------------------- 反例：用户约束（本轮新增）

    @Test
    @DisplayName("必用食材没有出现在配料表里要报错")
    void mustUseMissing() {
        GenerateConstraints c = new GenerateConstraints(
                List.of(104L), List.of(), List.of(), List.of(), null, List.of());
        when(directory.nameOf(104L)).thenReturn("牛肉");

        List<String> problems = validator.validate(validDraft(), input(REQUIRED, 2, c));

        assertTrue(has(problems, "用户要求必用的食材没有出现在配料表里") && has(problems, "牛肉"),
                "实际：" + problems);
    }

    @Test
    @DisplayName("必用食材只在配料表里挂名、步骤没用到，也要报错")
    void mustUseNotUsedInSteps() {
        DishDraft d = validDraft();
        d.getIngredients().add(ing("牛肉", 150));   // 配料表里有，但四个步骤里都没提
        GenerateConstraints c = new GenerateConstraints(
                List.of(104L), List.of(), List.of(), List.of(), null, List.of());
        when(directory.nameOf(104L)).thenReturn("牛肉");

        List<String> problems = validator.validate(d, input(REQUIRED, 2, c));

        assertTrue(has(problems, "步骤中没有用到") && has(problems, "牛肉"), "实际：" + problems);
    }

    @Test
    @DisplayName("禁用食材出现在配料表要报错")
    void excludedInIngredients() {
        DishDraft d = validDraft();
        d.getIngredients().add(ing("牛肉", 150));
        GenerateConstraints c = new GenerateConstraints(
                List.of(), List.of(), List.of(104L), List.of(), null, List.of());
        when(directory.nameOf(104L)).thenReturn("牛肉");

        List<String> problems = validator.validate(d, input(REQUIRED, 2, c));

        assertTrue(has(problems, "出现了用户禁用的食材") && has(problems, "牛肉"), "实际：" + problems);
    }

    @Test
    @DisplayName("禁用食材只写在步骤里同样要报错（过敏源写进做法也是事故）")
    void excludedInSteps() {
        DishDraft d = validDraft();
        d.getSteps().set(1, "热锅倒花生油，中火烧至六成热，倒入蛋液快速划散后盛出。");
        GenerateConstraints c = new GenerateConstraints(
                List.of(), List.of(), List.of(), List.of("花生油"), null, List.of());

        List<String> problems = validator.validate(d, input(REQUIRED, 2, c));

        assertTrue(has(problems, "出现了用户禁用的食材") && has(problems, "花生油"), "实际：" + problems);
    }

    @Test
    @DisplayName("总耗时超过用户上限要报错，并写清是怎么算出来的")
    void cookTimeOverLimit() {
        GenerateConstraints c = new GenerateConstraints(
                List.of(), List.of(), List.of(), List.of(), 10, List.of());

        List<String> problems = validator.validate(validDraft(), input(REQUIRED, 2, c));

        assertTrue(has(problems, "总耗时 14 分钟超过用户要求的 10 分钟"), "实际：" + problems);
    }

    @Test
    @DisplayName("用到了用户没有的厨具要报错，并列出用户有什么")
    void unavailableEquipment() {
        DishDraft d = validDraft();
        d.setEquipment(new ArrayList<>(List.of("烤箱")));
        GenerateConstraints c = new GenerateConstraints(
                List.of(), List.of(), List.of(), List.of(), null, List.of("炒锅", "电饭煲"));

        List<String> problems = validator.validate(d, input(REQUIRED, 2, c));

        assertTrue(has(problems, "用到了用户没有的厨具") && has(problems, "烤箱"), "实际：" + problems);
    }

    @Test
    @DisplayName("厨具名做宽松匹配：用户有「炒锅」时，「锅」不算缺")
    void equipmentNameTolerance() {
        DishDraft d = validDraft();
        d.setEquipment(new ArrayList<>(List.of("锅")));
        GenerateConstraints c = new GenerateConstraints(
                List.of(), List.of(), List.of(), List.of(), null, List.of("炒锅"));

        List<String> problems = validator.validate(d, input(REQUIRED, 2, c));

        assertFalse(has(problems, "厨具"), "用词差异不该触发重试：" + problems);
    }

    // ---------------------------------------------------------------- 反例：配料表与步骤一致性（本轮新增）

    @Test
    @DisplayName("配料表里的食材在步骤里没出现要报错")
    void ingredientNotUsedInSteps() {
        DishDraft d = validDraft();
        // 把所有提到「鸡蛋」的地方都换掉说法（鸡蛋 → 蛋/蛋液/蛋块），
        // 配料表却还列着鸡蛋 —— 这正是"配料表挂名、步骤里没用"的典型
        d.getSteps().set(0, "番茄去蒂切成滚刀块，另取一碗把蛋打散备用。");
        d.getSteps().set(1, "热锅倒油，中火烧至六成热，倒入蛋液快速划散后盛出。");
        d.getSteps().set(3, "倒回炒好的蛋块，加盐翻匀，撒上葱花即可出锅。");

        List<String> problems = validate(d);

        assertTrue(has(problems, "步骤中没有出现") && has(problems, "鸡蛋"), "实际：" + problems);
    }

    @Test
    @DisplayName("步骤里凭空多出配料表之外的食材要报错")
    void strayIngredientInSteps() {
        DishDraft d = validDraft();
        d.getSteps().set(2, "锅内留底油，放入葱白和香菇爆香，倒入番茄块大火翻炒出汁。");

        List<String> problems = validate(d);

        // 先自查两个前提：字典里有「香菇」、步骤里也真的写了「香菇」
        assertTrue(directory.canonicalNames().contains("香菇"), "A: dictionary lacks the item");
        assertTrue(String.join("\n", d.getSteps()).contains("香菇"), "B: steps lack the item");

        assertTrue(has(problems, "步骤里出现了配料表之外的食材") && has(problems, "香菇"),
                "C: validator did not flag it; problems=" + problems);
    }

    @Test
    @DisplayName("旧实现的误判要消失：配料表只写「鸡蛋面」不能算用上了「鸡蛋」")
    void substringMatchNoLongerPasses() {
        DishDraft d = validDraft();
        // 把鸡蛋换成「鸡蛋面」—— 旧实现用 text.contains("鸡蛋") 会判成命中
        d.setIngredients(new ArrayList<>(List.of(ing("番茄", 300), ing("鸡蛋面", 150), ing("葱", 10))));
        d.setSteps(new ArrayList<>(List.of(
                "番茄去蒂切成滚刀块备用。",
                "锅中加水煮开，下鸡蛋面煮熟后捞出过凉水备用。",
                "锅内留底油，放入葱白爆香，倒入番茄块大火翻炒出汁。",
                "把面条与番茄翻匀，加盐调味即可出锅装盘。")));

        List<String> problems = validate(d);

        assertTrue(has(problems, "没有用上用户提供的食材") && has(problems, "鸡蛋"),
                "「鸡蛋面」不等于「鸡蛋」，必须报缺：" + problems);
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

    @Test
    @DisplayName("目录没加载时也不能崩（按 ID 都查不到名字，但检查要跑完）")
    void toleratesEmptyDirectory() {
        when(directory.resolveId("番茄")).thenReturn(null);
        when(directory.nameOf(anyLong())).thenReturn(null);
        when(directory.categoryOf(anyLong())).thenReturn(null);
        when(directory.canonicalNames()).thenReturn(Set.of());

        // 不抛异常即通过；因为 ID 解析不到，归属判断只能靠名字，这里名字是对得上的
        List<String> problems = validate(validDraft());
        assertFalse(has(problems, "没有用上用户提供的食材"), "按名字仍应命中：" + problems);
    }

    // ---------------------------------------------------------------- 分档：格式 vs 约束（P1-4）

    @Test
    @DisplayName("格式问题与约束问题分开报：一个说明模型不会写结构化输出，一个是没听用户的话")
    void validateDetailedSplitsFormatAndConstraint() {
        DishDraft d = validDraft();
        d.setDifficulty(9);                                        // 格式问题
        d.getIngredients().removeIf(i -> "葱".equals(i.getName())); // 约束问题：少用了用户给的一样食材

        DishDraftValidator.ValidationResult r = validator.validateDetailed(
                d, input(REQUIRED, 2, GenerateConstraints.none()));

        assertFalse(r.formatOk(), "难度越界属于格式问题：" + r.problems());
        assertFalse(r.constraintOk(), "少了用户给的食材属于约束问题：" + r.constraintProblems());
        assertTrue(r.problems().size() > r.constraintProblems().size(),
                "全部问题里应该既有格式也有约束：" + r.problems());
        assertTrue(has(r.constraintProblems(), "没有用上用户提供的食材"),
                "约束类问题里应包含食材覆盖：" + r.constraintProblems());
        assertFalse(r.passed());
    }

    @Test
    @DisplayName("只有约束问题时：格式合格、约束不合格（这两个标记必须能单独成立）")
    void formatOkWhenOnlyConstraintFails() {
        DishDraft d = validDraft();
        d.getIngredients().removeIf(i -> "葱".equals(i.getName()));

        DishDraftValidator.ValidationResult r = validator.validateDetailed(
                d, input(REQUIRED, 2, GenerateConstraints.none()));

        assertTrue(r.formatOk(), "格式应当仍然合格：" + r.problems());
        assertFalse(r.constraintOk(), "约束不合格：" + r.constraintProblems());
    }

    @Test
    @DisplayName("全部通过时两个标记都为真")
    void bothOkWhenPassed() {
        DishDraftValidator.ValidationResult r = validator.validateDetailed(
                validDraft(), input(REQUIRED, 2, GenerateConstraints.none()));

        assertTrue(r.passed());
        assertTrue(r.formatOk());
        assertTrue(r.constraintOk());
    }
}
