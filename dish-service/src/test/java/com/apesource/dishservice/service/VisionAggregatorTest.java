package com.apesource.dishservice.service;

import com.apesource.dishservice.dto.VisionCandidate;
import com.apesource.dishservice.dto.VisionUnmappedLabel;
import com.apesource.dishservice.entity.VisionLabel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 识菜映射与聚合规则测试（纯逻辑，不起 Spring、不连库、不需要 ultralytics）。
 *
 * <p>这里守的是"多图检出 → 食材清单"这段最容易错的规则：
 * <ol>
 *   <li><b>两级映射</b>：先查 t_vision_label（COCO 英文名），未命中走目录别名归一，
 *       都不中进 unmapped —— 三级顺序错一级，用户体验就完全不同（静默丢弃最糟）；</li>
 *   <li><b>阈值过滤</b>：低于 label 配置的 min_confidence 直接丢，不进 unmapped（废话少说）；</li>
 *   <li><b>跨图聚合</b>：count 求和、maxConfidence 取最大、sourceImageIds 去重 —— 
 *       "两张图都有番茄"应显示番茄×2，而不是两条番茄。</li>
 * </ol>
 */
class VisionAggregatorTest {

    private IngredientDirectory directory;

    @BeforeEach
    void setUp() {
        directory = mock(IngredientDirectory.class);
        // 默认"认不出"必须显式 stub：Mockito 对未 stub 的 Long 方法返回 0L 而不是 null，
        // 不设这条的话"表外 label"用例会拿到 0 这个假 ID（踩过一次）
        when(directory.resolveId(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        when(directory.resolveId("番茄")).thenReturn(101L);
        when(directory.resolveId("鸡蛋")).thenReturn(102L);
        when(directory.nameOf(101L)).thenReturn("番茄");
        when(directory.nameOf(102L)).thenReturn("鸡蛋");
    }

    private static VisionLabel label(String name, Long refId, Double minConfidence) {
        VisionLabel row = new VisionLabel();
        row.setLabel(name);
        row.setLabelType("ingredient");
        row.setRefId(refId);
        row.setMinConfidence(minConfidence == null ? null : BigDecimal.valueOf(minConfidence));
        row.setStatus(1);
        return row;
    }

    private static VisionAggregator.RawDetection raw(String label, double confidence) {
        return new VisionAggregator.RawDetection(label, confidence, List.of(0, 0, 10, 10));
    }

    // ---------- 两级映射 ----------

    @Test
    @DisplayName("第一级：t_vision_label 精确命中优先于目录归一")
    void labelTableWins() {
        // carrot 在表里配到 113；目录里 resolveId("carrot") 也（假造）能解析到别的 ID
        when(directory.resolveId("carrot")).thenReturn(999L);
        Map<String, VisionLabel> labelMap = Map.of("carrot", label("carrot", 113L, 0.4));

        assertThat(VisionAggregator.resolveMapping("carrot", labelMap, directory)).isEqualTo(113L);
    }

    @Test
    @DisplayName("第二级：表里没有的 label 走目录别名归一（自训模型中文名的路）")
    void fallsBackToDirectory() {
        assertThat(VisionAggregator.resolveMapping("番茄", Map.of(), directory)).isEqualTo(101L);
    }

    @Test
    @DisplayName("第三级：都不中返回 null（进 unmapped，不静默丢弃）")
    void unknownLabelReturnsNull() {
        assertThat(VisionAggregator.resolveMapping("bowl", Map.of(), directory)).isNull();
    }

    @Test
    @DisplayName("表里 labelType=dish 的配置不当作食材用")
    void dishTypeNotTreatedAsIngredient() {
        Map<String, VisionLabel> labelMap = Map.of("gongbao", label("gongbao", 456L, null));
        labelMap.get("gongbao").setLabelType("dish");

        assertThat(VisionAggregator.resolveMapping("gongbao", labelMap, directory)).isNull();
    }

    // ---------- 阈值过滤 ----------

    @Test
    @DisplayName("低于 label 配置的 min_confidence 直接丢弃，连 unmapped 都不进")
    void belowThresholdDropped() {
        Map<String, VisionLabel> labelMap = Map.of("carrot", label("carrot", 113L, 0.6));

        List<VisionAggregator.MappedDetection> kept = VisionAggregator.mapAll(
                List.of(raw("carrot", 0.55), raw("carrot", 0.75)), labelMap, directory);

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).raw().confidence()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("没配阈值的 label 不过这道筛（Python 侧 conf 已初筛）")
    void noThresholdKeepsAll() {
        List<VisionAggregator.MappedDetection> kept = VisionAggregator.mapAll(
                List.of(raw("番茄", 0.1)), Map.of(), directory);

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).ingredientId()).isEqualTo(101L);
    }

