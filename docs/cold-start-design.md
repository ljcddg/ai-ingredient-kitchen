# 冷启动「推荐池 + 排行榜」设计方案（修订版）

> 本文是初版方案的修订稿。修订点集中在 §1，算法推导在 §3，其余为可直接落地的实现细节。
> 技术栈：Spring Boot 3.2.4 + MyBatis-Plus 3.5.5 + MySQL 8 + Vue 3。

---

## 1. 相对初版改了什么

| # | 初版问题 | 修订 |
|---|---|---|
| 1 | `avg_score`/`score_count` 与已有 `rating_avg`/`rating_count` 重复 | **复用**已有字段，不新建 |
| 2 | 互动率会 > 1，冲垮评分权重 | 重新设计归一化方式，见 §3 |
| 3 | `view_count=0` 除零，`dish_score` 变 NULL 排序错乱 | 加 `IF`/`NULLIF` 保护 |
| 4 | 「置信度折扣」与「不要威尔逊」自相矛盾 | 改用线性折扣 `LEAST(1, score_count/10)` |
| 5 | 演示时 `push_count` 难到 50 | 阈值进 Nacos + 管理员模拟推流接口 |
| 6 | （新发现）`avg_score`(1~5) 与互动率(0~1) **量纲不一致** | 两项都归一到 0~1 再加权，输出百分制 |
| 7 | `dish_score_log` 表冗余 | **不建**，复用 `t_dish_rating`（已有唯一键防重复评分） |
| 8 | 网关要加路由？ | **不用加**，`/api/dish/**` 已路由到 dish-service |

### 状态机澄清

草稿（`status=0`）的操作只有「公开发布」一条路，**没有私密发布选项**：

```
AI 生成 / 手写  →  草稿 status=0（仅作者可见，不进池、不推流）
                     ↓ 作者点「发布」（唯一出口）
                   已发布 status=1 = 公开 = 进推荐池 rank_level=1
                     ↓ 每日扫描：查看率 ≥ 10% 且 push_count ≥ 50
                   排行榜 rank_level=2
```

数据库注释原本就是这么写的（`t_dish.status`：「1-已发布(**进推荐池**)」，
`publish_time`：「发布**进推荐池**的时间」），本次只是把这句话真正实现出来。

---

## 2. 数据库设计

### 2.1 t_dish 新增字段

```sql
ALTER TABLE t_dish
  ADD COLUMN rank_level TINYINT      NOT NULL DEFAULT 0    COMMENT '0-未进池(草稿/下架)，1-推荐池，2-排行榜' AFTER status,
  ADD COLUMN push_count INT          NOT NULL DEFAULT 0    COMMENT '推流次数（出现在推荐流中+1）' AFTER view_count,
  ADD COLUMN like_count INT          NOT NULL DEFAULT 0    COMMENT '点赞数' AFTER collect_count,
  ADD COLUMN cook_count INT          NOT NULL DEFAULT 0    COMMENT '试做打卡数' AFTER like_count,
  ADD COLUMN dish_score DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '综合得分(百分制)，每日定时计算' AFTER rating_wilson;

ALTER TABLE t_dish ADD KEY idx_rank_level_score (rank_level, dish_score);
```

**没有新建 `avg_score` / `score_count`** —— 直接用已有的 `rating_avg` / `rating_count`。

### 2.2 存量数据迁移（必做，否则改完搜索变空）

已发布的菜不能因为加了 `rank_level` 就消失。存量已发布菜直接视作「已上榜」：

```sql
UPDATE t_dish SET rank_level = 2 WHERE deleted = 0 AND status = 1;
```

### 2.3 推流日志表

```sql
CREATE TABLE t_dish_push_log (
  id        BIGINT   NOT NULL COMMENT '雪花ID',
  dish_id   BIGINT   NOT NULL COMMENT '菜品ID',
  user_id   BIGINT   NOT NULL COMMENT '被推给的用户',
  push_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次推流时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dish_user (dish_id, user_id),
  KEY idx_user_time (user_id, push_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐流推流日志（24小时去重）';
```

**为什么唯一键是 `(dish_id, user_id)` 而不是每次插一条**：
一人一菜只保留一行，重复推流走 `ON DUPLICATE KEY UPDATE push_time=NOW()` 刷新时间。
表大小 = 用户数 × 菜数，可控；若每次插一条，用户刷几次就膨胀几倍。

