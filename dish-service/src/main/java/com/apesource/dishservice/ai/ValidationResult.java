package com.apesource.dishservice.ai;

import java.util.List;

/**
 * 草稿校验结果。
 *
 * <p>校验规则已经全部迁到 Python ai-service（{@code validator.py}），这里只承载结果，
 * 由 {@link RemoteDishGenerator} 从响应里还原，供生成记录落库和重试提示词使用。
 *
 * @param problems           全部问题（格式在前、约束在后）—— 会原样拼进重试提示词
 * @param constraintProblems 其中的约束类问题（{@code problems} 的子集）
 */
public record ValidationResult(List<String> problems, List<String> constraintProblems) {

    /** 格式合格（没有格式类问题） */
    public boolean formatOk() {
        return problems.size() <= constraintProblems.size();
    }

    /** 约束合格（没有约束类问题） */
    public boolean constraintOk() {
        return constraintProblems.isEmpty();
    }

    /** 全部通过 */
    public boolean passed() {
        return problems.isEmpty();
    }
}
