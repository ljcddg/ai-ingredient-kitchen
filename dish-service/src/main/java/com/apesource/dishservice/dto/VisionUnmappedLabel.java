package com.apesource.dishservice.dto;

/**
 * 模型认出来、但库里没有的 label（如 COCO 的 bowl/cup，或自训模型的新类别）。
 *
 * <p>不明示丢弃会让用户以为"没认出来"——实际是认出来了但对不上主数据。
 * 前端置灰展示；是否允许勾选后作为「自由食材」带入生成见
 * docs/yolo-vision-design.md §6.4（已确认：允许）。
 */
public record VisionUnmappedLabel(
        String label,
        int count,
        Double maxConfidence) {
}
