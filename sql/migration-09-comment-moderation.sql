-- ============================================================
-- 评论违规词拦截 + 用户举报 + 管理员审核
--
-- 执行方式（在项目根目录）：
--   mysql -uroot -proot --default-character-set=utf8mb4 project0914 \
--     -e "source sql/migration-09-comment-moderation.sql"
--
--   词库批量导入（可选，先 --dry-run 看统计再 --apply）：
--   python sql/import-sensitive-lexicon.py --dir "D:\explorer\NeatDM\Sensitive-lexicon-main\Sensitive-lexicon-main\Vocabulary" --dry-run
--
-- 【为什么要有这一层】
--   评论是唯一一处「用户自由输入、又直接展示给所有人」的入口。
--   只做长度校验等于什么都不做：广告导流、辱骂会在几分钟内铺满详情页。
--   但也不能一发现违禁词就整条拒绝 —— 用户写了 200 字经验，因为一个词
--   被整体丢弃，体验比屏蔽那个词差得多。
--
--   所以分成两级：
--     level=1 轻度（广告词、脏话）→ 命中的词就地替换成等长星号，评论照常发布
--     level=2 重度（色情、涉枪涉爆、违法网址）→ 整条转「待审核」，不进详情页
--
-- 【幂等性】
--   可重复执行：建表用 IF NOT EXISTS，加列用 information_schema 判断后动态拼 DDL。
--   种子词只在表为空时插入，重复执行不会把管理员删掉的词又塞回去。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 违规词库
--
--    category 用字符串而不是数字：类别是会被运营扩展的（今天 5 类，明年可能 8 类），
--    数字枚举每次加一类都要改注释、改前端映射、还要记住 3 到底是哪个。
--    VARCHAR('AD') 自解释，且和导出/导入脚本的字段对得上。
--
--    source 记录来源（manual / lexicon:色情词库.txt）：
--    批量导入必然混进误伤词，出问题时得能「按来源批量停用」，
--    否则只能一条条翻。
--
--    【坑】故意不加 UNIQUE KEY(word)：本表用逻辑删除（deleted），
--    MySQL 的唯一索引会把已删除的行也算进去 —— 删掉「加微信」之后再想
--    加回来就会撞唯一键。查重放在 Service 层（只查 deleted=0），
--    和 t_dish_collect 踩过的坑是同一类。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sensitive_word
(
    id          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    word        VARCHAR(255) NOT NULL COMMENT '违规词（入库前统一去空白、英文小写）',
    category    VARCHAR(16)  NOT NULL DEFAULT 'OTHER' COMMENT '类别：AD-广告导流，PORN-色情低俗，VIOLENCE-暴恐，ILLEGAL-涉枪涉爆等违法，ABUSE-辱骂攻击，OTHER-其他',
    level       TINYINT      NOT NULL DEFAULT 1 COMMENT '处置级别：1-替换为星号，2-整条拦截待审',
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：0-停用（保留历史命中记录），1-启用',
    source      VARCHAR(64)  DEFAULT 'manual' COMMENT '来源：manual-人工录入，lexicon:<文件名>-批量导入',
    remark      VARCHAR(255) DEFAULT NULL COMMENT '备注：为什么收录这个词',
    hit_count   INT          NOT NULL DEFAULT 0 COMMENT '累计命中次数，用于把高频词排前面',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_enabled_lookup (deleted, enabled) COMMENT '启动/刷新时一次性捞全量启用词',
    KEY idx_category (category, deleted) COMMENT '按类别筛选/按来源批量停用',
    KEY idx_word_prefix (word) COMMENT '管理页按词前缀检索'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评论违规词库';

-- ------------------------------------------------------------
-- 2. 评论举报
--    冗余存 dish_id：审核列表要显示「哪道菜的评论」，否则每条都要回查评论表。
--    【为什么不加唯一键(comment_id, reporter_id)】同样是逻辑删除的坑；
--    而且「举报过还能不能再举报」本身是业务规则（现在是拒绝，未来可能改成合并计数），
--    写在索引里就改不动了。查重同样放 Service 层。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_comment_report
(
    id            BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    comment_id    BIGINT       NOT NULL COMMENT '被举报的评论ID',
    dish_id       BIGINT       NOT NULL COMMENT '评论所属菜品ID（冗余，便于审核列表展示与跳转）',
    reporter_id   BIGINT       NOT NULL COMMENT '举报人用户ID',
    reason_type   TINYINT      NOT NULL DEFAULT 0 COMMENT '理由：0-其他，1-广告导流，2-辱骂攻击，3-色情低俗，4-违法违规',
    reason        VARCHAR(200) DEFAULT NULL COMMENT '补充说明',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-待处理，1-已受理（评论下架），2-已驳回',
    handler_id    BIGINT       DEFAULT NULL COMMENT '处理人（管理员）用户ID',
    handle_remark VARCHAR(200) DEFAULT NULL COMMENT '处理说明',
    handle_time   DATETIME     DEFAULT NULL COMMENT '处理时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_status_time (status, deleted, create_time) COMMENT '审核列表：按状态取、按时间倒序',
    KEY idx_comment (comment_id, status) COMMENT '处理时连带关闭同评论的其他举报',
    KEY idx_reporter (reporter_id, create_time) COMMENT '防重复举报'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评论举报（用户提交，管理员处理）';

-- ------------------------------------------------------------
-- 3. 评论表补三列
--    hit_words   ：命中的词，逗号分隔。用户看到的只有星号，管理员要知道命中了什么，
--                  否则「为什么这条被屏蔽」永远说不清。
--    report_count：被举报次数。达到阈值自动转待审，把人力集中在争议内容上。
--    audit_remark：管理员处置说明（下架/放行的依据），复盘时不用靠回忆。
-- ------------------------------------------------------------
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_dish_comment'
      AND COLUMN_NAME = 'hit_words'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE t_dish_comment ADD COLUMN hit_words VARCHAR(255) DEFAULT NULL COMMENT ''命中的违规词（逗号分隔，仅管理员可见）'' AFTER status',
    'SELECT ''hit_words 列已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_dish_comment'
      AND COLUMN_NAME = 'report_count'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE t_dish_comment ADD COLUMN report_count INT NOT NULL DEFAULT 0 COMMENT ''被举报次数（达阈值自动转待审核）'' AFTER hit_words',
    'SELECT ''report_count 列已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_dish_comment'
      AND COLUMN_NAME = 'audit_remark'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE t_dish_comment ADD COLUMN audit_remark VARCHAR(200) DEFAULT NULL COMMENT ''管理员处置说明'' AFTER report_count',
    'SELECT ''audit_remark 列已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 待审评论列表按状态翻页：status=0 的那批会越攒越多，需要一个索引
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_dish_comment'
      AND INDEX_NAME = 'idx_status_time'
);
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE t_dish_comment ADD KEY idx_status_time (status, deleted, create_time)',
    'SELECT ''idx_status_time 已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4. 种子词（只在库为空时插入）
--
--    这里只放「人手确认、语义无歧义」的核心词。批量词库走导入脚本，
--    原因是开源词库掺了大量正常词（广告类型.txt 里就有「网络」「招聘」「淘宝」，
--    色情词库里有「刺激」「写真」），直接全量导入会把正常评论刷成星号。
--    词库治理的正确顺序是：种子词保底 → 批量导入候选 → 管理页按命中量剔除误伤。
-- ------------------------------------------------------------
INSERT INTO t_sensitive_word (id, word, category, level, enabled, source, remark, create_time, update_time)
SELECT * FROM (
    SELECT 1900000000000000001 AS id, '加微信'    AS word, 'AD'      AS category, 1 AS level, 1 AS enabled, 'manual' AS source, '广告导流：把用户往站外拉' AS remark, NOW() AS ct, NOW() AS ut UNION ALL
    SELECT 1900000000000000002, '加qq',        'AD',      1, 1, 'manual', '广告导流'                      , NOW(), NOW() UNION ALL
    SELECT 1900000000000000003, '私聊我',      'AD',      1, 1, 'manual', '广告导流'                      , NOW(), NOW() UNION ALL
    SELECT 1900000000000000004, '点击链接',    'AD',      1, 1, 'manual', '广告导流'                      , NOW(), NOW() UNION ALL
    SELECT 1900000000000000005, '扫码领',      'AD',      1, 1, 'manual', '广告导流'                      , NOW(), NOW() UNION ALL
    SELECT 1900000000000000006, '代购',        'AD',      1, 1, 'manual', '广告导流'                      , NOW(), NOW() UNION ALL
    SELECT 1900000000000000007, '刷单',        'AD',      1, 1, 'manual', '广告导流'                      , NOW(), NOW() UNION ALL
    SELECT 1900000000000000008, '骗子',        'ABUSE',   1, 1, 'manual', '辱骂攻击：对人不对事'          , NOW(), NOW() UNION ALL
    SELECT 1900000000000000009, '垃圾',        'ABUSE',   1, 1, 'manual', '辱骂攻击'                      , NOW(), NOW() UNION ALL
    SELECT 1900000000000000010, '滚蛋',        'ABUSE',   1, 1, 'manual', '辱骂攻击'                      , NOW(), NOW() UNION ALL
    SELECT 1900000000000000011, '操你',        'ABUSE',   1, 1, 'manual', '辱骂攻击'                      , NOW(), NOW() UNION ALL
    SELECT 1900000000000000012, '赌博',        'ILLEGAL', 2, 1, 'manual', '违法内容：整条拦截'            , NOW(), NOW() UNION ALL
    SELECT 1900000000000000013, '博彩',        'ILLEGAL', 2, 1, 'manual', '违法内容：整条拦截'            , NOW(), NOW() UNION ALL
    SELECT 1900000000000000014, '办证',        'ILLEGAL', 2, 1, 'manual', '违法内容：整条拦截'            , NOW(), NOW() UNION ALL
    SELECT 1900000000000000015, '开发票',      'ILLEGAL', 2, 1, 'manual', '违法内容：整条拦截'            , NOW(), NOW() UNION ALL
    SELECT 1900000000000000016, '摇头丸',      'ILLEGAL', 2, 1, 'manual', '违法内容：整条拦截'            , NOW(), NOW()
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM t_sensitive_word);

-- ------------------------------------------------------------
-- 5. 核对
-- ------------------------------------------------------------
SELECT category, level, COUNT(*) AS cnt
FROM t_sensitive_word
WHERE deleted = 0
GROUP BY category, level
ORDER BY level, category;

SELECT COUNT(*) AS comment_reports_total FROM t_comment_report;
