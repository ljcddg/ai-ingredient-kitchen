-- ============================================================
-- P1-6 索引同步待办：把"这道菜需要同步到 ES"变成一条可重试、可补偿的记录
--
-- 执行方式（在项目根目录）：
--   mysql -uroot -proot --default-character-set=utf8mb4 project0914 \
--     -e "source sql/migration-04-es-sync-task.sql"
--
-- 【为什么要这张表】
--   原来的做法是"发布事务提交后立刻同步一次，失败就只打一行 warn，
--   等每天 04:00 的全量重建补齐"。后果很实际：ES 短时抖动 → 这道菜
--   最长 24 小时搜不到，而日志里只有一行 warn，没人会去查。
--   说明文档要求：事务内写待同步记录、提交后由后台任务更新 ES、
--   失败有限退避重试、并提供手动补偿入口。
--
-- 【幂等怎么保证】
--   唯一键落在 dish_id 上：**同一道菜只有一条待办**。重复发布/撤回只是
--   刷新这条记录（version + 立即重试），不会堆出几十条任务。
--   而"到底该写入还是该删除"不靠任务类型决定 ——
--   同步器每次都按菜品**当前状态**决定（见 DishEsSyncService.syncOne），
--   所以不存在"旧任务把已撤回的菜重新塞回索引"这种事。
--
-- 【幂等性】
--   本脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_es_sync_task
(
    id           BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    dish_id      BIGINT       NOT NULL COMMENT '菜品ID',
    version      DATETIME     DEFAULT NULL COMMENT '触发时的菜品版本（取 update_time），便于追溯是哪次变更引发的',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-待同步，1-已完成，2-失败待重试，3-已放弃（需人工介入）',
    attempts     INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    next_retry_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可执行时间（退避用）',
    last_error   VARCHAR(500) DEFAULT NULL COMMENT '最后一次失败原因',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dish (dish_id) COMMENT '一道菜只有一条待办：重复变更只刷新它，不堆积',
    KEY idx_due (status, next_retry_at) COMMENT '后台任务扫描：找到点的待办'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='ES 索引同步待办（可重试、可补偿）';

-- ---------- 核对 ----------
SELECT status, COUNT(*) AS cnt FROM t_es_sync_task GROUP BY status;
