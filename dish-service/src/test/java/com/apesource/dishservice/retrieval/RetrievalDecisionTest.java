package com.apesource.dishservice.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三档决策 + 查询指纹的纯逻辑测试。
 *
 * <p>这两块是论文第 5 章统计的地基：档位判错，直出率就是错的；
 * 指纹不稳定，同一组食材会被统计成多个查询，命中率也是错的。
 */
class RetrievalDecisionTest {

    @Test
    @DisplayName("直出：缺料为 0 且质量达标 → DIRECT")
    void directWhenEligible() {
        assertThat(RetrievalDecision.decide(true, 1.0, 8)).isEqualTo(RetrievalDecision.DIRECT);
    }

    @Test
    @DisplayName("有可用候选且满足率 ≥ 0.4 → 带参考生成")
    void ragGenerateWhenPartialMatch() {
        assertThat(RetrievalDecision.decide(false, 0.6, 3)).isEqualTo(RetrievalDecision.RAG_GENERATE);
        // 边界：正好 0.4 算带参考（门槛是"不低于"）
        assertThat(RetrievalDecision.decide(false, 0.4, 1)).isEqualTo(RetrievalDecision.RAG_GENERATE);
    }

    @Test
    @DisplayName("满足率太低或没有候选 → 裸生成")
    void freshGenerateWhenTooWeak() {
        assertThat(RetrievalDecision.decide(false, 0.39, 3)).isEqualTo(RetrievalDecision.GENERATE_FRESH);
        assertThat(RetrievalDecision.decide(false, 0.9, 0)).isEqualTo(RetrievalDecision.GENERATE_FRESH);
        assertThat(RetrievalDecision.decide(false, 0d, 0)).isEqualTo(RetrievalDecision.GENERATE_FRESH);
    }

    @Test
    @DisplayName("查询指纹与选择顺序无关")
    void queryHashIsOrderInsensitive() {
        String a = RetrievalDecisionLogger.queryHash(List.of(2L, 1L), List.of("松露", "秋葵"));
        String b = RetrievalDecisionLogger.queryHash(List.of(1L, 2L), List.of("秋葵", "松露"));
        assertThat(a).isEqualTo(b).hasSize(32);
    }

    @Test
    @DisplayName("不同食材集合得到不同指纹")
    void queryHashDiffersForDifferentSets() {
        String a = RetrievalDecisionLogger.queryHash(List.of(1L), List.of());
        String b = RetrievalDecisionLogger.queryHash(List.of(1L, 2L), List.of());
        // 空集合也不能把 null 拼成字符串 "null" 混进去
        String c = RetrievalDecisionLogger.queryHash(null, null);
        assertThat(a).isNotEqualTo(b);
        assertThat(c).isNotEqualTo(RetrievalDecisionLogger.queryHash(List.of(), List.of("松露")));
    }
}
