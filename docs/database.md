# 数据库表结构说明

> 本文档描述 **表结构**（字段、类型、约束、索引与设计理由），**不包含任何业务数据**。
> 建表语句见 [`sql/dish.sql`](../sql/dish.sql) 与
> [`account-service/src/main/resources/sql/user.sql`](../account-service/src/main/resources/sql/user.sql)。

---

## 一、总览

- **数据库**：`project0914`
- **字符集**：`utf8mb4` / `utf8mb4_general_ci`
- **引擎**：`InnoDB`

| # | 表名 | 中文名 | 归属模块 |
|---|---|---|---|
| 1 | `t_user` | 用户表 | account-service |
| 2 | `t_ingredient` | 食材主数据表 | dish-service |
| 3 | `t_ingredient_candidate` | 食材候选表（表外食材沉淀） | dish-service |
| 4 | `t_dish` | 菜品表（草稿 / 已发布共表） | dish-service |
| 5 | `t_dish_ingredient` | 菜品-食材明细表 | dish-service |
| 6 | `t_dish_rating` | 菜品评分表 | dish-service |
| 7 | `t_dish_comment` | 菜品评论表 | dish-service |
| 8 | `t_dish_collect` | 菜品收藏表 | dish-service |
| 9 | `t_ai_generate_task` | AI 生成任务表 | dish-service |
| 10 | `t_tag` | 标签表 | dish-service |
| 11 | `t_dish_tag` | 菜品-标签关联表 | dish-service |
| 12 | `t_user_ingredient_pref` | 用户食材偏好画像 | dish-service |
| 13 | `t_ai_generation_record` | AI 生成记录（可复现 / 可评价） | dish-service |
| 14 | `t_es_sync_task` | ES 索引同步待办（可重试 / 可补偿） | dish-service |

> 13、14 是改进阶段新增的表，见第十节；`t_user.role`、`t_ingredient_candidate` 的审核三列、
> `t_dish.nutrition_source` / `nutrition_verified` 同样是这批迁移加上去的。

> **关于制作步骤**：不单独建表，存在 `t_dish.step_json` 里。
> 判断依据是**是否需要参与召回或计算** —— 步骤和食材不同，它只做展示，
> 不会成为查询条件，为它单独开表反而增加一次 JOIN 的成本。

### 通用约定

| 约定 | 做法 | 原因 |
|---|---|---|
| 主键 | 雪花 ID（应用层 `IdType.ASSIGN_ID`） | 分布式友好，不依赖数据库自增；且天然带时间序 |
| 逻辑删除 | `deleted` 字段（`@TableLogic`） | 保留行为痕迹，避免物理删除引发的外键悬空 |
| 时间字段 | `create_time` / `update_time`，由 `MetaObjectHandler` 自动填充 | DDL 侧留 `DEFAULT CURRENT_TIMESTAMP` 兜底 |
| 金额 / 营养 | `DECIMAL`，不用 `FLOAT` / `DOUBLE` | 避免浮点误差影响聚合与比对 |
| ID 对外 | 全部 `Long` → String 序列化 | 雪花 ID 19 位超 JS `Number` 精度 |

---

## 二、账号域

### `t_user` — 用户表

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键（雪花 ID，应用层生成） |
| `username` | `VARCHAR(50)` | NOT NULL, UNIQUE | 登录用户名 |
| `password` | `VARCHAR(100)` | NOT NULL | BCrypt 加密后密码（固定 60 字符） |
| `nickname` | `VARCHAR(50)` | NULL | 昵称 |
| `phone` | `VARCHAR(20)` | NULL | 手机号 |
| `status` | `TINYINT` | NOT NULL, DEFAULT 1 | 账号状态：0-禁用，1-正常 |
| `deleted` | `TINYINT` | NOT NULL, DEFAULT 0 | 逻辑删除 |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |
| `update_time` | `DATETIME` | NOT NULL | 更新时间 |

**索引**

| 名称 | 字段 | 用途 |
|---|---|---|
| `PRIMARY` | `id` | — |
| `uk_username` | `username` | **并发下防重复注册的最终保障** |

> **为什么唯一索引不能省**：Service 里的「先查后插」在并发场景下仍可能同时通过，
> 必须靠数据库唯一索引兜底。这是防重的最后一道防线，不是可选优化。

---

## 三、食材域

### `t_ingredient` — 食材主数据表

