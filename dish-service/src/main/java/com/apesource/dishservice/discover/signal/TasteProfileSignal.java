package com.apesource.dishservice.discover.signal;

import com.apesource.dishservice.es.DishDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 个性化信号 —— <b>当前是空实现，故意的</b>。
 *
 * <p>接口和权重位都留好了（{@code discover.weights.personal}），
 * 但真的去算会得到一堆噪声。理由是用实测数据说话的：
 *
 * <ul>
 *   <li>{@code taste_tag} 87% 有值（97/112），但取值极散：
 *       家常 19、酱香 9、清淡 9、清爽 5、麻辣 4、鲜嫩 4……
 *       几十个标签分到 112 道菜上，每类个位数，统计不出任何稳定的用户偏好。</li>
 *   <li>{@code spicy_level} 只有 21 道非零（91 道是不辣），
 *       "辣度偏好"在数据上只有一个粗糙的二元信号。</li>
 *   <li>更根本的：库里的评分和收藏大多来自造数脚本，不是真实用户行为。
 *       拿造出来的行为去拟合口味画像，拟合的是脚本作者的偏好。</li>
 * </ul>
 *
 * <p>等有了真实用户行为再启用。届时实现方向是：按用户收藏/评分过的菜
 * 聚合出食材与口味标签的偏好向量，再和菜品向量算余弦相似度。
 * 现在返回中性值，等于把权重让给其他三个信号——这比硬算一个假分数要诚实。
 */
@Slf4j
@Service
public class TasteProfileSignal {

    /** 中性分：不偏爱也不排斥 */
    private static final double NEUTRAL = 0.5;

    /**
     * 这道菜合不合当前用户的口味。
     *
     * @param doc    菜品文档
     * @param userId 当前用户（目前未使用，留着是为了将来不改动调用签名）
     */
    public double score(DishDoc doc, Long userId) {
        return NEUTRAL;
    }
}
