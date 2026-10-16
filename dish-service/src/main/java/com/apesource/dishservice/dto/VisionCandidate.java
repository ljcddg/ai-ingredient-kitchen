package com.apesource.dishservice.dto;

import java.util.List;

/**
 * 聚合后的食材候选 —— 多张图检出同一食材时合并成一条。
 *
 * @param count           多图检出数求和（两张图都有番茄 = 番茄×2）
 * @param maxConfidence   各图中的最高置信度
 * @param checked         是否默认勾选（maxConfidence ≥ 阈值时默认勾，用户可改）
 * @param sourceImageIds  来源图片 ID，前端可反查是哪几张图认出来的
 */
public record VisionCandidate(
        String ingredientId,
        String name,
        int count,
        Double maxConfidence,
        boolean checked,
        List<String> sourceImageIds) {
}