24 小时去重靠查询条件而非唯一键：

```sql
AND NOT EXISTS (
  SELECT 1 FROM t_dish_push_log l
  WHERE l.user_id = #{userId} AND l.dish_id = d.id
    AND l.push_time > NOW() - INTERVAL 24 HOUR
)
```

### 2.4 评分表：不新建

`t_dish_rating` 已具备全部能力，无需 `dish_score_log`：

| 需求 | 现有实现 |
|---|---|
| 防重复评分 | `uk_dish_user (dish_id, user_id)` 唯一键，靠数据库拒绝 |
| 改分不新增行 | `ON DUPLICATE KEY UPDATE` |
| 维护均分与人数 | `DishInteractionService#submitRating` 已同步更新 `rating_avg`/`rating_count` |
| 试做打卡 | 已有 `cooked_flag` 字段（一人一票） |

`t_dish.cook_count` 是**总人次**（可多人），与 `cooked_flag`（一人一票）互补，两者都要。

---

## 3. 得分算法（核心修订）

### 3.1 初版的两个问题

初版：

```
互动率    = (like_count + collect_count*2 + cook_count*3) / view_count
dish_score = avg_score * 0.6 + 互动率 * 0.4
```

**问题 A：互动率没有上界。**
一个人点赞+收藏+打卡，分子 = 1+2+3 = 6，`view_count = 1` → 互动率 = 6.0。
于是 `dish_score = 5*0.6 + 6*0.4 = 5.4`，超过 5 分满分，且 3 次浏览全三连的菜会碾压 1000 次浏览的菜。

**问题 B：量纲不一致（初版没注意到）。**
`avg_score` 是 1~5，互动率是 0~1，直接加权后：
- 评分从 1 到 5 → `dish_score` 变化 2.4（0.6×4）
- 互动率从 0 到 1 → `dish_score` 变化 0.4

名义上「互动占 40% 权重」，实际只占约 12%，互动率形同虚设。

### 3.2 修订后的算法

```sql
-- ① 人均互动权重：一个人最多贡献 1(赞)+2(藏)+3(做)=6
raw = (like_count*1 + collect_count*2 + cook_count*3) / view_count

-- ② 量程归一：除以"满分基准" BASELINE，再封顶防极端
--    BASELINE 按线上数据标定（当前 0.4，见下方实测），配在 Nacos 的 recommend.score.interaction-baseline
interaction_rate = LEAST(1.0, raw / 0.4)

-- ③ 评分置信系数：不满 10 人线性打折（不是威尔逊，两句话可解释）
confidence = LEAST(1.0, score_count / 10.0)

-- ④ 综合得分（百分制）：两项都归一到 0~1 再加权，量纲一致
dish_score = ( (rating_avg / 5.0) * 0.6 * confidence  +  interaction_rate * 0.4 ) * 100
```

**基准为什么取 0.4（而不是理论上限 6.0）** —— 这一版是用真实数据标定的，不是拍脑袋：

`raw` 的理论上限是 6（人人点赞+收藏+打卡），但真实数据里根本不会出现。
对库里 110 道已发布菜实测：

| 指标 | 实测值 |
|---|---|
| `raw` 均值 | 0.233 |
| `raw` 最大值 | 0.375 |
| 互动渗透率 `(赞+藏+做)/浏览` 均值 | **14%** |

三种基准的对比：

| 基准 | 互动率均值 | 结果 |
|---|---|---|
| 6.0（理论上限） | 0.039 | 0.4 权重形同虚设 |
| 3.0（初版设想） | 0.078 | 分数全挤在 52~60，无区分度 |
| **0.4（按实测标定）** | **0.388** | 分数落 52~87，区分度良好 |

所以基准取略高于实测最大值（0.375）的 **0.4**：最好的菜接近满分，典型菜在 0.6 附近。
**这个值必须可配置** —— 真实运行一段时间后数据分布会变，要能重新标定而不改代码。

**为什么输出百分制**：
排序直观、前端好展示、论文好写（`dish_score ∈ [0,100]`）。

### 3.3 算例验证

