package com.apesource.dishservice.dto;

import java.util.List;

/**
 * 一张图片的识别结果。
 *
 * @param imageId    上传时分配的 ID（img-1 / img-2 ...），前端按它关联上传预览
 * @param imageUrl   原图访问路径（/uploads/vision/2026/10/xxx.jpg）
 * @param detections 该图全部检出（映射前 → 映射后），按置信度降序
 * @param error      该图处理失败的原因（读图失败等）；正常为 null。
 *                   单张图失败不影响同批其他图
 */
public record VisionImageResult(
        String imageId,
        String imageUrl,
        List<VisionDetection> detections,
        String error) {
}
