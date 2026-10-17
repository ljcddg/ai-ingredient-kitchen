-- ============================================================
-- 迁移 09：用量统计 + 食材替代（两张离线统计表）
--
-- 这两张表都不是"业务输入"，是**离线统计产物**：
-- 由 sql/build-stats.py 从现有 t_dish_ingredient（679 条用量 / 113 道已发布菜）
-- 聚合出来，再被检索与校验读取。改算法 = 重跑脚本，不动业务代码。
--
-- 【为什么用量统计要分人数档】同一道菜 1 人份和 4 人份的用量差 3 倍，
-- 混在一起算分位数会得到一个"谁都不像"的中位数。
--
-- 【为什么替代要记 source】同类替代（都是叶菜）和共现替代（常一起做）
-- 是两种不同的理由，答题时要能分开说；只给一个总数说不清。
--
-- 【幂等】CREATE TABLE IF NOT EXISTS；脚本用 REPLACE INTO 重算覆盖。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_ingredient_quantity_stats (
  id            BIGINT       NOT NULL COMMENT '雪花ID',
  category      VARCHAR(20)  NOT NULL COMMENT '食材类别（蔬菜/肉类/水产…）',
  serving_bucket VARCHAR(8)  NOT NULL COMMENT '人数档：1-2 / 3-4 / 5+',
  p10           DECIMAL(10,2) NOT NULL COMMENT '该类别单样食材每人份用量的 10 分位（g）',
  p50           DECIMAL(10,2) NOT NULL COMMENT '中位数（g）',
  p90           DECIMAL(10,2) NOT NULL COMMENT '90 分位（g）',
  sample_count  INT          NOT NULL DEFAULT 0 COMMENT '样本数（菜品-食材行数）',
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_bucket (category, serving_bucket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='食材用量分位数（离线统计，按类别×人数档）';

CREATE TABLE IF NOT EXISTS t_ingredient_substitute (
  id            BIGINT      NOT NULL COMMENT '雪花ID',
  ingredient_id BIGINT      NOT NULL COMMENT '原食材ID',
  substitute_id BIGINT      NOT NULL COMMENT '可替代食材ID',
  score         DECIMAL(6,4) NOT NULL COMMENT '置信度 0~1',
  source        VARCHAR(16) NOT NULL COMMENT 'same_category（同类）/ co_occur（常一起做）',
  reason        VARCHAR(100) NULL     COMMENT '给人看的理由',
  sample_count  INT         NOT NULL DEFAULT 0 COMMENT '支撑样本数',
  update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pair (ingredient_id, substitute_id),
  KEY idx_ing (ingredient_id, score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='食材替代建议（离线统计）';

-- 自检：两张表都应输出 1 行
SHOW TABLES LIKE 't_ingredient_quantity_stats';
SHOW TABLES LIKE 't_ingredient_substitute';
