-- ============================================================
-- migration-11：单品用量分位 + RAGAS 另两项指标落库
-- ============================================================
-- 【为什么在 t_ingredient_quantity_stats 之外再建一张】
-- 原来那张是「类别 × 人数档」的聚合（蔬菜 p90=175g）。拿它当校验上限会误杀：
-- 类别里混着「葱姜」（一勺）和「土豆」（半斤），p90 被两头拉扯，
-- 4 人份的土豆烧牛肉放 300g 土豆是完全正常的，按类别上限却会被判超标。
--
-- 所以校验要**两级**：
--   ① 单品级（本表）：带鱼 1-2 人份 p90=250g，直接对这一样食材生效，最准；
--   ② 类别级（migration-09 那张）：单品样本不够时兜底；
--   ③ 固定上限（validator 里的 PER_PERSON_GRAM_BY_CATEGORY）：连类别样本都不够时兜底。
-- 样本量门槛写在代码里（单品 n≥3、类别 n≥20）—— 库里只有 113 道菜，
-- 单个食材能凑到 3 条已属不易，门槛再高就永远用不上统计了。
--
-- 【为什么 p90 而不是 max】
-- max 会被一条录入错误的菜（比如料酒 500g）永久污染；p90 天然抗单点异常。
-- 校验时再乘 1.5 的余量系数，等于"比同类菜最多的一档再宽 50%"。
--
-- 【RAGAS 三指标为什么落在这里】
-- faithfulness 已经在 migration-10 落了。这里补另外三项：
--   answer_relevance：三维修正加权（食材覆盖 0.5 / 口味契合 0.3 / 约束契合 0.2），
--                     只把"这次请求真的提了"的维度计入分母 —— 回答是否在答所问
--   ctx_precision   ：参考主料里"用户手上真有"的比例（检索准不准）
--   ctx_recall      ：用户食材被参考覆盖的比例（检索全不全）
-- 三项同一行存，才能按 reference_dish_ids 是否为空分组做 A/B —— 这是第 5 章的核心数据。
-- 同样：NULL 表示"判不了"，不是 0 分。无参考时 ctx 两项必为 NULL，
-- 但 answer_relevance 照算（那是"未注入参考"对照组的唯一数据）。
--
-- 【为什么 answer_relevance 不是"用到的食材数/用户食材数"】
-- 校验器已经会拦掉漏食材的草稿，通过的那批这一项恒等于 1，指标退化成常数、
-- 永远满分 —— 等于没测。必须加口味与约束两维，指标在"格式都合格"的样本之间才有区分度。
-- ============================================================

USE project0914;

CREATE TABLE IF NOT EXISTS t_ingredient_amount_stats (
    id            BIGINT        NOT NULL COMMENT '主键（确定性：md5(ingredient:bucket) 取前 15 位）',
    ingredient_id BIGINT        NOT NULL COMMENT '食材主数据 ID',
    serving_bucket VARCHAR(8)   NOT NULL COMMENT '人数档：1-2 / 3-4 / 5+',
    p50           DECIMAL(10,2) NOT NULL COMMENT '中位用量（g/份）',
    p90           DECIMAL(10,2) NOT NULL COMMENT '90 分位用量（g/份），校验上限的基准',
    sample_count  INT           NOT NULL DEFAULT 0 COMMENT '样本数；低于 3 的不入库',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ing_bucket (ingredient_id, serving_bucket),
    KEY idx_bucket (serving_bucket)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '单品用量分位（离线脚本 build-stats.py 生成）';

ALTER TABLE t_ai_generation_record
    ADD COLUMN answer_relevance DECIMAL(5,4) NULL COMMENT '答案相关性 = 用到的用户食材数/用户食材数；NULL=判不了' AFTER faith,
    ADD COLUMN ctx_precision    DECIMAL(5,4) NULL COMMENT '上下文精确率 = 参考里用户有的主料/参考主料' AFTER answer_relevance,
    ADD COLUMN ctx_recall       DECIMAL(5,4) NULL COMMENT '上下文召回率 = 用户食材被参考覆盖的比例' AFTER ctx_precision;

DESC t_ingredient_amount_stats;
