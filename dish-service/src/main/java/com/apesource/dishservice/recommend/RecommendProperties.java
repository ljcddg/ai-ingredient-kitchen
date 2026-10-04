package com.apesource.dishservice.recommend;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 冷启动推荐池的阈值与算分参数。
 *
 * <p>全部由 Nacos 下发（{@code dish-service.yaml} 的 {@code recommend.*}），
 * 这里的值只是兜底默认值。<b>演示环境和生产环境只改配置、不改代码</b>，
 * 分档见 {@code docs/cold-start-design.md} §6.2。
 */
@Data
@ConfigurationProperties(prefix = "recommend")
public class RecommendProperties {

    private Promote promote = new Promote();

    private Score score = new Score();

    /**
     * 晋级阈值：推荐池的菜满足这些条件才升到 rank_level = 2。
     */
    @Data
    public static class Promote {

        /** 最少推流次数。演示档 10 / 测试档 20 / 生产档 50 */
        private int minPushCount = 10;

        /** 最低查看率 view_count / push_count。演示档 0.30 / 测试档 0.25 / 生产档 0.10 */
        private double minViewRate = 0.30;

        /**
         * 查看次数的绝对下限。
         *
         * <p>只看比例会出事：推 10 次碰巧有 3 次被点开就是 30%，
         * 可这 3 次很可能只是作者自己和两个朋友，偶然性太大。
         * 加一道绝对量兜底，成本只是 SQL 多一个条件。
         */
        private int minViewCount = 3;
    }

    /**
     * 综合得分的计算参数。
     */
    @Data
    public static class Score {

        /** 评分人数达到多少才拿满置信系数。演示档 3 / 测试档 5 / 生产档 10 */
        private int minScoreCount = 3;

        /**
         * 互动率的"满分基准"：{@code 互动率 = LEAST(1, raw / 基准)}。
         *
         * <p>按线上真实数据标定，不是拍脑袋：对库里已发布菜实测
         * {@code raw} 均值 0.233、最大值 0.375、互动渗透率均值 14%，
         * 取略高于实测最大值的 0.4 —— 最好的菜接近满分，典型菜落在 0.6 附近。
         * 真实跑一段时间后要重新标定，所以这个值必须可配。
         */
        private double interactionBaseline = 0.4;
    }
}
