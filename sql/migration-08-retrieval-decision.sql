-- ============================================================
-- 迁移 08：检索决策日志 t_retrieval_decision（三档决策的证据表）
--
-- 【为什么要有这张表】现在"要不要调 AI"这件事只有一个布尔
--   DishRecallResult.directReturn，它回答不了论文第 5 章真正要问的问题：
--   检索优先到底省下了多少次模型调用？被省掉的那些决策是不是都值得？
--   这张表就是唯一的证据来源 —— 每发生一次决策落一行，
--   把当时的满足率、召回条数、参考条数、耗时一起记下来。
--
-- 【三档口径】（docs/thesis/RAG与功能扩展方案.md §3.2）
--   DIRECT         满足率 ≥ 0.8 且质量达标 → 直接返回库内菜品，不调模型
--   RAG_GENERATE   满足率 0.4~0.8 或质量不达标，但有可用候选 → 带参考生成
--   GENERATE_FRESH 满足率 < 0.4 或无可用候选 → 裸生成（无参考）
--
-- 【source 为什么分两种】一次"用户点了生成"会经过两个决策点：
--   SEARCH   —— 召回接口判定的档位（决定前端要不要发起生成）
--   GENERATE —— 真正生成时到底注入了几条参考（RAG 是否真的生效）
--   两者可能不一致（比如 SEARCH 判了 RAG_GENERATE、但生成时索引刚好挂了），
--   不分开记就会把"判了但没做到"算成"判对了"。
--
-- 【幂等】CREATE TABLE IF NOT EXISTS，重复执行不出错。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_retrieval_decision (
  id                BIGINT       NOT NULL COMMENT '雪花ID',
  user_id           BIGINT       NULL     COMMENT '用户ID，匿名/后台为空',
  source            VARCHAR(16)  NOT NULL COMMENT '决策来源：SEARCH / GENERATE',
  query_hash        CHAR(32)     NOT NULL COMMENT '食材集合的稳定指纹（排序后拼接取MD5）',
  decision          VARCHAR(20)  NOT NULL COMMENT 'DIRECT / RAG_GENERATE / GENERATE_FRESH',
  satisfaction_rate DECIMAL(5,4) NULL     COMMENT '最优候选的主料满足率 0~1',
  matched_count     INT          NOT NULL DEFAULT 0 COMMENT '命中主料数',
  missing_count     INT          NOT NULL DEFAULT 0 COMMENT '还缺几样主料',
  es_hit            INT          NOT NULL DEFAULT 0 COMMENT 'ES 召回条数',
  reference_count   INT          NOT NULL DEFAULT 0 COMMENT '实际注入模型的参考菜谱条数',
  es_available      TINYINT      NOT NULL DEFAULT 1 COMMENT '检索引擎是否可用（0=降级）',
  fallback          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否发生了回退（如判了RAG但参考为空）',
  cost_ms           INT          NOT NULL DEFAULT 0 COMMENT '本次决策链路耗时',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '决策时间',
  PRIMARY KEY (id),
  KEY idx_query_time (query_hash, create_time),
  KEY idx_decision_time (decision, create_time),
  KEY idx_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检索决策日志（三档决策证据）';

-- 自检：应输出 1 行 t_retrieval_decision
SHOW TABLES LIKE 't_retrieval_decision';