| 场景 | rating_avg | score_count | 互动率 | confidence | dish_score |
|---|---|---|---|---|---|
| 5星·50人·人人点赞收藏 | 5.0 | 50 | 1.00 | 1.0 | **100** |
| 4星·50人·人人点赞收藏 | 4.0 | 50 | 1.00 | 1.0 | **88** |
| 5星·50人·无人互动 | 5.0 | 50 | 0.00 | 1.0 | **60** |
| 4星·50人·人人只点赞 | 4.0 | 50 | 0.33 | 1.0 | **61.3** |
| 5星·**2人**·互动率0.5 | 5.0 | 2 | 0.50 | 0.2 | **32** |
| 3星·20人·互动率0.5 | 3.0 | 20 | 0.50 | 1.0 | **56** |
| 无人评分·互动率0.5 | 0 | 0 | 0.50 | 0 | **20** |

关键结论：
- **第 2 行 > 第 3 行**（88 > 60）：互动率真正逆转了排序，40% 权重名副其实 ✓
- **第 5 行**（5星但只有 2 人）被压到 32 分：小样本不再虚高 ✓
- **最后一行**：无人评分的菜最高只有 `0.4*100 = 40` 分，天然排在有好评的菜后面 ✓

### 3.4 边界保护

```sql
-- view_count = 0 时互动率记 0，避免除零导致 NULL 参与排序
interaction_rate = IF(view_count = 0, 0,
                      LEAST(1.0, (like_count + collect_count*2 + cook_count*3) / view_count / 3.0))
```

---

## 4. 接口设计

统一挂在 `/api/dish/**` 下（网关已路由，**无需新增路由配置**）。

| # | 接口 | 方法 | 说明 |
|---|---|---|---|
| 1 | `/ai/recommend` | GET | 获取推荐流 |
| 2 | `/ai/recommend/{id}/view` | POST | 上报查看 |
| 3 | `/ai/recommend/{id}/interact` | POST | 上报互动（点赞/收藏/打卡） |
| 4 | `/ai/dish/{id}/score` | POST | 提交评分（**复用现有**） |
| 5 | `/ai/rank?type=cold` | GET | 冷启动排行榜 |

### 4.1 获取推荐流

```
GET /ai/recommend?ingredientIds=101,102&page=1&size=10
```

响应：

```json
{
  "code": 200,
  "data": {
    "items": [
      {
        "id": "1900000000000000001",
        "name": "番茄炒蛋",
        "coverUrl": null,
        "summary": "...",
        "rankLevel": 1,
        "matchType": "full",
        "matchedIngredientNames": ["番茄", "鸡蛋"],
        "pushCount": 63, "viewCount": 11,
        "ratingAvg": 4.4, "ratingCount": 37,
        "likeCount": 12, "collectCount": 8, "cookCount": 3
      }
    ],
    "page": 1, "size": 10, "hasMore": true
  }
}
```

`matchType` 回传给前端，用于展示「完全匹配 / 部分匹配 / 为你推荐」角标——
**这个字段是毕设答辩的加分项**，能让推荐逻辑"可见"。

后端处理顺序：

1. 解析 `ingredientIds`，查 `t_dish_ingredient` 得到含这些食材的候选菜
2. 三级优先级取菜（见 §5.1）
3. 过滤：24 小时内已推给该用户的（§2.3 子查询）
4. 过滤：作者自己发布的菜（`creator_id <> #{userId}`，自己不能刷自己的菜）
5. 只取 `status=1 AND rank_level IN (1,2)`（推荐池 + 已上榜都可被推）
6. **批量 `push_count + 1`** 并写推流日志
7. 组装返回

> 计数时机：后端返回时直接累加，不需要前端再上报一次「曝光」。
> 「出现在屏幕上」与「返回给客户端」在毕设精度下等价，省一次请求。

### 4.2 上报查看

```
POST /ai/recommend/{id}/view
```

```sql
UPDATE t_dish SET view_count = view_count + 1 WHERE id = #{dishId};
```

在**菜品详情接口**里调用（用户点进详情时），不要放在列表接口。

### 4.3 上报互动

```
POST /ai/recommend/{id}/interact?type=like|collect|cook
```

