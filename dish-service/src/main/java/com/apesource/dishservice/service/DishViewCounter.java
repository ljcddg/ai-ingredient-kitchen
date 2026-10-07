package com.apesource.dishservice.service;

import com.apesource.dishservice.mapper.DishMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 浏览计数。
 *
 * <p><b>为什么单独抽一个类：</b>以前这个计数挂在 {@code RecommendService} 上，
 * 结果只有推荐流这一个入口会调它 —— 用户从搜索、榜单、收藏夹点进详情页都不算数。
 * 而 {@code view_count} 是互动率的分母，点赞/收藏/打卡却是全入口统计的：
 * 分母只算一个入口，分子算全部，算出来的互动率必然虚高。
 *
 * <p>计数点收敛到<b>菜品详情接口</b>这一处，语义就是"有人看了这道菜"，
 * 不管他从哪儿来。任何新增入口只要走到详情页就自动被统计，不会再漏。
 *
 * <p><b>为什么排除作者本人：</b>作者反复刷新自己的菜会把自己刷成"高互动"，
 * 而查看率正是冷启动晋级的依据。这里的口径与推荐流的
 * {@code creator_id != userId} 保持一致。
 *
 * <p><b>不做幂等。</b>同一个人反复刷新仍会累加。要精确的话得加"用户×菜"的去重表，
 * 毕设精度下不值得——真正需要准确的是"查看率"这个相对量，
 * 而推流次数和浏览次数是用同一套（同样不幂等的）规则累加的，两边口径一致，比值仍然可比。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DishViewCounter {

    private final DishMapper dishMapper;

    /**
     * 记一次浏览。
     *
     * <p>只更新已发布且作者不是当前用户的菜。草稿、已下架、自己看自己的，
     * 影响行数都是 0，静默跳过——计数不该影响详情页的正常返回。
     *
     * @param dishId 菜品 ID
     * @param userId 当前用户（用于排除作者本人）
     */
    public void countView(Long dishId, Long userId) {
        if (dishId == null || userId == null) {
            return;
        }
        int affected = dishMapper.addViewCount(dishId, userId);
        if (affected == 0) {
            log.debug("浏览未计数：菜品 {}（用户 {}）是草稿、已下架，或就是作者本人",
                    dishId, userId);
        }
    }
}
