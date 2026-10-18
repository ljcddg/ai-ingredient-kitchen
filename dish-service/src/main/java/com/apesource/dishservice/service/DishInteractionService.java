package com.apesource.dishservice.service;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.dto.CollectResult;
import com.apesource.dishservice.dto.DishCardView;
import com.apesource.dishservice.dto.DishDetailView;
import com.apesource.dishservice.dto.RatingSummary;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.entity.DishComment;
import com.apesource.dishservice.entity.DishRating;
import com.apesource.dishservice.mapper.DishCollectMapper;
import com.apesource.dishservice.mapper.DishCommentMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.apesource.dishservice.mapper.DishRatingMapper;
import com.apesource.dishservice.moderation.ModerationResult;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 用户对菜品的个人化操作：收藏 / 评分 / 评论，以及「我的菜品」「我的收藏」列表。
 *
 * <p>和 {@code DishGenerateService} 的分工很清楚：那边管"生成一道新菜"，
 * 这边管"对已有的菜做点什么"。两边共用同一条权限规则，见 {@link #requireVisibleDish}。
 *
 * <p><b>关于三张表为什么要分开</b>：收藏、评分、评论看起来都是"用户对菜的态度"，
 * 但语义和约束完全不同 ——
 * 收藏是"我想做"（一人一菜一条、可取消可恢复），
 * 评分是"我做过了、打几分"（一人一菜一票、可改分、是排序特征），
 * 评论是"我想说点什么"（可多条、纯文本、参与展示不参与排序）。
 * 合成一张表会让唯一键和业务规则互相打架，也会让推荐特征变得含混。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DishInteractionService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 与 {@code t_dish_comment.content} 的列长度严格一致，别只改一边 */
    private static final int MAX_CONTENT_LENGTH = 500;

    private static final int MY_DISH_LIMIT = 50;
    private static final int MY_COLLECT_LIMIT = 50;

    /** 威尔逊下界的置信水平参数，1.96 对应 95% */
    private static final double WILSON_Z = 1.96;

    private final DishMapper dishMapper;
    private final DishCollectMapper collectMapper;
    private final DishRatingMapper ratingMapper;
    private final DishCommentMapper commentMapper;

    /** 违规词过滤。评论是本服务里唯一「自由文本 + 公开展示」的入口 */
    private final SensitiveWordService sensitiveWordService;

    // ================================================================== 收藏

    /**
     * 收藏。重复点不会出错，也不会让收藏数虚增。
     *
     * <p>这就是"恢复式收藏"的入口：底层的 upsert 会把之前被逻辑删除的记录恢复回来，
     * 而不是插入一条撞唯一键的新记录。
     */
    public CollectResult collect(long userId, long dishId) {
        requireVisibleDish(dishId, userId);
        collectMapper.restoreCollect(IdWorker.getId(), dishId, userId);
        int count = refreshCollectCount(dishId);
        log.info("用户 {} 收藏菜品 {}，当前收藏数 {}", userId, dishId, count);
        return buildCollectResult(count, true);
    }

    /** 取消收藏。本来就没收藏时也返回成功（幂等），前端连点两下不该看到红字。 */
    public CollectResult uncollect(long userId, long dishId) {
        requireVisibleDish(dishId, userId);
        collectMapper.softUncollect(dishId, userId);
        int count = refreshCollectCount(dishId);
        log.info("用户 {} 取消收藏菜品 {}，当前收藏数 {}", userId, dishId, count);
        return buildCollectResult(count, false);
    }

    public boolean isCollected(long userId, long dishId) {
        return collectMapper.existsCollect(dishId, userId) > 0;
    }

    /** 我的收藏列表 */
    public List<DishCardView> myCollects(long userId) {
        return collectMapper.selectMyCollects(userId, MY_COLLECT_LIMIT);
    }

    /**
     * 按明细表重算收藏数。
     *
     * <p><b>为什么不根据 upsert 的 affected rows 做加减：</b>
     * 因为那个返回值在真实运行环境下<b>区分不出"新插入"和"重复收藏"</b>。
     * MySQL Connector/J 默认关闭 {@code useAffectedRows}（即启用 CLIENT_FOUND_ROWS），
     * 此时 {@code INSERT ... ON DUPLICATE KEY UPDATE} 对"把已有行设为相同值"
     * 也会返回 1，而不是文档里针对默认模式说的 0。
     *
     * <p>更麻烦的是**这种差异在命令行里测不出来** —— mysql 客户端不开那个标志，
     * 同一句 SQL 返回 0，于是"本地测没问题、跑起来收藏数就是虚增的"。
     * 我在这上面绕过一次弯路，所以这里索性不依赖返回值。
     *
     * <p>重算的另一个好处是**幂等**：无论用户点多少次、并发多少请求，
     * 收藏数永远等于"明细表里未删除的行数"这个事实，不会漂移。
     * 代价是一次 COUNT 查询 —— 收藏是低频操作，这点开销换确定性很划算。
     */
    private int refreshCollectCount(long dishId) {
        // 子查询查的是另一张表（t_dish_collect），MySQL 允许；
        // 如果自己查自己会被拒。dishId 是 long，不存在注入面。
        dishMapper.update(null, Wrappers.<Dish>lambdaUpdate()
                .eq(Dish::getId, dishId)
                .setSql("collect_count = (SELECT COUNT(*) FROM t_dish_collect"
                        + " WHERE dish_id = " + dishId + " AND deleted = 0)")
                .set(Dish::getUpdateTime, LocalDateTime.now()));

        Dish fresh = dishMapper.selectById(dishId);
        return fresh == null || fresh.getCollectCount() == null ? 0 : fresh.getCollectCount();
    }

    private CollectResult buildCollectResult(int collectCount, boolean collected) {
        CollectResult result = new CollectResult();
        result.setCollected(collected);
        result.setCollectCount(collectCount);
        return result;
    }

    // ================================================================== 评分

    /**
     * 打分（首次打 / 改分）。
     *
     * <p>一人一菜一票由 {@code uk_dish_user} 保证，并发重复提交会被
     * {@code ON DUPLICATE KEY UPDATE} 收编成"改分"，不需要额外加锁。
     */
    public RatingSummary rate(long userId, long dishId, Integer score, Boolean cooked) {
        requireVisibleDish(dishId, userId);
        if (score == null || score < 1 || score > 5) {
            throw new BusinessException("请给 1 到 5 星之间的评价");
        }

        int cookedFlag = Boolean.TRUE.equals(cooked) ? 1 : 0;
        ratingMapper.upsertRating(IdWorker.getId(), dishId, userId, score, cookedFlag);
        log.info("用户 {} 给菜品 {} 打了 {} 分", userId, dishId, score);

        return recomputeRating(dishId, userId);
    }

    /** 我打过的分，没打过返回 null */
    public Integer myScore(long userId, long dishId) {
        DishRating mine = ratingMapper.selectOne(
                Wrappers.<DishRating>lambdaQuery()
                        .eq(DishRating::getDishId, dishId)
                        .eq(DishRating::getUserId, userId)
                        .last("LIMIT 1"));
        return mine == null ? null : mine.getScore();
    }

    /**
     * 重算某道菜的评分冗余字段并写回 {@code t_dish}。
     *
     * <p>为什么要在 Java 里读出来算、而不是一条聚合 SQL 搞定：
     * 威尔逊下界那个公式在 SQL 里写出来没人能维护，而在 Java 里是一个
     * 可以单独写单元测试的纯函数。一道菜的评分条数在可预见的规模内（几百条），
     * 这点开销完全换得来可读性和可测性。
     *
     * <p>真要扛高并发打分，正解是异步重算 + 增量更新，那是后面的事。
     */
    private RatingSummary recomputeRating(long dishId, long userId) {
        List<DishRating> rows = ratingMapper.selectList(
                Wrappers.<DishRating>lambdaQuery().eq(DishRating::getDishId, dishId));

        int count = rows.size();
        int sum = 0;
        Integer mine = null;
        for (DishRating r : rows) {
            sum += r.getScore() == null ? 0 : r.getScore();
            if (Objects.equals(r.getUserId(), userId)) {
                mine = r.getScore();
            }
        }

        double average = count == 0 ? 0d : (double) sum / count;
        // 把 1-5 分归一化成 0-1 的"比例"，再算威尔逊下界 —— 这样它和
        // ES 侧直出判定用的 rating_wilson 是同一个口径，阈值 0.84 才对得上
        double wilson = wilsonLowerBound(average / 5.0, count);

        dishMapper.update(null, Wrappers.<Dish>lambdaUpdate()
                .eq(Dish::getId, dishId)
                .set(Dish::getRatingAvg, BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP))
                .set(Dish::getRatingCount, count)
                .set(Dish::getRatingWilson, BigDecimal.valueOf(wilson).setScale(4, RoundingMode.HALF_UP))
                .set(Dish::getUpdateTime, LocalDateTime.now()));

        RatingSummary summary = new RatingSummary();
        summary.setAverage(BigDecimal.valueOf(average).setScale(1, RoundingMode.HALF_UP).doubleValue());
        summary.setCount(count);
        summary.setWilson(BigDecimal.valueOf(wilson).setScale(4, RoundingMode.HALF_UP).doubleValue());
        summary.setMyScore(mine);
        return summary;
    }

    /**
     * 威尔逊得分下界 —— 用来压制"1 个人打 5 分"这种小样本虚高。
     *
     * <p>裸平均分有个致命问题：1 个人打 5 分是 5.0，会稳稳压过 100 个人打 4.8 的菜。
     * 威尔逊下界算的是"在最坏情况下，真实好评率至少有多高"，
     * 所以样本越少、下界压得越低，样本多了才慢慢逼近真实值。
     *
     * <p>这里额外说明为什么最终要跟 ES 侧口径对齐：{@code t_dish.rating_wilson}
     * 会被同步进 ES，直出判定用的就是它。两边算法必须完全一致，
     * 否则会出现"库里能直出、ES 里不能"这种极难排查的不一致。
     *
     * @param p 归一化后的成功率（本项目里是 平均分 / 5）
     * @param n 样本数
     * @return 0-1 的下界；n 为 0 时返回 0
     */
    public static double wilsonLowerBound(double p, int n) {
        if (n <= 0) {
            return 0d;
        }
        double z2 = WILSON_Z * WILSON_Z;
        double centre = p + z2 / (2 * n);
        double margin = WILSON_Z * Math.sqrt((p * (1 - p) + z2 / (4 * n)) / n);
        double lower = (centre - margin) / (1 + z2 / n);
        return Math.max(0d, Math.min(1d, lower));
    }

    // ================================================================== 评论

    /**
     * 发表评论。
     *
     * <p>评论里可以顺带带一个分数（展示用），但<b>计分只认 {@code t_dish_rating}</b> ——
     * 否则评论表就成了第二套计分来源，两个数对不上时没人说得清哪个是真的。
     *
     * <p><b>违规词在这里处理，分两种处置：</b>
     * <ul>
     *   <li>命中 level=1 的词 → 就地替换成等长星号，评论照常发布（
     *       用户写了 200 字经验，不该因为一个词整段丢掉）；</li>
     *   <li>命中 level=2 的词 → 评论落库但 {@code status=0} 待审核，
     *       不进详情页（违法类内容不该只变成星号）。</li>
     * </ul>
     *
     * <p><b>存的是脱敏后的文本，不是原文。</b> 详情页查询只按
     * {@code status=1} 取，若存原文，替换就白做了。原本命中的词记在
     * {@code hit_words} 里，管理员据此判断「为什么这条被屏蔽」；
     * 至于被屏蔽的那几个字本身，管理员也不需要看 —— 上下文完整就够了。
     */
    public DishDetailView.CommentView comment(long userId, long dishId, String content, Integer rating) {
        requireVisibleDish(dishId, userId);

        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BusinessException("评论内容不能为空");
        }
        if (text.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException("评论最多 " + MAX_CONTENT_LENGTH + " 字");
        }

        // 过滤发生在长度校验之后：先保证原文没超长，再谈替换
        ModerationResult moderation = sensitiveWordService.filter(text);
        boolean blocked = moderation.blocked();

        DishComment entity = new DishComment();
        entity.setDishId(dishId);
        entity.setUserId(userId);
        entity.setContent(moderation.text());
        entity.setHitWords(moderation.hits().isEmpty() ? null : String.join(",", moderation.hits()));
        // 越界的分数直接丢弃而不是报错：用户主要目的是发评论，不该因为一个附属字段失败
        entity.setRating(rating != null && rating >= 1 && rating <= 5 ? rating : null);
        entity.setLikeCount(0);
        entity.setReportCount(0);
        entity.setStatus(blocked ? 0 : 1);
        commentMapper.insert(entity);

        // 命中计数是运营指标，失败不影响发布（recordHits 内部吞异常）
        sensitiveWordService.recordHits(moderation.hits());

        DishDetailView.CommentView view = new DishDetailView.CommentView();
        view.setId(entity.getId());
        view.setUserId(userId);
        view.setNickname(commentMapper.selectNickname(userId));
        view.setContent(moderation.text());
        view.setRating(entity.getRating());
        view.setCreateTime(LocalDateTime.now().format(TIME_FMT));
        view.setMasked(moderation.masked());
        view.setPending(blocked);
        return view;
    }

    // ================================================================== 我的菜品

    /**
     * 我创建的菜品（草稿 + 已发布都算）。
     *
     * <p>查询只 select 列表需要的列，不带 {@code step_json} 这些大字段 ——
     * 列表页一条都不显示，白拉 50 份 JSON 没有意义。
     */
    public List<DishCardView> myDishes(long userId) {
        List<Dish> rows = dishMapper.selectList(
                Wrappers.<Dish>lambdaQuery()
                        .select(Dish::getId, Dish::getName, Dish::getSummary, Dish::getCoverUrl,
                                Dish::getSource, Dish::getStatus, Dish::getDifficulty,
                                Dish::getPrepTimeMin, Dish::getCookTimeMin, Dish::getNutritionSource,
                                Dish::getEnergyKcal, Dish::getRatingAvg, Dish::getRatingCount,
                                Dish::getCollectCount, Dish::getCreateTime)
                        .eq(Dish::getCreatorId, userId)
                        .orderByDesc(Dish::getCreateTime)
                        .last("LIMIT " + MY_DISH_LIMIT));
        return rows.stream().map(this::toCard).toList();
    }

    private DishCardView toCard(Dish d) {
        DishCardView card = new DishCardView();
        card.setId(d.getId());
        card.setName(d.getName());
        card.setSummary(d.getSummary());
        card.setCoverUrl(d.getCoverUrl());
        card.setSource(d.getSource());
        card.setStatus(d.getStatus());
        card.setDifficulty(d.getDifficulty());
        card.setPrepTimeMin(d.getPrepTimeMin());
        card.setCookTimeMin(d.getCookTimeMin());
        card.setNutritionSource(d.getNutritionSource());
        card.setEnergyKcal(toInt(d.getEnergyKcal()));
        card.setRatingAvg(d.getRatingAvg() == null
                ? null : d.getRatingAvg().setScale(1, RoundingMode.HALF_UP).doubleValue());
        card.setRatingCount(d.getRatingCount());
        card.setCollectCount(d.getCollectCount());
        card.setCreateTime(d.getCreateTime() == null ? null : d.getCreateTime().format(TIME_FMT));
        return card;
    }

    private Integer toInt(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    // ================================================================== 共用

    /**
     * 取一道"当前用户有权操作"的菜，没有就抛异常。
     *
     * <p><b>这条规则是数据安全级别的</b>：草稿（status=0）是别人还没发布的菜，
     * 漏掉这个校验，任何人都能靠猜 ID 看到、甚至给别人的草稿打分收藏。
     *
     * <p>提示语统一用"菜品不存在"，不区分"真的不存在"和"存在但你无权看" ——
     * 否则这个接口就成了探测"别人有哪些草稿"的工具。
     */
    private Dish requireVisibleDish(long dishId, long userId) {
        Dish dish = dishMapper.selectById(dishId);
        if (dish == null) {
            throw new BusinessException("菜品不存在");
        }
        boolean draft = Objects.equals(dish.getStatus(), 0);
        if (draft && !Objects.equals(dish.getCreatorId(), userId)) {
            throw new BusinessException("菜品不存在");
        }
        return dish;
    }
}
