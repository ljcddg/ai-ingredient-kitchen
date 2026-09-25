package com.apesource.dishservice.service;

import com.apesource.dishservice.dto.DishCardView;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.mapper.DishMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 菜品榜单。
 *
 * <p>数据来源就是 {@code t_dish} 上的几个冗余字段（评分、收藏数），
 * <b>不需要额外的统计表</b> —— 打分和收藏时已经把这些字段维护好了
 * （见 {@code DishInteractionService}）。这也是之前选择"冗余计数"而不是
 * "每次聚合算"的回报：榜单查询变成了一条带索引的排序查询。
 *
 * <p>榜单只统计**已发布**的菜。草稿是私有的，进榜等于泄漏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DishRankService {

    /** 已发布 */
    private static final int STATUS_PUBLISHED = 1;

    /** 单页上限。挡掉 ?size=100000 这种把库拖垮的请求 */
    private static final int MAX_PAGE_SIZE = 50;

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final DishMapper dishMapper;

    /**
     * 榜单类型。
     *
     * <p>接收字符串而不是直接用枚举做参数，是因为前端 URL 上写的是 {@code ?type=hot}；
     * 用 {@link #of(String)} 统一转换，未知值退回热门榜而不是报错 ——
     * 一个拼错的参数不该让页面白屏。
     */
    @Getter
    @RequiredArgsConstructor
    public enum RankType {
        HOT("热门榜", "大家都在做的"),
        TOP("高分榜", "评价最好的"),
        LATEST("最新榜", "刚出锅的");

        private final String title;
        private final String subtitle;

        public static RankType of(String raw) {
            if (raw == null || raw.isBlank()) {
                return HOT;
            }
            return switch (raw.trim().toLowerCase()) {
                case "top", "rating", "score" -> TOP;
                case "latest", "new", "newest" -> LATEST;
                default -> HOT;
            };
        }
    }

    /**
     * 榜单页。
     *
     * @param total 该榜单的总条数，前端据此决定要不要显示"下一页"
     */
    public record RankPage(String type, String title, String subtitle,
                           long total, int page, int size,
                           List<DishCardView> items) {
    }

    /**
     * 取一页榜单。
     *
     * <p>分页参数在这里夹紧（页码至少 1、页大小 1~50）而不是交给前端自觉：
     * 数据库不认识"谦让"，一个 {@code size=100000} 就够把连接池占满。
     */
    public RankPage rank(String type, int page, int size) {
        RankType rankType = RankType.of(type);

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safeSize;

        List<DishCardView> items = switch (rankType) {
            case HOT -> dishMapper.selectHotRank(safeSize, offset);
            case TOP -> dishMapper.selectTopRank(safeSize, offset);
            case LATEST -> dishMapper.selectLatestRank(safeSize, offset);
        };

        long total = switch (rankType) {
            // 高分榜的计数条件和查询本身必须一致（都带 rating_count >= 1），
            // 否则会出现"总数说有 3 页、翻到第 3 页却是空的"
            case TOP -> dishMapper.countRated();
            case HOT, LATEST -> {
                Long c = dishMapper.selectCount(
                        Wrappers.<Dish>lambdaQuery().eq(Dish::getStatus, STATUS_PUBLISHED));
                yield c == null ? 0L : c;
            }
        };

        log.debug("榜单 {} 第 {} 页（每页 {}），共 {} 条，返回 {} 条",
                rankType, safePage, safeSize, total, items.size());

        return new RankPage(rankType.name().toLowerCase(), rankType.getTitle(),
                rankType.getSubtitle(), total, safePage, safeSize, items);
    }
}