整套系统的地基。没有它，倒排召回和营养计算都无从谈起。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `name` | `VARCHAR(50)` | NOT NULL, UNIQUE | 标准名（如「番茄」） |
| `alias` | `VARCHAR(200)` | NULL | 别名，逗号分隔（「西红柿,tomato」），用于 AI 输出归一化 |
| `category` | `VARCHAR(20)` | NOT NULL | 分类：蔬菜/肉类/水产/蛋奶/豆制品/主食/菌菇/水果/调料 |
| `unit` | `VARCHAR(10)` | NOT NULL, DEFAULT 'g' | 标准计量单位：g / ml / 个 |
| `unit_gram` | `DECIMAL(10,2)` | NULL | 单个折算克数（如鸡蛋 1 个≈50g）；`unit=g` 时置 1 |
| `calorie` | `DECIMAL(8,2)` | NOT NULL, DEFAULT 0 | 每 100g 热量（kcal） |
| `protein` | `DECIMAL(8,2)` | NOT NULL, DEFAULT 0 | 每 100g 蛋白质（g） |
| `fat` | `DECIMAL(8,2)` | NOT NULL, DEFAULT 0 | 每 100g 脂肪（g） |
| `carb` | `DECIMAL(8,2)` | NOT NULL, DEFAULT 0 | 每 100g 碳水化合物（g） |
| `fiber` | `DECIMAL(8,2)` | NOT NULL, DEFAULT 0 | 每 100g 膳食纤维（g） |
| `sodium` | `DECIMAL(10,2)` | NOT NULL, DEFAULT 0 | 每 100g 钠（mg） |
| `is_common` | `TINYINT` | NOT NULL, DEFAULT 0 | 是否常用（前端首屏展示） |
| `sort_no` | `INT` | NOT NULL, DEFAULT 0 | 排序 |
| `status` | `TINYINT` | NOT NULL, DEFAULT 1 | 0-停用，1-启用 |
| `deleted` | `TINYINT` | NOT NULL, DEFAULT 0 | 逻辑删除 |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |
| `update_time` | `DATETIME` | NOT NULL | 更新时间 |

**索引**

| 名称 | 字段 | 用途 |
|---|---|---|
| `uk_name` | `name` | 标准名唯一 |
| `idx_category` | `(category, status)` | 按分类筛选 |
| `idx_common` | `(is_common, sort_no)` | 首屏常用食材 |

> **为什么食材必须是主数据**：字符串形式的食材名既做不了倒排召回，也算不出营养。
> 前端只传 `ingredient_id`。

---

### `t_ingredient_candidate` — 食材候选表

用户自由输入、主数据里暂时没有的食材（如「松露」）。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `name` | `VARCHAR(50)` | NOT NULL | 食材名（用户输入原文） |
| `name_key` | `VARCHAR(50)` | NOT NULL, UNIQUE | 归一化键（去空格小写），计数去重用 |
| `use_count` | `INT` | NOT NULL, DEFAULT 1 | 被使用次数（每出现一次 +1） |
| `status` | `TINYINT` | NOT NULL, DEFAULT 0 | 0-候选，1-已转正，2-已拒绝 |
| `ingredient_id` | `BIGINT` | NULL | 转正后对应的 `t_ingredient.id` |
| `first_seen` | `DATETIME` | NOT NULL | 首次出现时间 |
| `last_seen` | `DATETIME` | NOT NULL | 最近出现时间 |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |
| `update_time` | `DATETIME` | NOT NULL | 更新时间 |

**索引**

| 名称 | 字段 | 用途 |
|---|---|---|
| `uk_name_key` | `name_key` | 同一个名字只留一条，计数往上加 |
| `idx_promote` | `(status, use_count)` | 转正扫描：在候选里找够次数的 |

**转正流程**：累计出现 ≥ 3 次 → 写 `t_ingredient` + 回填历史菜品明细的 `ingredient_id`
\+ 标记候选已转正 + **重写受影响菜的索引**。

> **为什么不是「直接收进主数据」**：那样垃圾输入（甚至脏话）会污染主数据，
> 而主数据是所有归一化与营养计算的基准。用「先计数、够了再转正」做一道缓冲，
> 让真实需求浮上来，噪声自己沉下去。

---

## 四、菜品域

