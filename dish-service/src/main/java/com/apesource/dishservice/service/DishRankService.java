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

    /**
     * 能进质量榜（热门 / 高分）的最低等级：已晋级。
     *
     * <p>推荐池里的新菜（rank_level = 1）还没经过真实流量验证，
     * 不进热门榜和高分榜；最新榜不受此限（它是新菜的曝光位）。
     */
    private static final int RANK_LEVEL_ON_BOARD = 2;

    /** 单页上限。挡掉 ?size=100000 这种把库拖垮的请求 */
    private static final int MAX_PAGE_SIZE = 50;

    /** 默认每页 10 条 —— 榜单是"看排名"的场景，10 条一屏比 20 条更像榜单 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * 榜单总容量：每种榜最多只放前 100 条（10 页）。
     *
     * <p>为什么封顶：名次越往后越没有区分度（第 200 名和第 201 名的"热门度"
     * 几乎没有差别），但深分页的代价是实打实的 —— offset 越大，MySQL 要扫描
     * 再丢弃的行越多。100 条既是够用的浏览深度，也是可控的查询成本。
     *
     * <p>封顶要同时体现在两处：① 超过容量的页直接返回空页不查库；
     * ② 对外返回的 total 也要封顶，否则前端会算出 100 条以外的页码。
     */
    private static final int RANK_TOTAL_LIMIT = 100;

    private final DishMapper dishMapper;

    /**
     * 冷启动榜的查询放在推荐模块的 Mapper 里，不混进 {@link DishMapper} ——
     * 前三榜按收藏/威尔逊/发布时间排，冷启动榜按综合得分排，
     * 是两套完全不同的口径，混在一起只会互相干扰。
     */
    private final com.apesource.dishservice.mapper.RecommendMapper recommendMapper;

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
        LATEST("最新榜", "刚出锅的"),
        /** 冷启动榜：从推荐池里靠真实流量验证杀出来的菜，按综合得分排序 */
        COLD("冷启动榜", "新菜里的黑马");

        private final String title;
        private final String subtitle;

        public static RankType of(String raw) {
            if (raw == null || raw.isBlank()) {
                return HOT;
            }
            return switch (raw.trim().toLowerCase()) {
                case "top", "rating", "score" -> TOP;
                case "latest", "new", "newest" -> LATEST;
                case "cold", "recommend", "newcomer" -> COLD;
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

        // 起点已经在容量之外，直接给空页，连库都不用查
        if (offset >= RANK_TOTAL_LIMIT) {
            log.debug("榜单 {} 第 {} 页起点 {} 已超出容量 {}，返回空页",
                    rankType, safePage, offset, RANK_TOTAL_LIMIT);
            return new RankPage(rankType.name().toLowerCase(), rankType.getTitle(),
                    rankType.getSubtitle(), RANK_TOTAL_LIMIT, safePage, safeSize, List.of());
        }

        // 最后一页往往装不满，按剩余容量裁剪，不多查
        int fetchSize = Math.min(safeSize, RANK_TOTAL_LIMIT - offset);

        List<DishCardView> items = switch (rankType) {
            case HOT -> dishMapper.selectHotRank(fetchSize, offset);
            case TOP -> dishMapper.selectTopRank(fetchSize, offset);
            case LATEST -> dishMapper.selectLatestRank(fetchSize, offset);
            case COLD -> recommendMapper.selectColdRank(fetchSize, offset);
        };

        long total = switch (rankType) {
            // 高分榜：countRated() 已与 selectTopRank 对齐（rating_count >= 1 且 rank_level >= 2）
            case TOP -> dishMapper.countRated();
            case COLD -> recommendMapper.countColdRank();
            // 热门榜自己刚加了 rank_level >= 2，计数必须跟着加 ——
            // 不然就是「总数说有 3 页、翻到第 3 页却是空的」同一个坑
            case HOT -> {
                Long c = dishMapper.selectCount(
                        Wrappers.<Dish>lambdaQuery()
                                .eq(Dish::getStatus, STATUS_PUBLISHED)
                                .ge(Dish::getRankLevel, RANK_LEVEL_ON_BOARD));
                yield c == null ? 0L : c;
            }
            // 最新榜不按 rank_level 过滤（它是新菜的曝光位），计数同样不加
            case LATEST -> {
                Long c = dishMapper.selectCount(
                        Wrappers.<Dish>lambdaQuery().eq(Dish::getStatus, STATUS_PUBLISHED));
                yield c == null ? 0L : c;
            }
        };

        // 总量按容量封顶。不封顶的话前端会算出 100 条以外的页码，
        // 翻过去却是空的 —— 比直接不给更让人困惑
        total = Math.min(total, RANK_TOTAL_LIMIT);

        log.debug("榜单 {} 第 {} 页（每页 {}），共 {} 条，返回 {} 条",
                rankType, safePage, safeSize, total, items.size());

        return new RankPage(rankType.name().toLowerCase(), rankType.getTitle(),
                rankType.getSubtitle(), total, safePage, safeSize, items);
    }
}
