package com.apesource.dishservice.retrieval;

import com.apesource.dishservice.entity.RetrievalDecisionLog;
import com.apesource.dishservice.mapper.RetrievalDecisionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 检索决策日志写入器。
 *
 * <p><b>为什么单独一个组件而不是塞进 DishSearchService：</b>
 * 决策在三处发生（召回接口、生成前置检索、将来的离线评测），
 * 落库口径必须只有一份 —— 否则"同样的判定记出两种字段含义"，
 * 第 5 章的统计就没法做了。
 *
 * <p><b>写库失败必须无害：</b>这张表是给论文取证用的，不是业务主链路。
 * 数据库抖一下不该让用户搜不到菜（与 {@code DishDraftWriter.save()} 同一原则）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalDecisionLogger {

    private final RetrievalDecisionLogMapper mapper;

    /**
     * 记一次召回侧决策。
     *
     * @param satisfaction 最优候选的主料满足率
     * @param matched      命中主料数
     * @param missing      还缺几样主料
     * @param esHit        ES 召回条数
     * @param esAvailable  检索引擎是否可用
     * @param costMs       本次召回耗时
     */
    public void logSearch(Long userId, String queryHash, RetrievalDecision decision,
                          double satisfaction, int matched, int missing,
                          int esHit, boolean esAvailable, int costMs) {
        save(RetrievalDecisionLog.builder()
                .userId(userId)
                .source("SEARCH")
                .queryHash(queryHash)
                .decision(decision.name())
                .satisfactionRate(rate(satisfaction))
                .matchedCount(matched)
                .missingCount(missing)
                .esHit(esHit)
                .referenceCount(0)
                .esAvailable(esAvailable ? 1 : 0)
                .fallback(0)
                .costMs(costMs)
                .build());
    }

    /**
     * 记一次生成侧决策：真正喂给模型几条参考。
     *
     * <p>{@code fallback=true} 的含义是「召回判了 RAG_GENERATE，但生成时实际没拿到参考」
     * —— 这通常是索引挂了或同步延迟。不记这一位，就会把"判了但没做到"
     * 当成"判定正确"统计，直出率会虚高。
     */
    public void logGenerate(Long userId, String queryHash, RetrievalDecision decision,
                            int referenceCount, boolean esAvailable, int costMs) {
        boolean fallback = decision == RetrievalDecision.RAG_GENERATE && referenceCount == 0;
        save(RetrievalDecisionLog.builder()
                .userId(userId)
                .source("GENERATE")
                .queryHash(queryHash)
                .decision(fallback ? RetrievalDecision.GENERATE_FRESH.name() : decision.name())
                .satisfactionRate(null)
                .matchedCount(0)
                .missingCount(0)
                .esHit(0)
                .referenceCount(referenceCount)
                .esAvailable(esAvailable ? 1 : 0)
                .fallback(fallback ? 1 : 0)
                .costMs(costMs)
                .build());
    }

    /**
     * 食材集合的稳定指纹。
     *
     * <p>ID 升序 + 名字排序后再拼，保证「先选 A 再选 B」和「先选 B 再选 A」
     * 得到同一个哈希 —— 否则同一组食材会被统计成两种查询，命中率全是错的。
     */
    public static String queryHash(Collection<Long> ids, Collection<String> names) {
        List<Long> sortedIds = ids == null ? new ArrayList<>()
                : new ArrayList<>(ids.stream().filter(java.util.Objects::nonNull).toList());
        sortedIds.sort(Comparator.naturalOrder());

        List<String> sortedNames = names == null ? new ArrayList<>()
                : new ArrayList<>(names.stream()
                        .filter(n -> n != null && !n.isBlank())
                        .map(String::trim)
                        .toList());
        sortedNames.sort(Comparator.naturalOrder());

        StringBuilder raw = new StringBuilder();
        for (Long id : sortedIds) {
            raw.append(id).append(',');
        }
        raw.append('|');
        for (String name : sortedNames) {
            raw.append(name).append(',');
        }
        return md5(raw.toString());
    }

    // ------------------------------------------------------------------ 内部

    private void save(RetrievalDecisionLog entity) {
        try {
            if (entity.getCreateTime() == null) {
                entity.setCreateTime(LocalDateTime.now());
            }
            mapper.insert(entity);
        } catch (Exception e) {
            // 日志表写不进去不影响检索本身，记 debug 就够（生产会刷屏）
            log.debug("检索决策日志写入失败（不影响主流程）：{}", e.getMessage());
        }
    }

    private static BigDecimal rate(double value) {
        double clamped = Math.max(0d, Math.min(1d, value));
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必备算法，走不到这里；真走到了也不能让检索挂掉
            return "unknown";
        }
    }
}