### `t_dish` — 菜品表（草稿与已发布共表）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `name` | `VARCHAR(80)` | NOT NULL | 菜品名 |
| `name_key` | `VARCHAR(80)` | NOT NULL | 菜名归一化键（去空格、去修饰词、同义替换），用于重复检测 |
| `cover_url` | `VARCHAR(255)` | NULL | 封面图地址 |
| `source` | `TINYINT` | NOT NULL, DEFAULT 2 | 1-官方精选，2-AI 生成，3-用户自定义 |
| `creator_id` | `BIGINT` | NULL | 创建者用户 ID（官方精选为 NULL） |
| `status` | `TINYINT` | NOT NULL, DEFAULT 0 | **0-草稿**（仅创建者可见），1-已发布，2-审核拒绝，3-已下架 |
| `base_serving` | `INT` | NOT NULL, DEFAULT 2 | 基准份量（人份），明细用量以此为准 |
| `taste_tag` | `VARCHAR(100)` | NULL | 口味标签冗余（展示用，如「微辣,咸香」） |
| `difficulty` | `TINYINT` | NOT NULL, DEFAULT 1 | 难度 1-简单 / 2-中等 / 3-较难（与提示词 1~3 对齐） |
| `prep_time_min` | `INT` | NOT NULL, DEFAULT 0 | 备菜时间（分钟） |
| `cook_time_min` | `INT` | NOT NULL, DEFAULT 0 | 下锅时间（分钟） |
| `spicy_level` | `TINYINT` | NOT NULL, DEFAULT 0 | 辣度：0-不辣，1-微辣，2-中辣，3-很辣 |
| `summary` | `VARCHAR(80)` | NULL | 一句话简介（详情页副标题） |
| `step_json` | `JSON` | NULL | 做法步骤 `[{"no":1,"text":"..."}]` |
| `seasonings` | `JSON` | NULL | 调料名列表（**只存名字，用量由系统按标准配方补**） |
| `equipment` | `JSON` | NULL | 所需厨具 |
| `chef_tips` | `JSON` | NULL | 大厨建议（易翻车的点 / 改进技巧） |
| `substitutes` | `JSON` | NULL | 替代食材建议 |
| `energy_kcal` | `DECIMAL(10,2)` | NOT NULL, DEFAULT 0 | 每份热量（kcal） |
| `protein_g` | `DECIMAL(10,2)` | NOT NULL, DEFAULT 0 | 每份蛋白质（g） |
| `fat_g` | `DECIMAL(10,2)` | NOT NULL, DEFAULT 0 | 每份脂肪（g） |
| `carb_g` | `DECIMAL(10,2)` | NOT NULL, DEFAULT 0 | 每份碳水（g） |
| `fiber_g` | `DECIMAL(10,2)` | NOT NULL, DEFAULT 0 | 每份膳食纤维（g） |
| `sodium_mg` | `DECIMAL(10,2)` | NOT NULL, DEFAULT 0 | 每份钠（mg）—— 中式饮食最该看的指标 |
| `nutrition_source` | `TINYINT` | NOT NULL, DEFAULT 0 | 0-没有，1-AI 估算（仅供参考），2-本地精确计算 |
| `nutrition_verified` | `TINYINT` | NOT NULL, DEFAULT 0 | 是否通过本地校验（偏差 < 40%） |
| `rating_avg` | `DECIMAL(3,2)` | NOT NULL, DEFAULT 0 | 平均分（**仅展示，不用于排序**） |
| `rating_count` | `INT` | NOT NULL, DEFAULT 0 | 评分人数 |
| `rating_wilson` | `DECIMAL(5,4)` | NOT NULL, DEFAULT 0 | **威尔逊下界得分（排序用，抗小样本刷分）** |
| `collect_count` | `INT` | NOT NULL, DEFAULT 0 | 收藏数 |
| `view_count` | `INT` | NOT NULL, DEFAULT 0 | 浏览数 |
| `publish_time` | `DATETIME` | NULL | 发布进推荐池的时间 |
| `deleted` | `TINYINT` | NOT NULL, DEFAULT 0 | 逻辑删除 |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |
| `update_time` | `DATETIME` | NOT NULL | 更新时间 |

**索引**

| 名称 | 字段 | 用途 |
|---|---|---|
| `uk_name_creator` | `(name_key, creator_id)` | 同一创建者不重复建同名菜 |
| `idx_pool` | `(status, rating_wilson)` | **推荐池召回主索引** |
| `idx_creator` | `(creator_id, status)` | 我的菜品 / 我的草稿 |
| `idx_name_key` | `(name_key)` | 跨用户重复检测 |

**四条核心设计取舍**

1. **草稿态与已发布态共用一张表**，用 `status` 区分，不开两张表。
   好处：草稿发布无需跨表迁移数据，评分/收藏外键不用动。
   代价：**所有召回查询必须带 `status = 1`**，这个条件不许漏（已写进 Mapper）。
2. **人数不进任何匹配键**。菜品只存 `base_serving`（基准份量）下的用量，
   人数在应用层做换算系数 —— 否则同一道菜会因份量裂成多条记录。