    // ---------- 跨图聚合 ----------

    @Test
    @DisplayName("两张图都有番茄 → 番茄×2，来源图去重收集")
    void countSumsAcrossImages() {
        List<List<VisionAggregator.MappedDetection>> perImage = List.of(
                List.of(new VisionAggregator.MappedDetection(raw("番茄", 0.9), 101L, "番茄")),
                List.of(new VisionAggregator.MappedDetection(raw("番茄", 0.7), 101L, "番茄"),
                        new VisionAggregator.MappedDetection(raw("番茄", 0.6), 101L, "番茄")));

        VisionAggregator.Aggregation result = VisionAggregator.aggregate(
                perImage, List.of("img-1", "img-2"), directory, 0.8);

        assertThat(result.candidates()).hasSize(1);
        VisionCandidate tomato = result.candidates().get(0);
        assertThat(tomato.ingredientId()).isEqualTo("101");
        assertThat(tomato.name()).isEqualTo("番茄");
        assertThat(tomato.count()).isEqualTo(3);
        assertThat(tomato.maxConfidence()).isEqualTo(0.9);
        assertThat(tomato.sourceImageIds()).containsExactly("img-1", "img-2");
    }

    @Test
    @DisplayName("默认勾选线：maxConfidence ≥ 阈值才默认勾上")
    void checkedByThreshold() {
        List<List<VisionAggregator.MappedDetection>> perImage = List.of(
                List.of(new VisionAggregator.MappedDetection(raw("番茄", 0.85), 101L, "番茄"),
                        new VisionAggregator.MappedDetection(raw("鸡蛋", 0.55), 102L, "鸡蛋")));

        VisionAggregator.Aggregation result = VisionAggregator.aggregate(
                perImage, List.of("img-1"), directory, 0.8);

        VisionCandidate tomato = result.candidates().stream()
                .filter(c -> "101".equals(c.ingredientId())).findFirst().orElseThrow();
        VisionCandidate egg = result.candidates().stream()
                .filter(c -> "102".equals(c.ingredientId())).findFirst().orElseThrow();
        assertThat(tomato.checked()).isTrue();
        assertThat(egg.checked()).isFalse();
    }

    @Test
    @DisplayName("unmapped 按 label 聚合（bowl 认出两个 → count=2）")
    void unmappedAggregatedByLabel() {
        List<List<VisionAggregator.MappedDetection>> perImage = List.of(
                List.of(new VisionAggregator.MappedDetection(raw("bowl", 0.8), null, null)),
                List.of(new VisionAggregator.MappedDetection(raw("bowl", 0.6), null, null)));

        VisionAggregator.Aggregation result = VisionAggregator.aggregate(
                perImage, List.of("img-1", "img-2"), directory, 0.8);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.unmapped()).hasSize(1);
        VisionUnmappedLabel bowl = result.unmapped().get(0);
        assertThat(bowl.label()).isEqualTo("bowl");
        assertThat(bowl.count()).isEqualTo(2);
        assertThat(bowl.maxConfidence()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("候选按置信度降序（前端网格顺序即此序）")
    void candidatesSortedByConfidenceDesc() {
        List<List<VisionAggregator.MappedDetection>> perImage = List.of(
                List.of(new VisionAggregator.MappedDetection(raw("番茄", 0.5), 101L, "番茄"),
                        new VisionAggregator.MappedDetection(raw("鸡蛋", 0.95), 102L, "鸡蛋")));

        VisionAggregator.Aggregation result = VisionAggregator.aggregate(
                perImage, List.of("img-1"), directory, 0.8);

        assertThat(result.candidates()).extracting(VisionCandidate::name)
                .containsExactly("鸡蛋", "番茄");
    }

    @Test
    @DisplayName("没有检出时返回两个空列表（前端按空态展示，不报错）")
    void emptyDetections() {
        VisionAggregator.Aggregation result = VisionAggregator.aggregate(
                List.of(List.of(), List.of()), List.of("img-1", "img-2"), directory, 0.8);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.unmapped()).isEmpty();
    }
}
