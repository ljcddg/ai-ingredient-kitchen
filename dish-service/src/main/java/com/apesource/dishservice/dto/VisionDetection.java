package com.apesource.dishservice.dto;

/**
 * 单条检出（YOLO 检测框）→ 映射库内食材后的视图。
 *
 * <p>record 不写 getXxx()/isXxx() —— Jackson 会把它们当属性序列化，
 * 写进去就读不回来（项目铁律）。
 *
 * @param ingredientId 映射上的库内食材 ID；没映射上为 null。Long 一律序列化成字符串
 *                     （前端禁止 Number(id)）
 * @param cropUrl      检测框裁剪小图的访问路径（/uploads/vision/crops/...），用户看得见"它认的是哪块"
 */
public record VisionDetection(
        String label,
        Double confidence,
        String cropUrl,
        boolean mapped,
        String ingredientId,
        String ingredientName) {
}