3. **营养值以 AI 估算为主**，前端必须标注「AI 估算，仅供参考」。
   实测同一道番茄炒蛋两次生成分别是 210 / 250 千卡，波动约 20%，这行字不是客套。
   本地计算（`amount_gram × t_ingredient` 每 100g 营养）**保留为后台校验**：
   与 AI 值偏差超过 40% 就说明这次生成可疑，记日志并触发重试。
4. **`creator_id` 为 NULL（官方菜）时 MySQL 唯一索引不生效** ——
   多列唯一索引中任一列为 NULL 时整行不受约束。官方菜去重靠入库校验环节。

---

### `t_dish_ingredient` — 菜品-食材明细表

营养计算的**唯一数据来源**。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `dish_id` | `BIGINT` | NOT NULL | 菜品 ID |
| `ingredient_id` | `BIGINT` | NULL | 食材主数据 ID；落库时先按用户勾选的 ID，再回退 `IngredientDirectory.resolveId()`（正式名/别名/去空白），**真的表外食材才为 NULL**（此时只会被记进候选表等管理员转正）。历史遗留的空值见 `sql/migration-05-ingredient-id-backfill.sql` |
| `ingredient_name` | `VARCHAR(50)` | NOT NULL | 食材名（冗余一份，保证自由输入食材也能正常展示） |
| `amount` | `DECIMAL(10,2)` | NOT NULL | 基准份量下用量 |
| `unit` | `VARCHAR(10)` | NOT NULL, DEFAULT 'g' | 单位（冗余自主数据，允许菜品级覆盖） |
| `amount_gram` | `DECIMAL(10,2)` | NOT NULL | 折算克数（营养计算 + 相似度加权用） |
| `main_flag` | `TINYINT` | NOT NULL, DEFAULT 0 | 1-主料，0-辅料（召回时主料权重更高） |
| `sort_no` | `INT` | NOT NULL, DEFAULT 0 | 展示顺序 |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |

**索引**

| 名称 | 字段 | 用途 |
|---|---|---|
| `uk_dish_ingredient` | `(dish_id, ingredient_name)` | 同一道菜不重复记同种食材 |
| `idx_ingredient` | `(ingredient_id)` | **倒排召回的入口索引**（只覆盖归一成功的食材） |
| `idx_dish` | `(dish_id)` | 详情页按菜品拉明细 |

> **为什么唯一建在 `ingredient_name` 而不是 `ingredient_id`**：
> 后者允许 NULL，而 MySQL 唯一索引对多个 NULL 是不约束的，用它做去重会漏。

---

### `t_dish_step` — 制作步骤表（设计稿，未纳入当前 DDL）

> **当前状态**：步骤以 `t_dish.step_json` 的形式存储，本表为后续扩展预留。
> 只有当步骤需要被单独检索（如「找出所有需要焯水的菜」）或承载配图 / 视频时，
> 才值得拆表。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `dish_id` | `BIGINT` | NOT NULL | 菜品 ID |
| `step_no` | `INT` | NOT NULL | 步骤序号 |
| `content` | `VARCHAR(500)` | NOT NULL | 步骤内容 |
| `image_url` | `VARCHAR(255)` | NULL | 步骤配图（预留） |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |

**索引**：`uk_dish_step (dish_id, step_no)`

---

## 五、互动域

### `t_dish_rating` — 菜品评分表

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `dish_id` | `BIGINT` | NOT NULL | 菜品 ID |
| `user_id` | `BIGINT` | NOT NULL | 用户 ID |
| `score` | `TINYINT` | NOT NULL | 总评 1-5 |
| `taste_score` | `TINYINT` | NULL | 口味分 1-5（可空，后续扩展维度用） |
| `cooked_flag` | `TINYINT` | NOT NULL, DEFAULT 0 | 是否真的做过（打卡） |
| `comment` | `VARCHAR(500)` | NULL | 评价内容 |
| `weight` | `DECIMAL(4,2)` | NOT NULL, DEFAULT 1.00 | 评分权重（新号/异常号降权） |
| `deleted` | `TINYINT` | NOT NULL, DEFAULT 0 | 逻辑删除 |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |
| `update_time` | `DATETIME` | NOT NULL | 更新时间 |

**索引**

| 名称 | 字段 | 用途 |
|---|---|---|
| `uk_dish_user` | `(dish_id, user_id)` | **一人一菜一票，防刷核心** |
| `idx_dish` | `(dish_id, score)` | 聚合平均分与威尔逊下界 |
| `idx_user` | `(user_id, create_time)` | 我的评价列表 |

