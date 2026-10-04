-- ============================================================
-- P1-4 AI 生成记录：一次生成到底发生了什么，事后能查、能复现、能评价
--
-- 执行方式（在项目根目录）：
--   mysql -uroot -proot --default-character-set=utf8mb4 project0914 \
--     -e "source sql/migration-03-generation-record.sql"
--
-- 【为什么需要单独一张表，而不是把字段加进 t_ai_generate_task】
--   任务表是"队列/进度"的视角：一个任务一行，字段少、写得频繁。
--   而生成记录是"实验与追溯"的视角，要存模型原始输出（可能上万字符）、
--   校验问题、提示词版本这些大而全的东西。混在一起会让任务表变宽变重，
--   而任务列表接口（前端每 1.5 秒轮询一次）会把它们一起查出来。
--
-- 【分别记录三件事（说明文档第三节的要求）】
--   format_ok     格式合格：JSON 能不能解析、字段齐不齐、取值在不在合理区间
--   constraint_ok 约束合格：有没有用上用户的食材、有没有踩忌口、超没超时、设备够不够
--   review_status 审核/发布状态：生成出来之后进没进找菜库
--   前两者由 DishDraftValidator 给出，后者由发布流程给出。
--   分开记的理由很实际：格式不合格是"模型不会写结构化输出"，
--   约束不合格是"模型没听用户的话"，两者的改进方向完全不同，混成一个"失败率"就没法用。
--
-- 【幂等性】
--   本脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_ai_generation_record
(
    id                 BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    task_id            BIGINT        NOT NULL COMMENT '对应 t_ai_generate_task.id',
    user_id            BIGINT        NOT NULL COMMENT '发起用户ID',
    status             TINYINT       NOT NULL COMMENT '本次生成的终态：2-成功，3-失败，4-用户取消（生成已完成但结果被丢弃）',

    -- ---------- 可复现：换模型/改提示词之后，老结果还能不能解释得通 ----------
    model              VARCHAR(64)   DEFAULT NULL COMMENT '模型标识（如 gemma4:e2b）',
    prompt_version     VARCHAR(32)   DEFAULT NULL COMMENT '系统提示词版本（内容 MD5 前 8 位）',
    temperature        DECIMAL(4, 2) DEFAULT NULL COMMENT '生成温度',
    max_attempts       INT           DEFAULT NULL COMMENT '允许的最大尝试次数',

    -- ---------- 质量与开销 ----------
    attempts           INT           NOT NULL DEFAULT 0 COMMENT '实际尝试次数（1 表示一次通过）',
    cost_ms            INT           NOT NULL DEFAULT 0 COMMENT 'AI 耗时（ms，含重试）',
    format_ok          TINYINT       NOT NULL DEFAULT 0 COMMENT '格式合格：0-否，1-是',
    constraint_ok      TINYINT       NOT NULL DEFAULT 0 COMMENT '约束合格：0-否，1-是',
    validation_errors  VARCHAR(1000) DEFAULT NULL COMMENT '最后一次的校验问题（分号分隔）',

    -- ---------- 追溯 ----------
    raw_output         MEDIUMTEXT    DEFAULT NULL COMMENT '最后一次的模型原始输出（截断保存）',
    dish_id            BIGINT        DEFAULT NULL COMMENT '成功时落库的菜品ID',
    review_status      TINYINT       DEFAULT NULL COMMENT '生成当时的审核/发布状态：0-草稿，1-已发布',
    reference_dish_ids VARCHAR(255)  DEFAULT NULL COMMENT '参考菜谱ID（检索增强生成用，逗号分隔）',
    nutrition_source   TINYINT       DEFAULT NULL COMMENT '营养来源：1-AI估算，2-本地计算',
    error_msg          VARCHAR(500)  DEFAULT NULL COMMENT '失败原因',

    create_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task (task_id) COMMENT '一个任务只留一条记录（同一任务重复执行时不会写两条）',
    KEY idx_create (create_time) COMMENT '按时间查最近一批记录',
    KEY idx_model (model, create_time) COMMENT '按模型/提示词版本分组统计'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 生成记录（可复现、可追溯、可评价）';

-- ---------- 核对 ----------
SELECT COUNT(*) AS records FROM t_ai_generation_record;
