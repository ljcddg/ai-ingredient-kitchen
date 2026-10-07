package com.apesource.dishservice.discover;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 「发现」模块的阈值与权重。
 *
 * <p>全部由 Nacos 下发（{@code dish-service.yaml} 的 {@code discover.*}），
 * 这里的值只是兜底默认。<b>调参不改代码</b>——答辩现场要演示
 * "把匹配权重调高会怎样"，改配置重启比改代码编译快得多。
 */
@Data
@ConfigurationProperties(prefix = "discover")
public class DiscoverProperties {

    private Quality quality = new Quality();

    private Weights weights = new Weights();

    private Recall recall = new Recall();

    /**
     * 质量分的计算参数。
     *
     * <p>默认值全部用库内 112 道已发布菜的真实数据标定过，不是拍脑袋。
     */
    @Data
    public static class Quality {

        /**
         * 评分项权重。
         *
         * <p>0.65 而不是原来的 0.6：威尔逊下界的取值范围（实测 0.494~0.794）
         * 比"平均分/5"（0.746~0.962）低一截，不给评分项多补一点权重的话，
         * 好菜会被互动量大的菜压过去。
         */
        private double ratingWeight = 0.65;

        /** 互动项权重 */
        private double interactionWeight = 0.35;

        /**
         * 互动率的"满分基准"：{@code 互动率 = min(1, raw / 基准)}。
         *
         * <p>沿用冷启动阶段的标定值：对库内已发布菜实测
         * {@code raw} 最小 0.115、均值 0.233、最大 0.375。
         * 取略高于实测最大值的 0.4 —— 最好的菜接近满分，典型菜落在 0.6 附近。
         * 这个值当初就是按真实数据标出来的，<b>不要随手改</b>。
         */
        private double interactionBaseline = 0.4;
    }

    /**
     * 四个信号在 {@code feed_score} 里的权重。
     *
     * <p>排序原则：<b>能不能做 &gt; 好不好吃 &gt; 合不合情境 &gt; 合不合口味</b>。
     * 食材匹配权重最高，是因为它是这个产品独有的信号；
     * 个性化权重最低，是因为当前数据密度撑不起它（见 TasteProfileSignal）。
     */
    @Data
    public static class Weights {

        /** 食材匹配 */
        private double match = 0.45;

        /** 内容质量 */
        private double quality = 0.30;

        /** 情境适配 */
        private double context = 0.15;

        /** 个性化（当前基本不起作用） */
        private double personal = 0.10;
    }

    /**
     * 召回与呈现。
     */
    @Data
    public static class Recall {

        /**
         * 候选池大小。
         *
         * <p>取回来一批再在应用层打分排序，而不是让 ES 一次排好——
         * 因为匹配分要用到库里的最新数据，ES 侧算不了全部。
         * 这个数要明显大于单页条数，否则过滤+排序之后剩下的不够一页。
         */
        private int candidateLimit = 200;

        /**
         * 决策模式一次准备几道。
         *
         * <p>「换一个」是从这批里依次取下一个，不是重新算一遍。
         * 重算会因为随机性重复出同一道菜。
         */
        private int decidePoolSize = 20;

        /** 单页上限，挡掉 {@code ?size=100000} */
        private int maxPageSize = 30;
    }
}