- `like` → `like_count + 1`
- `collect` → `collect_count + 1`（同时写 `t_dish_collect` 明细，保持计分与明细一致）
- `cook` → `cook_count + 1`（同时把该用户在 `t_dish_rating` 的 `cooked_flag` 置 1，若没有评分行则插一条 `score=NULL` 的打卡行）

点赞/收藏需幂等：用 `t_dish_collect` 的唯一键 + 取消时 `deleted=1`、计数按明细重算
（沿用现有收藏的实现方式，不要直接 `+1` 了事）。

### 4.4 提交评分

**完全复用现有** `DishInteractionService#submitRating`，无需新代码：

- `INSERT ... ON DUPLICATE KEY UPDATE score=?` → 改分不新增行
- 同一事务内按明细重算 `rating_avg` / `rating_count`
- 返回最新的 `average` / `count` 给前端

### 4.5 排行榜

```
GET /ai/rank?type=cold&page=1&size=10
```

```sql
SELECT id, name, summary, cover_url, rank_level,
       rating_avg, rating_count, view_count, like_count, collect_count, cook_count, dish_score
FROM t_dish
WHERE deleted = 0 AND status = 1 AND rank_level = 2
ORDER BY dish_score DESC, view_count DESC
LIMIT #{size} OFFSET #{offset};
```

排序完全交给 SQL，应用层不做二次排序（口径只有一份）。

---

## 5. 核心业务逻辑

### 5.1 食材优先匹配（三级）

```java
// 用户选了 N 种食材，按匹配度分三档取菜
List<Long> fullMatch   = 全部选中的食材都有的菜;   // 交集全匹配
List<Long> partialMatch = 命中 1~(N-1) 种的菜;     // 部分匹配
List<Long> others      = 推荐池里剩下的菜;          // 随机补齐

// 拼装：全匹配 → 部分匹配 → 随机补齐，凑满 size 为止
```

SQL 侧实现全匹配（每种食材一个 `term`，不能用 `terms`——ES/MySQL 的 `IN` 是 OR 语义）：

```sql
SELECT dish_id FROM t_dish_ingredient
WHERE ingredient_id IN (#{ids})
GROUP BY dish_id
HAVING COUNT(DISTINCT ingredient_id) = #{idCount}
```

部分匹配同上，把 `= #{idCount}` 换成 `BETWEEN 1 AND #{idCount-1}`，按命中数降序。

每档内部 `ORDER BY RAND()` 随机（毕设数据量小，足够）。

### 5.2 推流去重

见 §2.3。`NOT EXISTS` 子查询过滤 24 小时内已推的菜。

### 5.3 晋级定时任务

```java
@Scheduled(cron = "0 0 3 * * ?")   // 每天 03:00
public void promote() {
    int minPush = pushThreshold;        // Nacos: recommend.promote.min-push-count = 50
    double minRate = rateThreshold;     // Nacos: recommend.promote.min-view-rate = 0.10

    int n = dishMapper.promoteToRank(minPush, minRate);
    log.info("晋级 {} 道菜进排行榜", n);
}
```

```sql
UPDATE t_dish
SET rank_level = 2, update_time = NOW()
WHERE deleted = 0 AND status = 1 AND rank_level = 1
  AND push_count >= #{minPush}
  AND view_count / push_count >= #{minRate};
```

**注意 `view_count / push_count` 是整数除法** —— 要么把列转 DECIMAL，要么写成
`view_count >= push_count * #{minRate}`。推荐后者，避免精度陷阱。

### 5.4 得分计算定时任务

```java
@Scheduled(cron = "0 30 3 * * ?")   // 每天 03:30（排在晋级之后）
public void calcScore() {
    dishMapper.recalcDishScore();
}
```

```sql
UPDATE t_dish
SET dish_score = ROUND(
      ( (rating_avg / 5.0) * 0.6 * LEAST(1.0, rating_count / 10.0)
        + IF(view_count = 0, 0,
             LEAST(1.0, (like_count + collect_count * 2 + cook_count * 3)
                        / view_count / 3.0)) * 0.4
      ) * 100, 2),
    update_time = NOW()
WHERE deleted = 0 AND status = 1 AND rank_level = 2;
```

只算 `rank_level = 2` 的菜，推荐池里的菜不参与算分（还没验证过，算了也没意义）。

---

## 6. 与现有架构的对接

### 6.1 服务归属

