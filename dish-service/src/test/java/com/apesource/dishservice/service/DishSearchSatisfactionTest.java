package com.apesource.dishservice.service;

import com.apesource.dishservice.config.EsProperties;
import com.apesource.dishservice.dto.DishItem;
import com.apesource.dishservice.dto.DishRecallResult;
import com.apesource.dishservice.dto.DishSearchRequest;
import com.apesource.dishservice.es.DishDoc;
import com.apesource.dishservice.es.DishSearchRepository;
import com.apesource.dishservice.es.RecalledDish;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 主料满足率排序的单元测试 —— 本方案核心方法的验证。
 *
 * <p>它刻意<b>不连 ES</b>：召回结果由测试直接喂进去，
 * 这样断言的才是"给定这批候选，排序与判定对不对"，
 * 而不是"ES 装没装、索引建没建"。要验证端到端链路的另有 verify-recall.py。
 *
 * <p>用例围绕说明文档第二节提出的那个问题展开：
 * <b>「只有番茄和鸡蛋的人，不该看到一道还要牛肉和奶酪的菜被推荐为"现在就能做"」</b>。
 * 其中 {@link #newStrategyPrefersCookableDish()} 是最关键的一条：
 * 同一批候选、同一份数据，只切换排序策略，结论就反过来 ——
 * 这正是论文里那组对照实验要展示的东西。
 */
class DishSearchSatisfactionTest {

    // 主数据 ID（与 sql/dish-seed-ingredient.sql 对齐）
    private static final long TOMATO = 101L;
    private static final long EGG = 102L;
    private static final long BEEF = 104L;
    private static final long CHEESE = 121L;
    private static final long SALT = 118L;

    private DishSearchRepository repository;
    private IngredientDirectory directory;
    private EsProperties properties;
    private DishSearchService service;

    @BeforeEach
    void setUp() {
        repository = mock(DishSearchRepository.class);
        directory = mock(IngredientDirectory.class);
        properties = new EsProperties();
        service = new DishSearchService(repository, properties, directory);

        // 食材目录：只桩住用例用到的几个
        when(directory.resolveId("番茄")).thenReturn(TOMATO);
        when(directory.resolveId("西红柿")).thenReturn(TOMATO);
        when(directory.nameOf(TOMATO)).thenReturn("番茄");
        when(directory.nameOf(EGG)).thenReturn("鸡蛋");
        when(directory.nameOf(BEEF)).thenReturn("牛肉");
        when(directory.nameOf(CHEESE)).thenReturn("奶酪");
        // 盐是常备调料：不该参与满足率
        when(directory.isSeasoning(SALT)).thenReturn(true);
        when(directory.isSeasoningName("盐")).thenReturn(true);

        // 召回非空时不探活；空结果时探活为真，便于断言"是约束筛掉的"而不是"ES 挂了"
        when(repository.ping()).thenReturn(true);
    }

    // ------------------------------------------------------------------ 构造

    private DishDoc doc(long id, String name, List<Long> mainIds, List<String> names,
                        int cookTimeMin, int ratingCount, double wilson, double avg) {
        DishDoc doc = new DishDoc();
        doc.setDishId(id);
        doc.setName(name);
        doc.setMainIngredientIds(mainIds);
        doc.setIngredientIds(mainIds);
        doc.setIngredientNames(names);
        doc.setIngredientCount(names.size());
        doc.setCookTimeMin(cookTimeMin);
        doc.setRatingCount(ratingCount);
        doc.setRatingWilson(wilson);
        doc.setRatingAvg(avg);
        doc.setCollectCount(0);
        return doc;
    }

    private RecalledDish recalled(DishDoc doc) {
        RecalledDish item = new RecalledDish();
        item.setDoc(doc);
        return item;
    }

    private DishSearchRequest request(List<Long> ids, String mode, Integer maxMissing) {
        DishSearchRequest request = new DishSearchRequest();
        request.setIngredientIds(ids);
        request.setMode(mode);
        request.setMaxMissing(maxMissing);
        request.setPage(1);
        request.setSize(10);
        return request;
    }

    /** 一道"还要牛肉和奶酪"的菜：用户只有番茄和鸡蛋 → 天然缺 2 样主料 */
    private DishDoc beefCheeseDish(double wilson, int ratingCount) {
        return doc(9001L, "番茄牛肉奶酪焗饭",
                List.of(TOMATO, EGG, BEEF, CHEESE),
                List.of("番茄", "鸡蛋", "牛肉", "奶酪"),
                40, ratingCount, wilson, 4.8);
    }

    /** 一道只用番茄和鸡蛋的菜 */
    private DishDoc tomatoEggDish(double wilson, int ratingCount) {
        return doc(9002L, "番茄炒蛋",
                List.of(TOMATO, EGG),
                List.of("番茄", "鸡蛋"),
                10, ratingCount, wilson, 4.8);
    }

    // ------------------------------------------------------------------ 指标

    @Test
    @DisplayName("满足率与缺料：番茄+鸡蛋 vs 一道还要牛肉奶酪的菜")
    void computesSatisfactionAndMissing() {
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(beefCheeseDish(0.5, 1))));

        // loose 模式（缺 2 样允许）才留得下来，否则被硬约束筛掉，看不到指标
        DishRecallResult result = service.search(request(List.of(TOMATO, EGG), "loose", 2));

        assertThat(result.getCandidates()).hasSize(1);
        DishItem item = result.getCandidates().get(0);

        // 覆盖率是 2/2 = 100% —— 这正是旧指标会骗人的地方
        assertThat(item.getSimilarity()).isEqualTo(1.0);
        // 但主料满足率只有 2/4
        assertThat(item.getSatisfactionRate()).isEqualTo(0.5);
        assertThat(item.getUtilizationRate()).isEqualTo(1.0);
        assertThat(item.getMissingCount()).isEqualTo(2);
        assertThat(item.getMissingIngredients()).containsExactlyInAnyOrder("牛肉", "奶酪");
        assertThat(item.getMatchedIngredients()).containsExactlyInAnyOrder("番茄", "鸡蛋");
        assertThat(item.getReason()).contains("还缺 2 样主料");
        // 缺料就不该直出
        assertThat(item.isDirectEligible()).isFalse();
        assertThat(result.isDirectReturn()).isFalse();
    }

    @Test
    @DisplayName("严格模式（只用现有食材）：缺主料的菜被直接筛掉")
    void strictModeFiltersDishesWithMissingMain() {
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(beefCheeseDish(0.9, 20)), recalled(tomatoEggDish(0.5, 1))));

        DishRecallResult result = service.search(request(List.of(TOMATO, EGG), "strict", null));

        assertThat(result.getCandidates()).extracting(DishItem::getDishId).containsExactly(9002L);
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("宽松模式：缺料数在上限内的菜保留，超出的仍被筛掉")
    void looseModeRespectsMaxMissing() {
        DishDoc tooManyMissing = doc(9003L, "四样全要的菜",
                List.of(TOMATO, BEEF, CHEESE), List.of("番茄", "牛肉", "奶酪"), 30, 1, 0.5, 4.0);

        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(tomatoEggDish(0.5, 1)),
                        recalled(beefCheeseDish(0.5, 1)),
                        recalled(tooManyMissing)));

        // 允许缺 1 样：番茄炒蛋（缺 0）留下；焗饭（缺 2）、四样菜（缺 2）都被筛掉
        DishRecallResult result = service.search(request(List.of(TOMATO, EGG), "loose", 1));
        assertThat(result.getCandidates()).extracting(DishItem::getDishId).containsExactly(9002L);

        // 放宽到 2 样：焗饭与四样菜都回来了
        DishRecallResult wider = service.search(request(List.of(TOMATO, EGG), "loose", 2));
        assertThat(wider.getCandidates()).extracting(DishItem::getDishId)
                .containsExactlyInAnyOrder(9001L, 9002L, 9003L);
    }

    @Test
    @DisplayName("被约束筛空时，说明原因要区分「筛掉的」和「库里没有」")
    void missReasonDistinguishesFilteredFromAbsent() {
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(beefCheeseDish(0.9, 20))));

        DishRecallResult result = service.search(request(List.of(TOMATO, EGG), "strict", null));

        assertThat(result.getCandidates()).isEmpty();
        assertThat(result.getMissReason()).contains("不满足当前约束");
    }

    // ------------------------------------------------------------------ 核心对照

    @Test
    @DisplayName("同一批候选、只换排序策略：旧公式推荐做不了的菜，新策略改推能做的")
    void newStrategyPrefersCookableDish() {
        // 焗饭：覆盖率高（都用上了）且质量分高，但缺 2 样主料
        // 番茄炒蛋：同样覆盖全部现有食材，质量分低一些，但主料齐全
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(beefCheeseDish(0.90, 20)), recalled(tomatoEggDish(0.50, 20))));

        // ---- 基线（旧公式）：覆盖率 1.0 打平，质量分高的焗饭胜出 ----
        properties.getScore().setStrategy("legacy");
        DishRecallResult baseline = service.search(request(List.of(TOMATO, EGG), "loose", 2));
        assertThat(baseline.getCandidates()).extracting(DishItem::getDishId)
                .as("旧公式把「做不了但评分高」的菜排在前面")
                .startsWith(9001L);

        // ---- 新策略：满足率 + 缺料惩罚把能做的菜顶上来 ----
        properties.getScore().setStrategy("satisfaction");
        DishRecallResult improved = service.search(request(List.of(TOMATO, EGG), "loose", 2));
        assertThat(improved.getCandidates()).extracting(DishItem::getDishId)
                .as("新策略把主料齐全的菜排在前面")
                .startsWith(9002L);
    }

    // ------------------------------------------------------------------ 硬约束

    @Test
    @DisplayName("禁用食材是硬约束：命中就淘汰，而不是扣分")
    void excludedIngredientIsHardFilter() {
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(beefCheeseDish(0.9, 20)), recalled(tomatoEggDish(0.5, 1))));

        DishSearchRequest request = request(List.of(TOMATO, EGG), "loose", 2);
        request.setExcludeIngredientIds(List.of(BEEF));

        DishRecallResult result = service.search(request);

        assertThat(result.getCandidates()).extracting(DishItem::getDishId).containsExactly(9002L);
    }

    @Test
    @DisplayName("禁用食材按名字也能拦（表外食材没有 ID）")
    void excludedIngredientByName() {
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(tomatoEggDish(0.5, 1))));

        DishSearchRequest request = request(List.of(TOMATO, EGG), "loose", 2);
        request.setExcludeIngredients(List.of(" 鸡蛋 "));

        assertThat(service.search(request).getCandidates()).isEmpty();
    }

    @Test
    @DisplayName("最长耗时是硬约束：超时的菜不返回")
    void maxCookTimeIsHardFilter() {
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(beefCheeseDish(0.9, 20)), recalled(tomatoEggDish(0.5, 1))));

        DishSearchRequest request = request(List.of(TOMATO, EGG), "loose", 2);
        request.setMaxCookTimeMin(15);

        assertThat(service.search(request).getCandidates())
                .extracting(DishItem::getDishId).containsExactly(9002L);
    }

    // ------------------------------------------------------------------ 归一化与直出

    @Test
    @DisplayName("自由输入按别名归一到主数据，不再重复计数")
    void freeInputResolvesByAlias() {
        // 用一道只需要番茄的菜：这样"归一成功"与"命中"是两件可分开看的事
        DishDoc onlyTomato = doc(9007L, "糖拌番茄", List.of(TOMATO), List.of("番茄"), 5, 5, 0.72, 4.8);
        when(repository.recall(any(), any(), any())).thenReturn(List.of(recalled(onlyTomato)));

        DishSearchRequest request = new DishSearchRequest();
        // 只输入别名「西红柿」，不给 ID
        request.setFreeIngredients(List.of("西红柿"));
        request.setMode("strict");
        request.setPage(1);
        request.setSize(10);

        DishRecallResult result = service.search(request);

        // 归一化成功 → 只有 1 种食材（不是"ID 一种 + 名字一种"）
        assertThat(result.getRequestedIngredientCount()).isEqualTo(1);
        // 并且能命中按 ID 索引了番茄的菜
        assertThat(result.getCandidates()).hasSize(1);
        assertThat(result.getCandidates().get(0).getMissingCount()).isZero();
    }

    @Test
    @DisplayName("常备调料不参与满足率：菜谱要盐、你只选了番茄鸡蛋，也不算缺料")
    void seasoningDoesNotCountAsMissing() {
        DishDoc withSalt = doc(9004L, "番茄炒蛋（加盐）",
                List.of(TOMATO, EGG), List.of("番茄", "鸡蛋", "盐"), 10, 5, 0.72, 4.8);
        when(repository.recall(any(), any(), any())).thenReturn(List.of(recalled(withSalt)));

        // 严格模式：如果盐被算成必需主料，这道菜会被筛掉
        DishRecallResult result = service.search(request(List.of(TOMATO, EGG), "strict", null));

        assertThat(result.getCandidates()).hasSize(1);
        assertThat(result.getCandidates().get(0).getMissingCount()).isZero();
    }

    @Test
    @DisplayName("直出两条质量路径：评价充分 与 少量高分，都要求缺料为 0")
    void directReturnRequiresNoMissingAndQuality() {
        // 评价充分：20 人评价、威尔逊 0.72（>0.70）
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(tomatoEggDish(0.72, 20))));
        DishRecallResult full = service.search(request(List.of(TOMATO, EGG), "strict", null));
        assertThat(full.isDirectReturn()).isTrue();
        assertThat(full.getBest().getDirectPath()).isEqualTo("充分评价");

        // 少量高分：3 人评价、均分 4.8、威尔逊只有 0.5（低于 0.70）
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(tomatoEggDish(0.50, 3))));
        DishRecallResult light = service.search(request(List.of(TOMATO, EGG), "strict", null));
        assertThat(light.isDirectReturn()).isTrue();
        assertThat(light.getBest().getDirectPath()).isEqualTo("少量高分");

        // 人太少且均分不够：不直出，交给 AI
        DishDoc weak = doc(9005L, "没什么评价的菜", List.of(TOMATO, EGG),
                List.of("番茄", "鸡蛋"), 10, 2, 0.4, 4.0);
        when(repository.recall(any(), any(), any())).thenReturn(List.of(recalled(weak)));
        DishRecallResult none = service.search(request(List.of(TOMATO, EGG), "strict", null));
        assertThat(none.isDirectReturn()).isFalse();
        assertThat(none.getMissReason()).contains("评价人数不足");
    }

    @Test
    @DisplayName("宽松模式下即使排第一的菜缺料，也不直出")
    void looseModeNeverDirectReturnsIncompleteDish() {
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(beefCheeseDish(0.9, 50))));

        DishRecallResult result = service.search(request(List.of(TOMATO, EGG), "loose", 2));

        assertThat(result.getCandidates()).hasSize(1);
        assertThat(result.isDirectReturn())
                .as("说明文档明确要求：不得返回缺失必需主料的直出结果")
                .isFalse();
    }

    @Test
    @DisplayName("名字→ID 解析不到时，反向用 ID→名字 兜底，不误报缺料")
    void fallsBackToReverseLookupWhenNameCannotResolve() {
        // 目录没收录「牛肉」这个名字（resolveId 返回 null，未打桩），
        // 但 ID→名字 是通的（nameOf(104) = 牛肉）—— 归属判断应当靠反向查兜住
        DishDoc dish = doc(9006L, "番茄牛肉", List.of(TOMATO, BEEF), List.of("番茄", "牛肉"), 20, 5, 0.72, 4.8);
        when(repository.recall(any(), any(), any())).thenReturn(List.of(recalled(dish)));

        DishRecallResult result = service.search(request(List.of(TOMATO, BEEF), "strict", null));

        assertThat(result.getCandidates()).hasSize(1);
        assertThat(result.getCandidates().get(0).getMissingCount()).isZero();
    }

    // ------------------------------------------------------------------ 对照实验开关

    @Test
    @DisplayName("排序策略可按请求覆盖：对照实验要在同一份菜库快照上跑两臂")
    void requestLevelStrategyOverrideWins() {
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(beefCheeseDish(0.90, 20)), recalled(tomatoEggDish(0.50, 20))));

        // 服务端配置是 legacy，请求指定 satisfaction → 按新策略排（主料齐全的在前）
        properties.getScore().setStrategy("legacy");
        DishSearchRequest newArm = request(List.of(TOMATO, EGG), "loose", 2);
        newArm.setStrategy("satisfaction");
        assertThat(service.search(newArm).getCandidates())
                .extracting(DishItem::getDishId).startsWith(9002L);

        // 反过来：服务端 satisfaction，请求 legacy → 按旧公式排（评分高的在前）
        properties.getScore().setStrategy("satisfaction");
        DishSearchRequest legacyArm = request(List.of(TOMATO, EGG), "loose", 2);
        legacyArm.setStrategy("legacy");
        assertThat(service.search(legacyArm).getCandidates())
                .extracting(DishItem::getDishId).startsWith(9001L);
    }

    @Test
    @DisplayName("未知策略名回退到服务端配置，不让排序口径写错把接口打成 500")
    void unknownStrategyFallsBackToConfig() {
        when(repository.recall(any(), any(), any()))
                .thenReturn(List.of(recalled(tomatoEggDish(0.72, 20))));
        properties.getScore().setStrategy("satisfaction");

        DishSearchRequest req = request(List.of(TOMATO, EGG), "strict", null);
        req.setStrategy("  nonsense ");

        assertThat(service.search(req).getCandidates()).hasSize(1);
    }
}