---

### `t_dish_comment` — 菜品评论表（一级评论）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `dish_id` | `BIGINT` | NOT NULL | 菜品 ID |
| `user_id` | `BIGINT` | NOT NULL | 评论用户 ID |
| `content` | `VARCHAR(500)` | NOT NULL | 评论内容 |
| `rating` | `TINYINT` | NULL | 评论时顺带给的分（可空；**与评分表不冲突，那边才是计分来源**） |
| `like_count` | `INT` | NOT NULL, DEFAULT 0 | 点赞数（第一版只展示） |
| `status` | `TINYINT` | NOT NULL, DEFAULT 1 | 0-待审核，1-正常，2-已隐藏 |
| `deleted` | `TINYINT` | NOT NULL, DEFAULT 0 | 逻辑删除 |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |
| `update_time` | `DATETIME` | NOT NULL | 更新时间 |

**索引**

| 名称 | 字段 | 用途 |
|---|---|---|
| `idx_dish_time` | `(dish_id, status, create_time)` | 详情页按时间倒序拉评论 |
| `idx_user` | `(user_id, create_time)` | 我的评论 |

> **为什么和评分表分开**：评分是「一人一票的数值」（靠唯一键防刷），
> 评论是「可多条的文字」。两类数据的约束和查询方式都不同，
> 硬塞一张表会让唯一键和业务规则互相打架。
> 第一版只做一级评论：不做回复、不做点赞交互（`like_count` 只展示）。

---

### `t_dish_collect` — 菜品收藏表

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `dish_id` | `BIGINT` | NOT NULL | 菜品 ID |
| `user_id` | `BIGINT` | NOT NULL | 用户 ID |
| `deleted` | `TINYINT` | NOT NULL, DEFAULT 0 | 逻辑删除（取消收藏走这里，保留行为痕迹） |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |

**索引**

| 名称 | 字段 | 用途 |
|---|---|---|
| `uk_dish_user` | `(dish_id, user_id)` | 防重复收藏 |
| `idx_user` | `(user_id, create_time)` | 我的收藏列表（倒序） |

> **⚠️ 唯一键 + 逻辑删除会冲突**：取消收藏置 `deleted=1` 后，再收藏时 `INSERT` 会撞
> `uk_dish_user`。正确做法是**恢复式收藏**：
>
> ```sql
> INSERT INTO t_dish_collect (id, dish_id, user_id, deleted) VALUES (?, ?, ?, 0)
> ON DUPLICATE KEY UPDATE deleted = 0, create_time = NOW();
> ```
>
> **计数按明细表重算**，绝不用 affected rows 做加减。

---

## 六、任务与标签域

### `t_ai_generate_task` — AI 生成任务表

三个用途：异步任务进度、单飞合并、日配额统计。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键（即返回前端的 `taskId`） |
| `user_id` | `BIGINT` | NOT NULL | 发起用户 ID |
| `request_signature` | `CHAR(32)` | NOT NULL | 请求参数规范化后的 MD5（食材排序+口味+人数），用于单飞去重与精确缓存 |
| `request_json` | `JSON` | NOT NULL | 原始请求参数快照 |
| `status` | `TINYINT` | NOT NULL, DEFAULT 0 | **0-排队中，1-生成中，2-成功，3-失败，4-已取消，5-已中断**（服务重启导致，需用户主动重试） |
| `result_dish_id` | `BIGINT` | NULL | 成功后落库的草稿菜品 ID |
| `error_msg` | `VARCHAR(500)` | NULL | 失败原因 |
| `cost_ms` | `INT` | NOT NULL, DEFAULT 0 | AI 调用耗时（ms） |
| `queue_wait_ms` | `INT` | NOT NULL, DEFAULT 0 | 排队等待时长（ms）——用来回答「用户到底等了多久」 |
| `deleted` | `TINYINT` | NOT NULL, DEFAULT 0 | 逻辑删除 |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |
| `update_time` | `DATETIME` | NOT NULL | 更新时间 |

**索引**

| 名称 | 字段 | 用途 |
|---|---|---|
| `idx_sig_status` | `(request_signature, status, create_time)` | 单飞：查同签名的活跃任务 |
| `idx_user_quota` | `(user_id, create_time)` | 日配额与频控统计 |

**状态机**：`0 PENDING → 1 RUNNING → 2 SUCCESS / 3 FAILED`，
任意阶段可 `4 CANCELLED`；服务重启时 `0/1` 状态被标记为 `5 INTERRUPTED`。

