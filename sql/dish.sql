-- ============================================================
-- AI 灵感厨房 —— 菜品域数据模型
-- ============================================================
-- 归属：未来的 dish-service（模块尚未创建，先以设计稿形式落库）
-- 依赖：t_user（account-service，见 account-service/src/main/resources/sql/user.sql）
-- 约定：主键雪花 ID（应用层 IdType.ASSIGN_ID）、逻辑删除 deleted、
--       时间字段由 MetaObjectHandler 自动填充（DDL 侧留 DEFAULT 兜底）
-- 字符集：utf8mb4 / utf8mb4_general_ci（与 t_user 保持一致）
--
-- 【四条核心设计取舍，改之前先读这里】
-- 1. 食材必须是主数据（有 ID、标准单位、营养值），前端只传 ingredient_id。
--    字符串形式的食材名既做不了倒排召回，也算不出营养。
-- 2. 草稿态与已发布态共用 t_dish，用 status 区分，不开两张表。
--    好处：草稿发布无需跨表迁移数据，评分/收藏外键不用动。
--    代价：所有召回查询必须带 status = 1，这个条件不许漏（写进 Mapper）。
-- 3. 人数不进任何匹配键。菜品只存 base_serving（基准份量）下的用量，
--    人数在应用层做换算系数。否则同一道菜会因份量裂成多条记录。
-- 4. 营养值以 **AI 估算** 为主（用户决策，见 docs/ai-kitchen.md 3.2），
--    前端必须标注「AI 估算，仅供参考」—— 实测同一道番茄炒蛋两次生成
--    分别是 210 / 250 千卡，波动约 20%，这行字不是客套。
--    本地计算（amount_gram × t_ingredient 每 100g 营养）**保留为后台校验**：
--    与 AI 值偏差超过 40% 就说明这次生成可疑，记日志并触发重试。
--    来源记在 nutrition_source 字段，将来能平滑切成"精确计算"。
-- ============================================================

USE project0914;

-- ------------------------------------------------------------
-- 1. 食材主数据表
--    整套系统的地基。没有它，倒排召回和营养计算都无从谈起。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_ingredient;

