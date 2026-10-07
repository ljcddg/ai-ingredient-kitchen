package com.apesource.dishservice.recommend;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.dto.DishCardView;
import com.apesource.dishservice.entity.DishPushLog;
import com.apesource.dishservice.mapper.PushLogMapper;
import com.apesource.dishservice.mapper.RecommendMapper;
import com.apesource.dishservice.service.DishInteractionService;
import com.apesource.dishservice.service.IngredientDirectory;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 推荐池（冷启动）的取流与计数。
 *
 * <p>核心就一句话：<b>新发布的菜没有曝光，所以系统主动把它推到用户面前，
 * 用真实流量验证质量。</b>推流次数、查看次数都是这次验证的证据。
 *
 * <p>关于分页：这里<b>没有 offset</b>。因为 24 小时去重的存在，
 * 上一页推过的菜这一次本来就被排除了，再翻一页自然拿到的是新的菜。
 * 加 offset 反而会跳过没被推过的菜。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    /** 单页上限，挡掉 ?size=100000 */
    private static final int MAX_PAGE_SIZE = 30;

    /** 匹配档位，回传给前端展示角标用 */
    private static final String MATCH_FULL = "full";
    private static final String MATCH_PARTIAL = "partial";
    private static final String MATCH_RANDOM = "random";

    private final RecommendMapper recommendMapper;
    private final PushLogMapper pushLogMapper;
    private final DishInteractionService dishInteractionService;
    private final IngredientDirectory directory;

    /**
     * 取一屏推荐流。
     *
     * <p>三级优先级：全匹配 → 部分匹配 → 随机补齐，凑满 size 为止。
     *
     * @param ingredientIds   用户勾选的食材 ID，可以为空（为空就纯随机）
     * @param ingredientNames 用户在输入框里自由敲的食材名（表外食材）——
     *                        先按名字/别名归一到主数据，归不上的丢弃。
     *                        两条召回路都走「用到了它」这一个语义，
     *                        和找菜的 ES 召回口径保持一致。
     */
    public RecommendPage feed(Long userId, List<Long> ingredientIds,
                              List<String> ingredientNames, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        // 【关键】表外名字必须先尝试归一到主数据 ID，再并进同一个集合。
        // 否则会出现「菜谱里番茄是按 ingredient_id 存的，而用户敲的是『西红柿』」
        // 这种对不上的情况 —— 用户的输入被静默丢掉，还不报错。
        // 归不上的（真正的主数据外食材）在当前「只走 t_dish_ingredient」的实现下
        // 会被忽略，所以这里直接丢弃而不是留着一个用不到的值。
        Set<Long> idSet = new LinkedHashSet<>();
        if (ingredientIds != null) {
            ingredientIds.stream().filter(Objects::nonNull).forEach(idSet::add);
        }
        if (ingredientNames != null) {
            for (String raw : ingredientNames) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Long id = directory.resolveId(raw.trim());
                if (id != null) {
                    idSet.add(id);
                } else {
                    log.debug("推荐：表外食材「{}」未收录，本次忽略", raw);
                }
            }
        }
        List<Long> ids = new ArrayList<>(idSet);

        // LinkedHashMap 保序：先放进去的排在前面，正好就是三级优先级的顺序
        LinkedHashMap<Long, String> picked = new LinkedHashMap<>();

        // ① 全匹配：用户选的食材这道菜全都有
        if (!ids.isEmpty()) {
            take(recommendMapper.selectCandidateIds(ids, ids.size(), ids.size(), userId, safeSize),
                    picked, safeSize, MATCH_FULL);
        }
        // ② 部分匹配：命中一部分。只选 1 种食材时不存在"部分"，跳过
        if (ids.size() >= 2 && picked.size() < safeSize) {
            take(recommendMapper.selectCandidateIds(ids, 1, ids.size() - 1, userId, safeSize * 2),
                    picked, safeSize, MATCH_PARTIAL);
        }
        // ③ 随机补齐：锅里还有位置就随便塞几道，保证推荐池的新菜有曝光
        if (picked.size() < safeSize) {
            take(recommendMapper.selectCandidateIds(null, 0, 0, userId, safeSize * 3),
                    picked, safeSize, MATCH_RANDOM);
        }

        if (picked.isEmpty()) {
            log.debug("推荐池已空：用户 {} 在 24 小时内看完了所有可推的菜", userId);
            return new RecommendPage(safePage, safeSize, 0, List.of());
        }

        // 计数：出现在推荐流里就算一次推流。
        // 放在这里而不是让前端再上报一次"曝光"，省一次往返，
        // 毕设精度下"返回给客户端"和"出现在屏幕上"可以视为等价。
        recommendMapper.addPushCount(new ArrayList<>(picked.keySet()));

        LocalDateTime now = LocalDateTime.now();
        List<DishPushLog> logs = new ArrayList<>(picked.size());
        for (Long dishId : picked.keySet()) {
            DishPushLog row = new DishPushLog();
            row.setId(IdWorker.getId());
            row.setDishId(dishId);
            row.setUserId(userId);
            row.setPushTime(now);
            logs.add(row);
        }
        pushLogMapper.batchUpsert(logs);

        // 组装：数据库返回的顺序不保证，按 picked 的顺序重排，保证优先级可见
        Map<Long, DishCardView> byId = new LinkedHashMap<>();
        for (DishCardView card : recommendMapper.selectCardsByIds(new ArrayList<>(picked.keySet()))) {
            byId.put(card.getId(), card);
        }
        List<DishCardView> ordered = new ArrayList<>(picked.size());
        for (Map.Entry<Long, String> e : picked.entrySet()) {
            DishCardView card = byId.get(e.getKey());
            if (card == null) {
                continue;
            }
            card.setMatchType(e.getValue());
            ordered.add(card);
        }

        log.debug("推荐流：用户 {} 选 {} 种食材，返回 {} 条（全匹配 {} 条）",
                userId, ids.size(), ordered.size(), countOf(picked, MATCH_FULL));

        return new RecommendPage(safePage, safeSize, ordered.size(), ordered);
    }

    /*
     * 浏览计数已迁出：见 DishViewCounter。
     *
     * 放在这里的问题是它只覆盖"从推荐流点进详情"这一条路，
     * 而 view_count 是互动率的分母，点赞/收藏/打卡却统计全部入口 ——
     * 分母偏小会让互动率虚高。现在统一由菜品详情接口计数。
     */

    /**
     * 上报互动。
     *
     * <p>收藏走现有的 {@link DishInteractionService}：它会写明细表并按明细重算计数，
     * 这样"我的收藏"列表才不会出现只加了数字却看不到菜的情况。
     * 点赞和打卡是本次新增的计数，按毕设的简化要求不做幂等（前端按钮点击后置灰）。
     */
    public void reportInteract(Long dishId, Long userId, String type) {
        switch (type) {
            case "like" -> recommendMapper.addLikeCount(dishId);
            case "cook" -> recommendMapper.addCookCount(dishId);
            case "collect" -> dishInteractionService.collect(userId, dishId);
            default -> throw new BusinessException("不支持的互动类型：" + type);
        }
    }

    /** 冷启动排行榜 */
    public RankResult coldRank(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safeSize;
        long total = recommendMapper.countColdRank();
        return new RankResult(total, safePage, safeSize,
                recommendMapper.selectColdRank(safeSize, offset));
    }

    private void take(List<Long> candidates, LinkedHashMap<Long, String> picked,
                      int size, String matchType) {
        if (candidates == null) {
            return;
        }
        for (Long id : candidates) {
            if (picked.size() >= size) {
                break;
            }
            picked.putIfAbsent(id, matchType);
        }
    }

    private long countOf(Map<Long, String> picked, String matchType) {
        return picked.values().stream().filter(v -> v.equals(matchType)).count();
    }

    /** 推荐流一页 */
    public record RecommendPage(int page, int size, int count, List<DishCardView> items) {
    }

    /** 冷启动榜一页 */
    public record RankResult(long total, int page, int size, List<DishCardView> items) {
    }
}
