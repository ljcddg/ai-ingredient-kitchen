package com.apesource.dishservice.service;

import com.apesource.dishservice.ai.VisionClient;
import com.apesource.dishservice.dto.VisionCandidate;
import com.apesource.dishservice.dto.VisionUnmappedLabel;
import com.apesource.dishservice.entity.VisionLabel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 识菜结果的映射与聚合 —— **纯逻辑，不碰 IO**（落盘/调 Python/裁剪都在 VisionService）。
 *
 * <p>独立成类是为了单测：不起 Spring、不连库、不装 ultralytics 也能把
 * "多图检出 → 食材清单"这段最容易错的核心规则测干净。
 *
 * <p>两级映射（docs/yolo-vision-design.md §6.3）：
 * <ol>
 *   <li>t_vision_label 精确命中（label → refId）—— COCO 英文名必须显式配；</li>
 *   <li>未命中走 {@link IngredientDirectory#resolveId}（正式名 + 别名归一）
 *       —— 自训模型的中文食材名走这条，无需逐条配表；</li>
 *   <li>都不中 → unmappedLabels 明示返回，不静默丢弃。</li>
 * </ol>
 */
public final class VisionAggregator {

    private VisionAggregator() {
    }

    /** Python 侧原始检出（映射前） */
    public record RawDetection(String label, Double confidence, List<Integer> box) {
    }

    /** 映射后的检出。ingredientId 为 null = unmapped */
    public record MappedDetection(RawDetection raw, Long ingredientId, String ingredientName) {
        boolean mapped() {
            return ingredientId != null;
        }
    }

    /** 聚合结果：跨图合并后的食材清单 + 未映射 label */
    public record Aggregation(List<VisionCandidate> candidates, List<VisionUnmappedLabel> unmapped) {
    }

    // ---------- 映射 ----------

    /**
     * 单条 label → 食材 ID。
     *
     * @param labelMap t_vision_label 的启用项（label → 配置），调用方查库构建
     */
    public static Long resolveMapping(String label, Map<String, VisionLabel> labelMap,
                                      IngredientDirectory directory) {
        if (label == null) {
            return null;
        }
        VisionLabel configured = labelMap.get(label);
        if (configured != null && configured.getRefId() != null
                && "ingredient".equals(configured.getLabelType())) {
            return configured.getRefId();
        }
        return directory.resolveId(label);
    }

    /**
     * 批量映射 + 按 label 阈值过滤。
     *
     * <p>低于 t_vision_label.min_confidence 的检出直接丢弃（连 unmapped 都不进）
     * —— 用户没勾它，展示出来只会困惑。没配表的 label 不过这道筛
     * （Python 侧 conf 已初筛过一轮）。
     */
    public static List<MappedDetection> mapAll(List<RawDetection> raws,
                                               Map<String, VisionLabel> labelMap,
                                               IngredientDirectory directory) {
        List<MappedDetection> out = new ArrayList<>(raws.size());
        for (RawDetection raw : raws) {
            VisionLabel configured = labelMap.get(raw.label());
            if (configured != null && configured.getMinConfidence() != null
                    && raw.confidence() != null
                    && raw.confidence() < configured.getMinConfidence().doubleValue()) {
                continue;
            }
            Long id = resolveMapping(raw.label(), labelMap, directory);
            out.add(new MappedDetection(raw, id, id == null ? null : directory.nameOf(id)));
        }
        return out;
    }

    // ---------- 聚合 ----------

    /**
     * 多图检出 → 一份食材清单。
     *
     * <p>规则（docs/yolo-vision-design.md §6.3）：
     * <ul>
     *   <li>按食材 ID 聚合：count = 各图检出数求和；maxConfidence 取最大；
     *       sourceImageIds 去重收集；</li>
     *   <li>checked = maxConfidence ≥ defaultCheckedConfidence（高置信默认勾选）；</li>
     *   <li>unmapped 按 label 同样聚合；</li>
     *   <li>两个列表都按 maxConfidence 降序（前端网格顺序即此序）。</li>
     * </ul>
     *
     * @param perImage 按图分组的映射后检出（与 VisionResult.images 同序）
     * @param directory 食材目录，用于回填候选的正式名（目录里没有的表外 ID 不会出现在这）
     */
    public static Aggregation aggregate(List<List<MappedDetection>> perImage,
                                        List<String> imageIds,
                                        IngredientDirectory directory,
                                        double defaultCheckedConfidence) {
        // key = ingredientId；value = 累加器
        Map<Long, Acc> byIngredient = new LinkedHashMap<>();
        Map<String, Acc> byLabel = new LinkedHashMap<>();

        for (int i = 0; i < perImage.size(); i++) {
            String imageId = imageIds.get(i);
            for (MappedDetection d : perImage.get(i)) {
                if (d.mapped()) {
                    byIngredient.computeIfAbsent(d.ingredientId(), k -> new Acc())
                            .accept(d.raw().confidence(), imageId);
                } else {
                    byLabel.computeIfAbsent(d.raw().label(), k -> new Acc())
                            .accept(d.raw().confidence(), imageId);
                }
            }
        }

        List<VisionCandidate> candidates = new ArrayList<>();
        byIngredient.forEach((id, acc) -> candidates.add(new VisionCandidate(
                String.valueOf(id), directory.nameOf(id), acc.count, acc.maxConfidence,
                acc.maxConfidence != null && acc.maxConfidence >= defaultCheckedConfidence,
                new ArrayList<>(acc.imageIds))));
        candidates.sort(byConfidence());

        List<VisionUnmappedLabel> unmapped = new ArrayList<>();
        byLabel.forEach((label, acc) -> unmapped.add(new VisionUnmappedLabel(
                label, acc.count, acc.maxConfidence)));
        unmapped.sort(Comparator.comparing(
                VisionUnmappedLabel::maxConfidence, Comparator.nullsLast(Comparator.reverseOrder())));

        return new Aggregation(candidates, unmapped);
    }

    /** 从 Python 原始检出构造 RawDetection（VisionClient 响应 → 聚合器输入） */
    public static RawDetection toRaw(VisionClient.PythonDetection d) {
        return new RawDetection(d.label(), d.confidence(), d.box());
    }

    // ---------- 内部 ----------

    private static final class Acc {
        int count;
        Double maxConfidence;
        final java.util.LinkedHashSet<String> imageIds = new java.util.LinkedHashSet<>();

        void accept(Double confidence, String imageId) {
            count++;
            if (confidence != null && (maxConfidence == null || confidence > maxConfidence)) {
                maxConfidence = confidence;
            }
            imageIds.add(imageId);
        }
    }

    private static Comparator<VisionCandidate> byConfidence() {
        return Comparator.comparing(VisionCandidate::maxConfidence,
                Comparator.nullsLast(Comparator.reverseOrder()));
    }
}
