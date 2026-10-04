package com.apesource.dishservice.dto;

import java.util.List;

/**
 * 食材候选的审核请求体。
 *
 * <p>通过与拒绝共用同一个结构：一次可以审一条，也可以审一批
 * （管理页面里勾选多行再点按钮，走的就是同一套接口）。
 * 批量接口刻意<b>要求显式列出 id</b>，而不是"把所有够次数的都通过" ——
 * 后者等于把阈值当成审核结论，正是这次要改掉的东西。
 *
 * @param ids    候选食材ID列表
 * @param reason 审核说明（拒绝时必填，通过时可选）
 */
public record IngredientReviewRequest(List<Long> ids, String reason) {
}