CREATE TABLE t_ingredient
(
    id          BIGINT         NOT NULL COMMENT '主键（雪花ID）',
    name        VARCHAR(50)    NOT NULL COMMENT '标准名（唯一，如"番茄"）',
    alias       VARCHAR(200)   DEFAULT NULL COMMENT '别名，逗号分隔（"西红柿,tomato"），用于 AI 输出归一化',
    category    VARCHAR(20)    NOT NULL COMMENT '分类：蔬菜/肉类/水产/蛋奶/豆制品/主食/菌菇/水果/调料',
    unit        VARCHAR(10)    NOT NULL DEFAULT 'g' COMMENT '标准计量单位：g/ml/个',
    unit_gram   DECIMAL(10, 2) DEFAULT NULL COMMENT '单个折算克数（如鸡蛋 1 个≈50g）；unit=g 时置 1',
    calorie     DECIMAL(8, 2)  NOT NULL DEFAULT 0 COMMENT '每 100g 热量（kcal）',
    protein     DECIMAL(8, 2)  NOT NULL DEFAULT 0 COMMENT '每 100g 蛋白质（g）',
    fat         DECIMAL(8, 2)  NOT NULL DEFAULT 0 COMMENT '每 100g 脂肪（g）',
    carb        DECIMAL(8, 2)  NOT NULL DEFAULT 0 COMMENT '每 100g 碳水化合物（g）',
    fiber       DECIMAL(8, 2)  NOT NULL DEFAULT 0 COMMENT '每 100g 膳食纤维（g）',
    sodium      DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '每 100g 钠（mg）',
    is_common   TINYINT        NOT NULL DEFAULT 0 COMMENT '是否常用：0-否，1-是（前端首屏展示）',
    sort_no     INT            NOT NULL DEFAULT 0 COMMENT '排序',
    status      TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：0-停用，1-启用',
    deleted     TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    KEY idx_category (category, status) COMMENT '按分类筛选',
    KEY idx_common (is_common, sort_no) COMMENT '首屏常用食材'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='食材主数据表';


-- ------------------------------------------------------------
-- 2. 菜品表（草稿态 + 已发布态共表）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_dish;

CREATE TABLE t_dish
(
    id                 BIGINT         NOT NULL COMMENT '主键（雪花ID）',
    name               VARCHAR(80)    NOT NULL COMMENT '菜品名',
    name_key           VARCHAR(80)    NOT NULL COMMENT '菜名归一化键（去空格、去修饰词、同义替换），用于重复检测',
    cover_url          VARCHAR(255)   DEFAULT NULL COMMENT '封面图地址',
    source             TINYINT        NOT NULL DEFAULT 2 COMMENT '来源：1-官方精选，2-AI生成，3-用户自定义',
    creator_id         BIGINT         DEFAULT NULL COMMENT '创建者用户ID（官方精选为 NULL）',
    status             TINYINT        NOT NULL DEFAULT 0 COMMENT '状态：0-草稿(仅创建者可见)，1-已发布(进推荐池)，2-审核拒绝，3-已下架',
    base_serving       INT            NOT NULL DEFAULT 2 COMMENT '基准份量（人份），明细用量以此为准',
    taste_tag          VARCHAR(100)   DEFAULT NULL COMMENT '口味标签冗余（展示用，如"微辣,咸香"）',
    difficulty         TINYINT        NOT NULL DEFAULT 1 COMMENT '难度：1-简单，2-中等，3-较难（与 AI 提示词的 1~3 对齐）',
    prep_time_min      INT            NOT NULL DEFAULT 0 COMMENT '备菜时间（分钟）',
    cook_time_min      INT            NOT NULL DEFAULT 0 COMMENT '下锅时间（分钟）',
    spicy_level        TINYINT        NOT NULL DEFAULT 0 COMMENT '辣度：0-不辣，1-微辣，2-中辣，3-很辣',
    summary            VARCHAR(80)    DEFAULT NULL COMMENT '一句话简介（详情页副标题）',
    step_json          JSON           DEFAULT NULL COMMENT '做法步骤：[{"no":1,"text":"..."}]',
    -- 下面四个都是「简单字符串列表」，不值得为它们各开一张表 ——
    -- 步骤和食材要参与召回/营养计算才需要独立表，这些只做展示，JSON 足够。
    seasonings         JSON           DEFAULT NULL COMMENT '调料名列表 ["盐","生抽"]；用量由系统按标准配方补，不存 AI 给的数字',
    equipment          JSON           DEFAULT NULL COMMENT '所需厨具 ["炒锅","锅铲"]',
    chef_tips          JSON           DEFAULT NULL COMMENT '大厨建议 ["易翻车的点","改进技巧"]（原 tip 单条字段升级为列表）',
    substitutes        JSON           DEFAULT NULL COMMENT '替代食材建议 ["没有料酒可以用黄酒代替"]',

    -- 营养值：主力是 AI 估算（用户决策），本地计算降级为后台校验
    energy_kcal        DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '每份热量（kcal）',
    protein_g          DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '每份蛋白质（g）',
    fat_g              DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '每份脂肪（g）',
    carb_g             DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '每份碳水（g）',
    fiber_g            DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '每份膳食纤维（g）',
    sodium_mg          DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '每份钠（mg）—— 中式饮食最该看的指标',
    nutrition_source   TINYINT        NOT NULL DEFAULT 0 COMMENT '营养来源：0-没有，1-AI估算(仅供参考)，2-本地精确计算',
    nutrition_verified TINYINT        NOT NULL DEFAULT 0 COMMENT '是否通过本地校验（与本地算的值偏差<40%）：0-否，1-是',

    -- 评分与热度：冗余字段，评分写入时重算（量大后再改异步）
    rating_avg         DECIMAL(3, 2)  NOT NULL DEFAULT 0 COMMENT '平均分（仅展示，不用于排序）',
    rating_count       INT            NOT NULL DEFAULT 0 COMMENT '评分人数',
    rating_wilson      DECIMAL(5, 4)  NOT NULL DEFAULT 0 COMMENT '威尔逊下界得分（排序用，抗小样本刷分）',
    collect_count      INT            NOT NULL DEFAULT 0 COMMENT '收藏数',
    view_count         INT            NOT NULL DEFAULT 0 COMMENT '浏览数',

    publish_time       DATETIME       DEFAULT NULL COMMENT '发布进推荐池的时间',
    deleted            TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    create_time        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- creator_id 为 NULL（官方菜）时 MySQL 唯一索引不生效，官方菜去重靠入库校验环节
    UNIQUE KEY uk_name_creator (name_key, creator_id) COMMENT '同一创建者不重复建同名菜',
    KEY idx_pool (status, rating_wilson) COMMENT '推荐池召回主索引',
    KEY idx_creator (creator_id, status) COMMENT '我的菜品 / 我的草稿',
    KEY idx_name_key (name_key) COMMENT '跨用户重复检测'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='菜品表（草稿与已发布共表，status 区分）';


-- ------------------------------------------------------------
-- 3. 菜品-食材明细表（含用量，营养计算的唯一数据来源）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_dish_ingredient;

CREATE TABLE t_dish_ingredient
(
    id              BIGINT         NOT NULL COMMENT '主键（雪花ID）',
    dish_id         BIGINT         NOT NULL COMMENT '菜品ID',
    ingredient_id   BIGINT         DEFAULT NULL COMMENT '食材主数据ID；用户自由输入且未归一的食材为 NULL',
    ingredient_name VARCHAR(50)    NOT NULL COMMENT '食材名（冗余一份，保证自由输入、没进主数据的食材也能正常展示）',
    amount          DECIMAL(10, 2) NOT NULL COMMENT '基准份量下用量（配合 unit 理解）',
    unit            VARCHAR(10)    NOT NULL DEFAULT 'g' COMMENT '单位：g/ml/个（冗余自主数据，允许菜品级覆盖）',
    amount_gram     DECIMAL(10, 2) NOT NULL COMMENT '折算克数（营养计算 + 相似度加权用）',
    main_flag       TINYINT        NOT NULL DEFAULT 0 COMMENT '是否主料：1-主料，0-辅料（召回时主料权重更高）',
    sort_no         INT            NOT NULL DEFAULT 0 COMMENT '展示顺序',
    create_time     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    -- 唯一键建在 name 上而不是 ingredient_id：后者允许 NULL，
    -- 而 MySQL 唯一索引对多个 NULL 是不约束的，用它做去重会漏。
    UNIQUE KEY uk_dish_ingredient (dish_id, ingredient_name),
    KEY idx_ingredient (ingredient_id) COMMENT '倒排召回的入口索引（只覆盖归一成功的食材）',
    KEY idx_dish (dish_id) COMMENT '详情页按菜品拉明细'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='菜品-食材明细表';


-- ------------------------------------------------------------
-- 4. 标签表（口味/菜系/场景/人群统一承载）
--    口味不进匹配键，只做召回过滤 + 排序加权
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_tag;

CREATE TABLE t_tag
(
    id          BIGINT      NOT NULL COMMENT '主键（雪花ID）',
    name        VARCHAR(30) NOT NULL COMMENT '标签名（微辣/清淡/川菜/快手菜…）',
    type        TINYINT     NOT NULL COMMENT '类型：1-口味，2-菜系，3-场景，4-人群',
    sort_no     INT         NOT NULL DEFAULT 0 COMMENT '排序',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_name (type, name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='标签表';


DROP TABLE IF EXISTS t_dish_tag;

CREATE TABLE t_dish_tag
(
    id          BIGINT   NOT NULL COMMENT '主键（雪花ID）',
    dish_id     BIGINT   NOT NULL COMMENT '菜品ID',
    tag_id      BIGINT   NOT NULL COMMENT '标签ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dish_tag (dish_id, tag_id),
    KEY idx_tag (tag_id) COMMENT '按标签召回'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='菜品-标签关联表';


-- ------------------------------------------------------------
-- 5. 评分表
--    uk_dish_user 是防刷的地基：一人一菜一票，靠数据库兜底而非应用层判断
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_dish_rating;

CREATE TABLE t_dish_rating
(
    id          BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    dish_id     BIGINT        NOT NULL COMMENT '菜品ID',
    user_id     BIGINT        NOT NULL COMMENT '用户ID',
    score       TINYINT       NOT NULL COMMENT '总评 1-5',
    taste_score TINYINT       DEFAULT NULL COMMENT '口味分 1-5（可空，后续扩展维度用）',
    cooked_flag TINYINT       NOT NULL DEFAULT 0 COMMENT '是否真的做过：0-没做，1-做过(打卡)',
    comment     VARCHAR(500)  DEFAULT NULL COMMENT '评价内容',
    weight      DECIMAL(4, 2) NOT NULL DEFAULT 1.00 COMMENT '评分权重（新号/异常号降权，默认 1.00）',
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dish_user (dish_id, user_id) COMMENT '一人一菜一票，防刷核心',
    KEY idx_dish (dish_id, score) COMMENT '聚合平均分与威尔逊下界',
    KEY idx_user (user_id, create_time) COMMENT '我的评价列表'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='菜品评分表';


-- ------------------------------------------------------------
-- 5.5 评论表
--     为什么和评分表分开：评分是「一人一票的数值」（唯一键防刷），
--     评论是「可多条的文字」，两类数据的约束和查询方式都不同，
--     硬塞一张表会让唯一键和业务规则互相打架。
--     第一版只做一级评论：不做回复、不做点赞交互（like_count 只展示）。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_dish_comment;

CREATE TABLE t_dish_comment
(
    id          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    dish_id     BIGINT       NOT NULL COMMENT '菜品ID',
    user_id     BIGINT       NOT NULL COMMENT '评论用户ID',
    content     VARCHAR(500) NOT NULL COMMENT '评论内容',
    rating      TINYINT      DEFAULT NULL COMMENT '评论时顺带给的分 1-5（可空；与 t_dish_rating 不冲突，那边才是计分来源）',
    like_count  INT          NOT NULL DEFAULT 0 COMMENT '点赞数（第一版只展示）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-待审核，1-正常，2-已隐藏',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_dish_time (dish_id, status, create_time) COMMENT '详情页按时间倒序拉评论',
    KEY idx_user (user_id, create_time) COMMENT '我的评论'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='菜品评论表（一级评论）';


-- ------------------------------------------------------------
-- 6. 收藏表
--    【坑】唯一键 + 逻辑删除会冲突：取消收藏置 deleted=1 后，
--    再收藏时 INSERT 会撞 uk_dish_user。正确做法是「恢复式收藏」：
--    INSERT ... ON DUPLICATE KEY UPDATE deleted = 0, create_time = NOW()
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_dish_collect;

CREATE TABLE t_dish_collect
(
    id          BIGINT   NOT NULL COMMENT '主键（雪花ID）',
    dish_id     BIGINT   NOT NULL COMMENT '菜品ID',
    user_id     BIGINT   NOT NULL COMMENT '用户ID',
    deleted     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除（取消收藏走这里，保留行为痕迹）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dish_user (dish_id, user_id) COMMENT '防重复收藏',
    KEY idx_user (user_id, create_time) COMMENT '我的收藏列表（倒序）'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='菜品收藏表';


-- ------------------------------------------------------------
-- 7. AI 生成任务表
--    三个用途：异步任务进度、单飞合并、日配额统计
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_ai_generate_task;

CREATE TABLE t_ai_generate_task
(
    id                BIGINT       NOT NULL COMMENT '主键（雪花ID，即返回前端的 taskId）',
    user_id           BIGINT       NOT NULL COMMENT '发起用户ID',
    request_signature CHAR(32)     NOT NULL COMMENT '请求参数规范化后的 MD5（食材排序 + 口味 + 人数），用于单飞去重与精确缓存',
    request_json      JSON         NOT NULL COMMENT '原始请求参数快照（食材ID列表、标签ID、人数）',
    status            TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-排队中，1-生成中，2-成功，3-失败，4-已取消，5-已中断（服务重启导致，需用户主动重试）',
    result_dish_id    BIGINT       DEFAULT NULL COMMENT '成功后落库的草稿菜品ID',
    error_msg         VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    cost_ms           INT          NOT NULL DEFAULT 0 COMMENT 'AI 调用耗时（ms）',
    queue_wait_ms     INT          NOT NULL DEFAULT 0 COMMENT '排队等待时长（ms）：用来回答「用户到底等了多久」',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_sig_status (request_signature, status, create_time) COMMENT '单飞：查同签名的活跃任务',
    KEY idx_user_quota (user_id, create_time) COMMENT '日配额与频控统计'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 生成任务表（异步 + 单飞去重 + 配额统计）';


-- ------------------------------------------------------------
-- 8. 用户食材偏好画像（P2，排序加权用）
--    「用户常选什么」沉淀成权重，减少重复选择负担，同时给排序加个性化因子
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_user_ingredient_pref;

CREATE TABLE t_user_ingredient_pref
(
    id            BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    user_id       BIGINT        NOT NULL COMMENT '用户ID',
    ingredient_id BIGINT        NOT NULL COMMENT '食材ID',
    weight        DECIMAL(6, 3) NOT NULL DEFAULT 1.000 COMMENT '偏好权重（选择即累加，随时间衰减）',
    choose_count  INT           NOT NULL DEFAULT 0 COMMENT '被选择次数',
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_ingredient (user_id, ingredient_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户食材偏好画像（P2，排序加权用）';


-- ============================================================
-- 附：召回查询（L2）参考写法
-- ============================================================
-- 【① 倒排召回】找到包含用户所选食材的候选菜品，按命中度排序
--
--   SELECT di.dish_id,
--          COUNT(*)              AS hit_cnt,
--          SUM(di.main_flag = 1) AS main_hit
--   FROM t_dish_ingredient di
--            JOIN t_dish d ON d.id = di.dish_id
--       AND d.status = 1          -- 必须带：草稿不进推荐池
--       AND d.deleted = 0
--   WHERE di.ingredient_id IN (:ids)
--   GROUP BY di.dish_id
--   ORDER BY main_hit DESC, hit_cnt DESC
--   LIMIT 50;
--
-- 【② 加权排序】在应用层算综合分
--   similarity = hit_cnt / 用户所选食材数          （召回覆盖率）
--   score = w1 * similarity
--         + w2 * d.rating_wilson                    （抗小样本的评分）
--         + w3 * 人数适配度                          （|base_serving - 人数| 越小越高）
--         + w4 * 用户偏好匹配度                      （t_user_ingredient_pref）
--
-- 【③ 直出判定】三条同时满足才算「命中」，否则去调 AI
--   similarity      >= 0.8
--   rating_wilson   >= 0.84      （≈ 5 分制的 4.2 分）
--   rating_count    >= 10
--
-- 【威尔逊下界公式】z 取 1.96（95% 置信）
--   p = 加权平均分 / 5
--   lower = (p + z²/2n - z * sqrt(p(1-p)/n + z²/4n²)) / (1 + z²/n)
--   再乘以 5 还原到 5 分制。n 越小，lower 被压得越低，天然抑制 1 人 5 分的刷分。
-- ============================================================


-- ------------------------------------------------------------
-- 9. 食材候选表（用户自由输入、主数据里暂时没有的食材）
--
--    要解决什么：用户能在输入框里敲主数据之外的食材（「松露」）。这些食材没有 ID，
--    只能靠名字参与召回 —— 能用，但不精确（同一样东西的不同叫法会被当成两样）。
--    所以把它们记在这里：出现次数够多就说明是真需求，转正成正式食材
--    （写 t_ingredient + 回填历史菜品的 ingredient_id），之后它们就走 ID 召回。
--
--    【为什么不是「直接收进主数据」】那样垃圾输入（「狗屎」）会污染主数据，
--    而主数据是所有归一化与营养计算的基准。用「先计数、够了再转正」做一道缓冲，
--    让真实需求浮上来，噪声自己沉下去。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_ingredient_candidate;

CREATE TABLE t_ingredient_candidate
(
    id            BIGINT      NOT NULL COMMENT '主键（雪花ID）',
    name          VARCHAR(50) NOT NULL COMMENT '食材名（用户输入时的原文）',
    name_key      VARCHAR(50) NOT NULL COMMENT '归一化键（去空格小写），唯一，用于计数去重',
    use_count     INT         NOT NULL DEFAULT 1 COMMENT '被使用次数（每出现一次 +1）',
    status        TINYINT     NOT NULL DEFAULT 0 COMMENT '状态：0-候选，1-已转正，2-已拒绝（垃圾输入）',
    ingredient_id BIGINT               DEFAULT NULL COMMENT '转正后对应的 t_ingredient.id',
    first_seen    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次出现时间',
    last_seen     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近出现时间',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name_key (name_key) COMMENT '同一个名字只留一条，计数往上加',
    KEY idx_promote (status, use_count) COMMENT '转正扫描：在候选里找够次数的'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='食材候选表（自由输入的待收录食材）';