> **不做自动重新入队**：任务可能已经写了一部分草稿，重放会产生重复数据，
> 让用户自己决定要不要重试更安全。

---

### `t_tag` — 标签表

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `name` | `VARCHAR(30)` | NOT NULL | 标签名（微辣 / 清淡 / 川菜 / 快手菜…） |
| `type` | `TINYINT` | NOT NULL | 1-口味，2-菜系，3-场景，4-人群 |
| `sort_no` | `INT` | NOT NULL, DEFAULT 0 | 排序 |
| `deleted` | `TINYINT` | NOT NULL, DEFAULT 0 | 逻辑删除 |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |

**索引**：`uk_type_name (type, name)`

> 口味不进匹配键，只做**召回过滤 + 排序加权**。

---

### `t_dish_tag` — 菜品-标签关联表

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `dish_id` | `BIGINT` | NOT NULL | 菜品 ID |
| `tag_id` | `BIGINT` | NOT NULL | 标签 ID |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |

**索引**

| 名称 | 字段 | 用途 |
|---|---|---|
| `uk_dish_tag` | `(dish_id, tag_id)` | 防重复关联 |
| `idx_tag` | `(tag_id)` | 按标签召回 |

---

### `t_user_ingredient_pref` — 用户食材偏好画像

「用户常选什么」沉淀成权重，减少重复选择负担，同时给排序加个性化因子。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `BIGINT` | PK | 主键 |
| `user_id` | `BIGINT` | NOT NULL | 用户 ID |
| `ingredient_id` | `BIGINT` | NOT NULL | 食材 ID |
| `weight` | `DECIMAL(6,3)` | NOT NULL, DEFAULT 1.000 | 偏好权重（选择即累加，随时间衰减） |
| `choose_count` | `INT` | NOT NULL, DEFAULT 0 | 被选择次数 |
| `deleted` | `TINYINT` | NOT NULL, DEFAULT 0 | 逻辑删除 |
| `create_time` | `DATETIME` | NOT NULL | 创建时间 |
| `update_time` | `DATETIME` | NOT NULL | 更新时间 |

**索引**：`uk_user_ingredient (user_id, ingredient_id)`

> 状态：**已建表，尚未接入业务**，属于排序个性化（P2）的预留。

---

## 七、关键计算口径

### 威尔逊下界（排序用）

```
p = 加权平均分 / 5，z = 1.96（95% 置信），n = 评分人数
lower = (p + z²/2n - z * sqrt(p(1-p)/n + z²/4n²)) / (1 + z²/n)
```

再乘以 5 还原到 5 分制。**n 越小，lower 被压得越低**，天然抑制「1 人打 5 分」的刷分。

> SQL 侧与 ES 侧的威尔逊口径必须一致，否则两边排序会对不上。

### 直出判定（确定性规则）

```
覆盖率 ≥ 0.8  且  rating_count ≥ 10  且  rating_wilson ≥ 0.84
```

三条同时满足才「命中」，否则走 AI 生成分支。
不满足时带上可解释的 `missReason`，前端据此提示用户。

### 榜单口径

| 类型 | 规则 |
|---|---|
| 热门（hot） | `收藏数 × 3 + 评分人数 × 2` |
| 高分（top） | 按**威尔逊下界**排序（不是平均分） |
| 最新（latest） | 按 `publish_time` 倒序 |

> 榜单只统计 `status = 1` 且 `deleted = 0` 的菜。

### 发布门槛

**食材数 ≥ 2**（数的是「能被搜到的食材总数」，不是「主料 ≥ 2」）——
落库只把 AI 输出的第一个食材标成主料，按主料数会把「土豆 + 胡萝卜」也拦下来。

---

## 八、召回查询参考写法

### 倒排召回（MySQL 降级路径）

```sql
SELECT di.dish_id,
       COUNT(*)              AS hit_cnt,
       SUM(di.main_flag = 1) AS main_hit
FROM t_dish_ingredient di
         JOIN t_dish d ON d.id = di.dish_id
    AND d.status = 1          -- 必须带：草稿不进推荐池
    AND d.deleted = 0
WHERE di.ingredient_id IN (:ids)
GROUP BY di.dish_id
ORDER BY main_hit DESC, hit_cnt DESC
LIMIT 50;
```

### 应用层加权排序

```
similarity = hit_cnt / 用户所选食材数          （召回覆盖率）
score = w1 * similarity
      + w2 * d.rating_wilson                    （抗小样本的评分）
      + w3 * 人数适配度                          （|base_serving - 人数| 越小越高）
      + w4 * 用户偏好匹配度                      （t_user_ingredient_pref）
```

