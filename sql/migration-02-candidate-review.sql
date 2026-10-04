-- ============================================================
-- P0-2 食材候选审核：给 t_ingredient_candidate 加"审核人 / 审核时间 / 审核说明"
--
-- 执行方式（在项目根目录）：
--   mysql -uroot -proot --default-character-set=utf8mb4 project0914 \
--     -e "source sql/migration-02-candidate-review.sql"
--
-- 【为什么需要这一步】
--   说明文档第四节要求：候选食材「改为『候选—通过或拒绝』的审核流程，
--   记录审核人、时间和理由」。原来的实现是"用次数够不够决定要不要转正"
--   （promoteReady 按 use_count >= 3 批量转正），问题在于：
--   「出现得多」并不等于「名字有效」—— 一个错别字被几个人一起敲，一样能刷到 3 次，
--   然后就被写进正式食材表，污染归一化与营养计算的基准。
--   现在阈值只用来给待办排序，转正必须由管理员逐条（或勾选批量）判定，
--   判定结果连同审核人、时间、理由一起留痕。
--
-- 【幂等性】
--   三列都可能已经加过，所以先查 information_schema，
--   只把缺的列拼进一条 ALTER 里执行；都齐了就跳过。
-- ============================================================

SET @add_columns := (
    SELECT CONCAT('ALTER TABLE t_ingredient_candidate ',
                  GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_def) SEPARATOR ', '))
    FROM (
        SELECT 'reviewed_by'   AS col_name, 'BIGINT DEFAULT NULL COMMENT ''审核人用户ID（管理员）''' AS col_def
        UNION ALL
        SELECT 'review_time',   'DATETIME DEFAULT NULL COMMENT ''审核时间'''
        UNION ALL
        SELECT 'review_reason', 'VARCHAR(200) DEFAULT NULL COMMENT ''审核说明（拒绝时必填）'''
    ) cols
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS c
        WHERE c.TABLE_SCHEMA = DATABASE()
          AND c.TABLE_NAME = 't_ingredient_candidate'
          AND c.COLUMN_NAME = cols.col_name
    )
);

SET @ddl := IFNULL(@add_columns, 'SELECT ''审核列已全部存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 核对 ----------
-- 待审清单（管理员待办，按热度倒序；>=3 次的只是"优先看"，不代表可以直接转正）
SELECT id, name, use_count, status, last_seen
FROM t_ingredient_candidate
WHERE status = 0
ORDER BY use_count DESC, last_seen DESC;
