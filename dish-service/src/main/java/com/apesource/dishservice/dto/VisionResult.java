package com.apesource.dishservice.dto;

import java.util.List;

/**
 * 识菜结果（POST /ai/vision/dishes 的响应体）。
 *
 * <p>两个层次：
 * <ul>
 *   <li>{@code images} —— 按图归组的原始检出（带裁剪小图），用户核对"它认的是哪块"；</li>
 *   <li>{@code candidates} / {@code unmappedLabels} —— 跨图聚合的食材清单，
 *       前端勾选后回填厨房生成表单（映射成功的进 ingredientIds，
 *       unmapped 里勾了的进 freeIngredients，见 docs/yolo-vision-design.md §6.4）。</li>
 * </ul>
 */
public record VisionResult(
        String requestId,
        int costMs,
        List<VisionImageResult> images,
        List<VisionCandidate> candidates,
        List<VisionUnmappedLabel> unmappedLabels) {
}