ES 不可用时回落到这条路径，两条路产出的结构必须一致，降级才对前端透明。

---

## 九、ES 侧对应关系

**MySQL 是 source of truth，ES 只是检索副本。**

索引名：`dish_index`，`_id` 直接用雪花 ID（重复写自动覆盖，天然幂等）。

| MySQL 字段 | ES 字段 | 类型 |
|---|---|---|
| `t_dish.id` | `dishId` | `long` |
| `t_dish.name` | `name` | `text` + `keyword` |
| `t_dish.name_key` | `nameKey` | `keyword` |
| `t_dish.status` | `status` | `byte` |
| `t_dish.deleted` | `deleted` | `byte` |
| `t_dish_ingredient.ingredient_id` | `ingredientIds` | `long[]` |
| `t_dish_ingredient.main_flag=1` | `mainIngredientIds` | `long[]` |
| `t_dish.rating_wilson` | `ratingWilson` | `double` |
| `t_dish.publish_time` | `publishTime` | `date` |

**同步时必须带上的字段**（漏一个就出事）：

| 字段 | 漏了会怎样 |
|---|---|
| `status` | **草稿泄漏到推荐结果里**，用户看到别人没发布的菜 |
| `deleted` | 已删除的菜还能被搜出来 |
| `ratingWilson` | 排序退化，小样本刷分菜顶到第一 |
| `ingredientIds` | 召回直接失效 |

详见 [`es/dish-search.md`](../es/dish-search.md)。

---

## 十、改进新增的表与字段（`sql/migration-01` ~ `06`）

改进阶段的库变更全部走**可重复执行的迁移脚本**（MySQL 8 不支持
`ADD COLUMN IF NOT EXISTS`，所以脚本先查 `information_schema` 再动态拼 DDL），
每支脚本末尾都带一条核对 SQL。

| 脚本 | 改了什么 | 为什么必须有 |
|---|---|---|
| `sql/migration-01-admin-role.sql` | `t_user.role TINYINT NOT NULL DEFAULT 0`（0 普通 / 1 管理员） | 原来只有 `status`（禁用/正常），任何登录用户都能调 `/ai/es/reindex` 重建全量索引、把候选食材批量转正 |
| `sql/migration-02-candidate-review.sql` | `t_ingredient_candidate` 加 `reviewed_by` / `review_time` / `review_reason` | 「出现次数 ≥ 3 就自动转正」不等于名字有效：同一个错别字被几个人敲一样能刷到 3 次，然后污染归一化与营养基准。次数现在只用来**排待办顺序**，转正必须由管理员逐条判定并留痕 |
| `sql/migration-03-generation-record.sql` | 新表 `t_ai_generation_record` | 换个模型/改句提示词之后，老结果要还能解释得通：记录 `model` / `prompt_version`（系统提示词 MD5 前 8 位）/ `temperature` / `max_attempts` / `attempts` / `cost_ms` / `format_ok` / `constraint_ok` / `validation_errors` / `raw_output`（截断） / `reference_dish_ids` / `nutrition_source` |
| `sql/migration-04-es-sync-task.sql` | 新表 `t_es_sync_task` | ES 写入不能只在事务里"顺手做一次"：失败要有退避重试与人工补偿入口 |
| `sql/migration-05-ingredient-id-backfill.sql` | 纯数据回填（不改表结构）：把"名字其实命中主数据、`ingredient_id` 却是空"的明细行补上 ID | `DishDraftWriter` 原来只在**用户勾选的列表**里按名字找 ID，于是 AI 自己补的食材、以及用户手敲的食材一律以 `ingredient_id = NULL` 落库：进不了按 ID 的召回、营养查不到成分表（整道菜被标"AI 估算"）、还会被误记进候选表。应用侧已改为回退 `IngredientDirectory.resolveId()`，这支脚本只补**已经落库**的历史行（开发库实测 6 行 → 0 行）。只补"唯一能对上"的：正式名精确相同，或别名命中且该别名只属于一条主数据；表外食材（紫米/迷迭香/高脚鸟蛋/食用油…）不会被误补。执行后要跑 `POST /api/dish/ai/es/reindex` 与 `POST /api/dish/ai/admin/nutrition/recompute` |
| `sql/migration-06-recommend-push-log.sql` | 补建 `t_dish_push_log`（`CREATE TABLE IF NOT EXISTS`，幂等） | 冷启动的推流日志表当初是手工建的、没落成脚本，随数据恢复一起丢了；而推荐候选查询里有对它的 `NOT EXISTS` 24 小时去重子查询 → 推荐页整页 `500 服务器开小差了`（`Table 'project0914.t_dish_push_log' doesn't exist`）。`t_dish` 的冷启动字段与 `idx_rank_level_score` 都在，只缺这张表。**教训：新表必须进 `sql/`**。 |

