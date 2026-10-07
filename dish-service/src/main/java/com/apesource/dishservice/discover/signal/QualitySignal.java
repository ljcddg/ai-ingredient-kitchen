package com.apesource.dishservice.discover.signal;

import com.apesource.dishservice.discover.DiscoverProperties;
import com.apesource.dishservice.es.DishDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 内容质量信号 —— 只做中等深度，够用就好。
 *
 * <p><b>为什么不做深：</b>威尔逊下界已经是统计学里处理"小样本均分虚高"的标准答案，
 * 再往上堆公式（贝叶斯平滑、时间衰减、多目标融合）的边际收益，
 * 远低于把力气花在食材匹配上。这个模块的定位是"别拖后腿"，不是"出彩"。
 *
 * <pre>
 *   quality = 0.65 × (威尔逊下界 × 100) + 0.35 × (归一化互动率 × 100)
 * </pre>
 *
 * <p><b>为什么换掉原来的 {@code (平均分/5) × 线性置信系数}</b> —— 三条实测依据（112 道已发布菜）：
 *
 * <ol>
 *   <li>置信项形同虚设：{@code LEAST(1, rating_count/3)} 实测均值 <b>0.982</b>，
 *       110 道里 108 道是满分。阈值 3 相对真实的评分人数分布（均值 31.3）低了一个数量级。</li>
 *   <li>威尔逊区分度更好：实测 0.494~0.794（极差 0.300），
 *       而"平均分/5"是 0.746~0.962（极差 0.216），挤在高位分不开。</li>
 *   <li>口径统一：高分榜本来就用 {@code rating_wilson} 排序，
 *       原来的综合得分却用另一套算法，同一道菜在两个地方的相对位置可能对不上。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualitySignal {

    private final DiscoverProperties properties;

    /**
     * 算质量分，百分制。
     *
     * <p>两处防除零都写死在这里，不要指望调用方保证：
     * {@code view_count = 0} 的新菜互动率记 0（不是 NULL——NULL 进排序会让顺序莫名其妙）；
     * 没被评分过的菜威尔逊记 0，于是它最多只能拿到互动项那部分分，
     * 天然排在有好评的菜后面，这正是想要的效果。
     */
    public double score(DishDoc doc) {
        DiscoverProperties.Quality q = properties.getQuality();

        double ratingTerm = nz(doc.getRatingWilson()) * 100d;

        int view = nz(doc.getViewCount());
        double raw = 0d;
        if (view > 0) {
            double weighted = nz(doc.getLikeCount())
                    + nz(doc.getCollectCount()) * 2d
                    + nz(doc.getCookCount()) * 3d;
            raw = weighted / view;
        }
        double baseline = q.getInteractionBaseline() > 0 ? q.getInteractionBaseline() : 1d;
        double interactionTerm = Math.min(1d, raw / baseline) * 100d;

        return q.getRatingWeight() * ratingTerm + q.getInteractionWeight() * interactionTerm;
    }

    private static double nz(Double v) {
        return v == null ? 0d : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