全部放 **dish-service**，新建 `recommend` 包：

```
com.apesource.dishservice.recommend
  ├── RecommendController      # 推荐流 / 上报查看 / 上报互动
  ├── RecommendService         # 三级匹配 + 去重 + 计数
  ├── PushLogMapper
  ├── RecommendProperties      # Nacos 阈值
  └── job
      ├── RankPromoteJob       # 每日晋级
      └── DishScoreJob         # 每日算分
```

**不要改现有 `DishRankService`**：它管的 hot/top/latest 三榜按威尔逊排序，是另一套口径。
冷启动榜作为 `type=cold` **增量接入**，老功能不受影响。

### 6.2 Nacos 配置（按环境分档）

加进 `dish-service.yaml`。**阈值不写死在代码里**，三档环境只改配置不改代码：

```yaml
recommend:
  promote:
    min-push-count: 10      # 演示档；测试档 20；生产档 50
    min-view-rate: 0.30     # 演示档；测试档 0.25；生产档 0.10
    min-view-count: 3       # 附加下限：光看比例不够，还要有绝对量的兜底
  score:
    min-score-count: 3      # 演示档；测试档 5；生产档 10（置信度满分所需评分人数）
```

三档对照：

| 档位 | push_count | 查看率 | 附加最小查看数 | 最少查看次数 | 适用场景 |
|---|---|---|---|---|---|
| **演示档** | 10 | 30% | 3 | 3 | 答辩现场、自己联调，几分钟能走完一次晋级 |
| 测试档 | 20 | 25% | 5 | 5 | 验收、给同学试用，有一点统计意义 |
| 生产档 | 50 | 10% | 5 | 5 | 论文里写的设计值，真实流量规模 |

**为什么还要 `min-view-count` 这个附加下限**：
只看比例会出事——推流 10 次、查看 3 次是 30%，但推流 100 次、查看 3 次是 3%（不达标）。
反过来，如果只卡比例不卡绝对量，一道菜推 10 次里碰巧有 3 次被点开就上榜，偶然性太大。
加一道 `view_count >= min-view-count` 兜底，成本是 SQL 多一个条件。

**论文里怎么写**：正文写生产档（50 / 10%），并在"系统实现"章节说明阈值通过 Nacos
下发、可按部署环境调整，附上面这张分档表 —— 这比写死一个数字显得更工程化。

### 6.3 ES 对接

`DishDoc` 与 mapping 增加：`rank_level`、`dish_score`、`push_count`、`like_count`、`cook_count`。

- 改完 mapping **必须 `POST /ai/es/reindex`**（索引不会自动加字段）
- 搜索默认只查 `rank_level = 2`：在 `EsDslBuilder` 的 `filter` 里加一个 term 即可
- 排序加 `dish_score` 字段参与

### 6.4 演示辅助接口（关键，否则现场演示跑不完）

定时任务每天 03:00 才执行一次，**答辩现场不可能等**。所以必须留手动触发口子：

```java
/** 模拟推流：把 push_count 直接加上去，避免手动刷几十次推荐页 */
@PostMapping("/ai/admin/dish/{id}/simulate-push")
public Result<?> simulatePush(@PathVariable Long id,
                              @RequestParam(defaultValue = "10") int times) {
    dishMapper.addPushCount(id, times);
    return Result.success(null);
}

/** 手动触发晋级（与 03:00 的定时任务跑同一段逻辑） */
@PostMapping("/ai/admin/rank/promote")
public Result<?> promoteNow() {
    return Result.success(rankPromoteJob.promote());
}

/** 手动触发算分（与 03:30 的定时任务跑同一段逻辑） */
@PostMapping("/ai/admin/rank/calc-score")
public Result<?> calcScoreNow() {
    return Result.success(dishScoreJob.calcScore());
}
```

论文里表述为「为便于演示与验证而提供的管理端触发接口，与定时任务共用同一业务逻辑」。

**答辩现场的完整演示脚本**（约 2 分钟）：