### `t_ai_generation_record` — AI 生成记录

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` PK | 雪花 ID |
| `task_id` | `BIGINT` | 对应 `t_ai_generate_task.id`，**`UNIQUE KEY uk_task`** |
| `user_id` | `BIGINT` | 发起用户 |
| `status` | `TINYINT` | 终态：2 成功 / 3 失败 / 4 用户取消（生成其实已完成，只是结果被丢弃） |
| `format_ok` / `constraint_ok` | `TINYINT` | **分两档**记录：格式不合格是"模型没按格式说"，约束不合格是"模型没听用户的话"，改进方向完全不同，合成一个"失败率"就没法用 |
| `raw_output` | `MEDIUMTEXT` | 最后一次的原始输出（截断 20000 字符） |
| `reference_dish_ids` | `VARCHAR(255)` | 检索增强生成时注入了哪几道参考菜（逗号分隔） |
| `nutrition_source` | `TINYINT` | 1 AI 估算 / 2 本地计算 |
| 索引 | | `idx_create(create_time)`、`idx_model(model, create_time)` |
| 约束 | | `UNIQUE KEY uk_task(task_id)`：一个任务只留一条，重复执行不会写两条 |

### `t_es_sync_task` — 索引同步待办

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` PK | 雪花 ID |
| `dish_id` | `BIGINT` | **`UNIQUE KEY uk_dish`**：一道菜只有一条待办，反复变更只刷新它，不堆积 |
| `version` | `DATETIME` | 触发时的 `update_time`，用于追溯是哪次变更引发的 |
| `status` | `TINYINT` | 0 待同步 / 1 已完成 / 2 失败待重试 / 3 已放弃（需人工介入） |
| `attempts` | `INT` | 已尝试次数（**成功时也要 +1**，否则一次成功会显示"尝试 0 次"） |
| `next_retry_at` | `DATETIME` | 退避用：失败后按 5 / 15 / 45 / 120 秒推后；5 次用尽转"已放弃" |
| `last_error` | `VARCHAR(500)` | 最后一次失败原因（管理员界面上直接看得到） |
| 索引 | | `idx_due(status, next_retry_at)`：后台任务按它扫到点的待办 |

> **幂等性来自"任务不表达写还是删"**：待办里没有"这次要写入还是删除"这种意图，
> 同步器每次按菜品**当前**状态决定动作 —— 查不到或非已发布就 delete。
> 所以撤回后遗留的旧任务执行时只会删，不会把已撤回的菜重新塞回索引。

### `t_dish_push_log` — 推荐流推流日志

它只回答一个问题：**这道菜最近推给过这个用户吗？** 用来做 24 小时去重 ——
同一个人短时间反复刷到同一道菜，会让推荐流看起来像坏了，也会让 `push_count` 虚高。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` PK | 雪花 ID |
| `dish_id` | `BIGINT` | 菜品 ID |
| `user_id` | `BIGINT` | 被推给的用户 |
| `push_time` | `DATETIME` | **最近一次**推给该用户的时间（默认 `CURRENT_TIMESTAMP`） |
| 索引 | | `UNIQUE KEY uk_dish_user(dish_id, user_id)`：一人一菜只留一行，重复推流走 `INSERT … ON DUPLICATE KEY UPDATE push_time = VALUES(push_time)` 刷新时间，而不是每次插一条（后者会让表随刷新次数成倍膨胀）<br>`KEY idx_user_time(user_id, push_time)`：去重子查询按它走 |

**24 小时这个窗口是查询条件，不是唯一键**：唯一键负责"只留一行"，
时间窗口负责"多久之后可以再推"。表大小 ≈ 用户数 × 菜数，可控。

推荐候选里的实际写法（`RecommendMapper`）：

```sql
AND NOT EXISTS (
  SELECT 1 FROM t_dish_push_log l
  WHERE l.user_id = #{userId} AND l.dish_id = d.id
    AND l.push_time > NOW() - INTERVAL 24 HOUR
)
```

> 建表脚本 `sql/migration-06-recommend-push-log.sql`。这张表曾经**只存在于
> 某个手工执行的会话里**（`docs/cold-start-design.md:63` 有 DDL 但没有脚本），
> 换库恢复数据后消失，于是推荐接口直接 500 —— 缺一张表不是"少个功能"，
> 是把整条推荐流打挂。
