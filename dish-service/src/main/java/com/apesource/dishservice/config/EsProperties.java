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
 * <p>本机环境：Docker 容器 {@code es}（镜像 {@code elasticsearch:8.16.0}，单节点）。
 * ES 8 默认<b>开启</b> security，该容器用 {@code xpack.security.enabled=false}
 * 显式关掉了，所以仍然走 http、无需用户名密码 —— 与原生 7.16.2 时代保持一致。
 * 原生解压版 7.16.2 已退役，不再启动。
 */
@Data
@ConfigurationProperties(prefix = "es")
public class EsProperties {

    /** ES 地址。不要改成 https —— 本容器已显式关闭 security，走 http 免密 */
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

        /**
         * 食材覆盖率下限：命中食材数 / 用户所选食材数。
         * <p>【只给 legacy 排序策略用】。新策略（satisfaction）不看这个阈值，
         * 它要求的是「缺料数为 0」—— 见 {@code DishSearchService#isDirectEligible}。
         */
        private double minSimilarity = 0.8;

        /** 评价人数下限：人太少时分数不可信 */
        private int minRatingCount = 10;

        /**
         * 威尔逊下界得分下限。
         *
         * <p><b>为什么从 0.84 调到 0.70</b>：按威尔逊下界公式，10 次满分也只有约
         * <b>0.722</b> —— 也就是说原来 minRatingCount=10 与 minRatingWilson=0.84
         * 这两条永远不可能同时成立，直出条件实际上恒为假。0.70 是"10 次满分能过、
         * 9 次满分（约 0.596）过不了"的位置，两条阈值这才互相兼容。
         *
         * <p>另外要说明它的性质：这里的输入是<b>平均星级归一化</b>后的值，
         * 不是"好评/总数"的二项比例，所以它只是一个<b>排序启发式</b>，
         * 不应该解释成严格的好评率置信区间。
         */
        private double minRatingWilson = 0.70;

        /**
         * 「少量评价但高分」这条备用质量路径的人数和均分下限。
         *
         * <p>为什么需要第二条路：新菜天然评价少，按"人够多"卡它会永远直出不了，
         * 只能一直调 AI。给一条"3 个人都打了高分"的通道，并在结果里标明走的是哪条路
         * （见 {@code DishItem.directPath}），便于事后核对这条宽松通道有没有被滥用。
         */
        private int lightRatingCount = 3;

        /** 备用路径的平均分下限（5 分制） */
        private double lightRatingAvg = 4.5;
    }

    /**
     * 应用层综合分权重。
     *
     * <p>综合分 = w1 * 覆盖率 + w2 * 威尔逊分 + w3 * 收藏热度（归一化后）。
     * 三个权重之和建议为 1.0，便于直观看出每一项占多少。
     */
    @Data
    public static class Score {

        /**
         * 排序策略。
         * <ul>
         *   <li>{@code satisfaction}（默认）：主料满足率 + 利用率 + 质量分 − 缺料惩罚</li>
         *   <li>{@code legacy}：原来的「覆盖率 + 威尔逊 + 收藏热度」</li>
         * </ul>
         * 保留 legacy 不是为了兼容，而是<b>为了做对照实验</b>：
         * 同一批查询分别用两种策略跑，比较 Precision@5 与平均缺料数 ——
         * 这是说明文档第五节要求的那组实验，没有这个开关就无从对比。
         */
        private String strategy = "satisfaction";

        /**
         * 【legacy 策略用】覆盖率权重。
         * <p>覆盖率的定义是「命中食材数 / 用户所选食材数」，也就是新口径里的"利用率"。
         * 它只说明用了你多少食材，并不说明这道菜现在能不能做 ——
         * 这正是这次要补上满足率的原因。
         */
        private double weightSimilarity = 0.5;

        /** 主料满足率权重：|U∩R| / |R|，衡量"这道菜的主料你齐了没有" */
        private double weightSatisfaction = 0.40;

        /** 食材利用率权重：|U∩R| / |U|，衡量"你的食材被用掉了多少" */
        private double weightUtilization = 0.15;

        /** 质量分（威尔逊下界）权重 */
        private double weightWilson = 0.30;

        /** 收藏热度权重 */
        private double weightCollect = 0.15;

        /**
         * 缺料惩罚系数（0~1 量纲，与其它项一致）。
         * <p>缺得越多扣得越多，但扣到 {@link #missingPenaltyCap} 样就封顶 ——
         * 否则"缺 8 样"会把分数压成负数，不同候选之间反而区分不出差别。
         */
        private double missingPenalty = 0.25;

        /** 缺料惩罚的封顶样数 */
        private int missingPenaltyCap = 3;

        /** 收藏热度归一化基准：log1p(collectCount) / log1p(该值) */
        private double collectBaseline = 1000.0;
    }
}