```bash
# 1. 发布一道菜（AI 生成或手写均可）→ rank_level 自动变 1
# 2. 模拟推流 10 次
curl -X POST ".../api/dish/ai/admin/dish/{id}/simulate-push?times=10"
# 3. 用 3 个账号（或同一账号 3 次）点进详情页 → view_count = 3
# 4. 打个 4 星、点个赞
# 5. 手动触发晋级 → 3/10 = 30% 达标 → rank_level = 2
curl -X POST ".../api/dish/ai/admin/rank/promote"
# 6. 手动触发算分 → dish_score 出来
curl -X POST ".../api/dish/ai/admin/rank/calc-score"
# 7. 刷新排行榜页，菜出现在榜上
curl ".../api/dish/ai/rank?type=cold"
```

---

## 7. 前端要点

| 页面 | 文件 | 关键交互 |
|---|---|---|
| 推荐页 | `RecommendView.vue`（新建） | 食材多选标签 → 卡片流 → 点击上报查看 → 点赞 → 下拉刷新 |
| 详情页 | `DishView.vue`（改造） | 星级评分 1~5、收藏按钮、试做打卡按钮 |
| 排行榜 | `RankView.vue`（加 `cold` 类型） | 排名列表 + 平均分 + 查看数 + 点赞数 + 分页 |
| 我的 | `MyView.vue`（改造） | 菜品状态徽章：推荐中(rank_level=1) / 已上榜(2)；作者可见 push/view/avg |

推荐页卡片右上角展示 `matchType` 角标（完全匹配 / 部分匹配 / 为你推荐）。

---

## 8. 完整链路演示

| 步骤 | 动作 | push_count | view_count | like_count | rating_avg | rating_count | rank_level |
|---|---|---|---|---|---|---|---|
| 1 | 用户 A 用 AI 生成「蒜香黄油虾」 | 0 | 0 | 0 | 0 | 0 | 0（草稿） |
| 2 | A 点「发布」 | 0 | 0 | 0 | 0 | 0 | **1** |
| 3 | 系统推给 B、C、D… | 3 | 0 | 0 | 0 | 0 | 1 |
| 4 | B 点进详情 | 3 | 1 | 0 | 0 | 0 | 1 |
| 5 | B 打 4 星 | 3 | 1 | 0 | 4.00 | 1 | 1 |
| 6 | B 点赞 | 3 | 1 | 1 | 4.00 | 1 | 1 |
| … | 模拟推流凑到 10 次，C、D 也点开详情 | 10 | 3 | 2 | 4.33 | 3 | 1 |
| 7 | 手动触发晋级：3/10 = **30%** ≥ 30% 且 10 ≥ 10 且 view 3 ≥ 3 | 10 | 3 | 2 | 4.33 | 3 | **2** |
| 8 | 手动触发算分（见下方拆解） | 10 | 3 | 2 | 4.33 | 3 | 2 |
| 9 | 排行榜按 dish_score 降序展示，「蒜香黄油虾」以 **69.74** 分入榜 | | | | | | 2 |

**第 8 步拆解**（演示档 `min-score-count = 3`）：

```
互动率     = (like 2×1 + collect 1×2 + cook 0×3) / view 3 / 3.0 = 4/3/3 = 0.444
置信系数   = LEAST(1.0, rating_count 3 / 3) = 1.0
dish_score = (4.33/5 × 0.6 × 1.0 + 0.444 × 0.4) × 100 = (0.5196 + 0.1778) × 100 = 69.74
```

> **同一个场景换到生产档会怎样**：`min-score-count` 若配成 10，
> 置信系数变成 3/10 = 0.3，`dish_score = (0.5196×0.3 + 0.1778)×100 = 33.37`。
> 分差 69.74 → 33.37 全靠配置切换，这段代码一行没改 —— 这就是把阈值放进 Nacos 的意义，
> 论文里可以拿这组对比数字当例子。

---

## 9. 论文可写的几点

1. **沿用既有状态机**：`status=1` 语义本就是「进推荐池」，冷启动未引入新的菜品状态
2. **分级曝光机制**：新菜不直接进排行榜，先在推荐池接受真实流量验证（查看率），达标才晋级
3. **双指标解耦**：晋级看「流量质量」（查看率），排序看「内容质量」（综合得分），
   避免只看热度导致的马太效应
4. **小样本折扣**：线性置信系数替代威尔逊下界，在保证抗刷分的同时保持可解释性
5. **互动权重设计**：以「点赞+收藏」为满分基准做量程归一，解决加权互动率无上界的问题
