package com.apesource.dishservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch 与召回策略相关配置。
 *
 * <p>对应 Nacos 上 {@code dish-service.yaml} 里的 {@code es.*} 段落。
 * 所有字段都给了合理默认值 —— 这样即使 Nacos 上那份配置暂时拉不到，
 * 服务仍能启动并连上本机的 ES（设计上故意留的兜底，避免本地调试被配置中心卡住）。
 *
 * <p>本机环境：解压版 Elasticsearch 7.16.2，
 * 路径 {@code D:\explorer\NeatDM\\utils\elasticsearch-7.16.2}。
 * 7.x 默认关闭 security，所以走 http、无需用户名密码。
 */
@Data
@ConfigurationProperties(prefix = "es")
public class EsProperties {

    /** ES 地址。注意不要用 https —— 7.x 默认没开 TLS */
    private String uris = "http://127.0.0.1:9200";

    /** 菜品索引名 */
    private String dishIndex = "dish_index";

    /** 建连超时（秒） */
    private int connectTimeout = 3;

    /** 读超时（秒） */
    private int socketTimeout = 30;

    /** 召回参数 */
    private Recall recall = new Recall();

    /** 直出判定阈值 */
    private Judge judge = new Judge();

    /** 应用层综合分权重 */
    private Score score = new Score();

    /**
     * 召回参数。
     */
    @Data
    public static class Recall {

        /**
         * ES 侧一次召回多少条候选进「粗排池」。
         * 应用层会在这个池子里按食材覆盖率重排并分页。
         * 数据量还小时 200 足够；候选变多后要改用 search_after 深分页。
         */
        private int topN = 200;

        /**
         * 用户最多可选几种食材。
         * 这个上限是必要的 —— must 里会为每种食材拼一个 term，
         * 不设上限的话有人勾 50 种食材就会拼出一个巨大的查询。
         */
        private int maxIngredients = 10;
    }

    /**
     * 直出判定阈值。
     *
     * <p>三条同时满足才算「命中已有菜品」，直接用库里那道菜、不调 AI。
     * 注意这是一个【确定性规则】而不是概率 —— 同样的输入必然得到同样的结果，
     * 这样用户才不会觉得系统在抽风。
     */
    @Data
    public static class Judge {

        /** 食材覆盖率下限：命中食材数 / 用户所选食材数 */
        private double minSimilarity = 0.8;

        /** 评价人数下限：人太少时分数不可信 */
        private int minRatingCount = 10;

        /** 威尔逊下界得分下限（≈ 5 分制的 4.2 分），抗小样本刷分 */
        private double minRatingWilson = 0.84;
    }

    /**
     * 应用层综合分权重。
     *
     * <p>综合分 = w1 * 覆盖率 + w2 * 威尔逊分 + w3 * 收藏热度（归一化后）。
     * 三个权重之和建议为 1.0，便于直观看出每一项占多少。
     */
    @Data
    public static class Score {

        private double weightSimilarity = 0.5;

        private double weightWilson = 0.3;

        private double weightCollect = 0.2;

        /** 收藏热度归一化基准：log1p(collectCount) / log1p(该值) */
        private double collectBaseline = 1000.0;
    }
}
