# 「发现」模块重构设计

> 2026-10-07。取代 `docs/rank-redesign.md`（那份仍在"四个榜"格局内修补）。
> 目标：把推荐页与榜单页合并为一个入口，以「帮我决定今天吃什么」为第一目标。
> 所有阈值均用库内 112 道已发布菜的真实数据标定。

---

## 1. 先回答：三个方向是并列还是串联

**是串联的流水线，不是并列。** 这正是它们能同时做、且互相不打架的原因：

| 方向 | 落点 | 在流水线里的位置 |
|---|---|---|
| 方向二（手边食材） | 信号层最强的一个信号 | **最前**：决定"这道菜你做不做得了" |
| 方向一（筛选器） | 排序前的硬过滤 | **中间**：先砍掉不合适的，再排 |
| 方向四（帮我决定） | 呈现层的一个分支 | **最后**：同一份排序结果的另一种消费方式 |

只有**呈现层的两种模式是并列**的（浏览 / 决策），它们共用上游全部结果。

```
输入（食材 + 情境 + 用户）
   ↓
信号层：匹配度 · 质量 · 情境 · 个性化     ← 方向二在这里
   ↓
排序层：feed_score 加权合成               ← 方向一在这里做硬过滤
   ↓
呈现层：├─ 浏览模式（列表）                ← 方向一
        └─ 决策模式（一屏一道）            ← 方向四
```

**关键推论**：方向四不需要新算法。它和浏览模式吃的是同一份排序结果，
只是"一次给 N 条"和"一次给 1 条、可换"的区别。工作量主要在前端。

---

## 2. 模块深度：力气要花在别人没有的东西上

这是本次设计最重要的一条原则。

| 模块 | 深度 | 理由 |
|---|---|---|
| **食材匹配** | **深** | 本产品最独特的信号，也是论文里唯一别人没有的东西 |
| 质量分 | 中 | 已有 `rating_wilson` + 互动率基础，改进即可，不重做 |
| 情境匹配 | **浅** | 本质是筛选器，且实测数据支撑有限（见 §2.3） |
| 个性化 | **暂不做深** | 数据稀疏，硬做只会得到一堆噪声（见 §2.4） |
| 决策模式 | 中 | 交互是亮点，算法复用排序层，工作量在前端 |

反过来说：**不要在质量分上过度设计**。威尔逊下界 + 互动率已经够用，
再堆公式的边际收益远低于把食材匹配做扎实。

### 2.1 食材匹配（深）

沿用 ES 侧已有的缺料口径，**不要新造一套**：

```
U = 用户手边食材（经 IngredientDirectory 归一后）
R = 菜品必需食材 = 全部非调料食材        ← 注意不是 mainIngredientIds
S = 菜品调料 = seasonings 字段
```

> **坑（已踩过）**：每道菜只标 1 个主料，拿 `mainIngredientIds` 当必需集合
> 会让缺料恒为 0。必须用"非调料"口径。

```java
satisfyRate = |U ∩ R| / |R|
missing     = R - U                       // 缺的非调料食材
missingSoft = (S - U) ∩ 常见调料表        // 缺的调料，可豁免

matchScore = satisfyRate
           - 0.15 × (|missing| / |R|)     // 缺料惩罚
           + (missing.isEmpty() ? 0.10 : 0)   // 全齐加分
```

**档位（给前端做角标，也是答辩可讲的东西）**：

| 档位 | 判定 | 卡片上怎么说 |
|---|---|---|
| 齐活 | `missing` 为空 | 「食材都齐了」 |
| 差一点 | `missing` ≤ 1 且非主料，或缺的只是调料 | 「还差 1 样：姜」 |
| 缺得多 | 其余 | 「需要再买 3 样」 |

**实测支撑**：`t_dish_ingredient` 677 行 / 153 道菜 = 平均 4.4 种食材/菜，
`seasonings` 平均 2.9 个（111/112 道有值）—— 口径完整，算得出来。

### 2.2 质量分（中）

```
raw          = (like_count + collect_count*2 + cook_count*3) / view_count
qualityScore = 0.65 × rating_wilson × 100
             + 0.35 × LEAST(1.0, raw / 0.4) × 100
```

**为什么用威尔逊换掉现在的 `(rating_avg/5) × LEAST(1, rating_count/3)`** —— 实测三条：

| 对比项 | 威尔逊 | 平均分/5 |
|---|---|---|
| 实测范围 | 0.494 ~ 0.794 | 0.746 ~ 0.962 |
| 区分度（极差） | **0.300** | 0.216 |
| 置信项实测均值 | —— | **0.982**（形同虚设，110 道里 108 道满分） |

`interaction_raw` 实测 0.115~0.375、均值 0.233，除以基准 0.4 后落在 0.29~0.94 ——
**基准 0.4 是当初用真实数据标定的，继续沿用，不要动**。

### 2.3 情境（浅，主要是硬过滤）

实测字段填充率（112 道已发布）：

| 字段 | 有值 | 分布 | 能做什么 |
|---|---|---|---|
| `cook_time_min` | 111 | —— | 时长筛选 ✓ |
| `difficulty` | 112 | —— | 难度筛选 ✓ |
| `base_serving` | 112 | **2人 75 / 3人 23 / 4人 11** | 人数筛选，但集中在 2 人，区分度有限 |
| `spicy_level` | 21（>0） | 0×91 / 1×11 / 2×7 / 3×3 | 只能做「要辣 / 不辣」二元 |
| `seasonings` | 111 | 平均 2.9 个 | 缺料豁免 ✓ |

结论：**情境做成硬过滤 + 极小权重打分就够了**，不值得为它设计复杂模型。
`base_serving` 的偏态说明"人数"这个维度在数据上撑不起细粒度筛选。

### 2.4 个性化（留接口，暂不做深）

`taste_tag` 87% 有值（97/112），但取值极其分散：家常 19、酱香 9、清淡 9、清爽 5、麻辣 4……
`spicy_level` 只有 21 道非零。

**在这个数据密度下做口味画像，得到的只会是噪声。**
建议：`TasteProfileSignal` 先留一个返回常量的空实现 + 配置项，
等有真实用户行为数据再启用。论文里可以写"预留了个性化扩展位"。

---

## 3. 排序层

```java
feedScore = 0.45 × matchScore
          + 0.30 × qualityScore / 100
          + 0.15 × contextScore
          + 0.10 × personalScore   // 当前恒为中性值 0.5
```

权重进 Nacos（`discover.weights.*`），方便答辩现场调。

**顺序很重要**——筛选是硬过滤，必须在打分之前：

```
召回 → 硬过滤（时长/难度/辣度/人数） → 打分 → 排序 → 分页 或 取 Top1
```

把筛选做成打分项是个常见错误：用户勾了"只要 20 分钟内"，
一道 60 分钟的菜不该因为质量高就被排进来。

### 召回走 ES 还是 MySQL

**走 ES**，理由：缺料口径、食材双路召回（ID + 表外名字）都已实现。

**关键发现**：`DishEsSyncService` 同步时只按 `status=1` 过滤，**不按 rank_level**
（`EsDslBuilder` 里的 `term rankLevel=2` 是查询时加的）。
所以**推荐池里的新菜（rank_level=1）本来就在索引里**。

```
搜索/榜单：加 rankLevel=2 过滤（现状，不变）
发现流  ：不加过滤，或改成 should 加权 → 新菜自然获得曝光
```

**冷启动因此从"一个独立榜"变成"排序的一个输入"** —— 这是本次重构最漂亮的一处收敛：
机制还在（推荐池 → 晋级 → 阈值 Nacos 分档全部保留），但不再占用户可见的位置。

---

## 4. 呈现层

### 4.1 浏览模式（取代现在的四个榜）

一屏 N 条，每张卡片必须有：

| 信息 | 来源 |
|---|---|
| 匹配档位角标 | `matchScore` 档位 |
| 缺料清单 | `missing` / `missingSoft` |
| 做过的人数 / 评分 | `cook_count` / `rating_avg` |
| 时长、难度 | `cook_time_min` / `difficulty` |

**筛选器取代榜单切换**：时长、难度、辣度、人数 —— 用户直接表达需求，
不用在"热门/高分/最新"之间来回切。

### 4.2 决策模式（一屏一道）

```
        ┌─────────────────┐
        │   番茄鸡蛋松露炒蛋   │
        │                 │
        │  你手边的番茄、鸡蛋   │
        │  都能用上           │  ← 理由必须显式给出
        │                 │
        │  327 人做过 · 20 分钟 │
        └─────────────────┘
         [ 就这个 ]  [ 换一个 ]  [ 不想吃 ]
```

三条实现要点：

1. **「换一个」不是重算，是取下一位**。一次性取 Top 20，逐个呈现。
   每次重算会重复出同一道菜。
2. **理由必须显式**。这是这个模式存在的全部意义——用户信的是理由，不是算法。
3. **「不想吃」是负反馈**，记进 `t_dish_push_log`（复用现成表加一个 `feedback` 字段），
   下次不再推。现在的 24 小时去重做不到这一点。

---

## 5. 代码结构

```
com.apesource.dishservice.discover          （新包）
├── signal/
│   ├── IngredientMatchSignal             深：满足率 + 缺料惩罚 + 档位
│   ├── QualitySignal                     中：威尔逊 + 互动率
│   ├── ContextFilter                     浅：硬过滤 + 小权重打分
│   └── TasteProfileSignal                P2：当前返回中性值
├── DiscoverRanker                        加权合成
├── DiscoverService                       召回 → 过滤 → 打分 → 排序
├── DiscoverController                    /ai/discover/browse、/ai/discover/decide
└── DiscoverProperties                    Nacos 权重与阈值

com.apesource.dishservice.recommend.job    保留不动
├── RankPromoteJob                        晋级（后台机制）
└── DishScoreJob                          算分（后台机制）
```

`DishRankService` 与四个榜的 SQL：**保留一段时间做对照**，前端切换后再删。
不要一上来就删——答辩时"重构前后对比"是很好的素材。

### 前端

| 现在 | 之后 |
|---|---|
| `RecommendView.vue` | → `DiscoverView.vue` |
| `RankView.vue` | → 并入 `DiscoverView.vue` |
| 四个 tab | → 两个模式 tab（浏览 / 帮我决定）+ 筛选器行 |

---

## 6. 落地顺序

| 阶段 | 内容 | 工作量 | 依赖 |
|---|---|---|---|
| **S0** | 修三个真 bug（见 §7） | ~20 行 | 无 |
| **S1** | `IngredientMatchSignal` + `QualitySignal` | ~150 行 | S0 |
| **S2** | `DiscoverService` + 浏览模式接口 | ~120 行 | S1 |
| **S3** | 前端 `DiscoverView`（浏览模式） | ~300 行 | S2 |
| **S4** | 决策模式（后端 + 前端） | ~200 行 | S2 |
| **S5** | 个性化（可选，等真实数据） | ~150 行 | S1 |

**S0 可以立刻做**，它不依赖任何方向选择。
**S1~S3 是一条完整可演示的链路**，做到 S3 就有东西给答辩看了。
S4 决策模式是锦上添花，时间紧可以先不做。

改完必须重跑：`POST /ai/es/reindex`、`es/eval/run-ranking-experiment.py`。
`dish_score` 数值会整体下移（威尔逊比 `avg/5` 低），这是预期的。

---

## 7. 三个真 bug（S0，与重构方向无关，先修）

| # | 位置 | 问题 |
|---|---|---|
| 1 | `countRated()` | 缺 `rank_level >= 2`，与 `selectTopRank` 不一致 → total 虚高、尾部空页 |
| 2 | `selectHotRank` / `selectLatestRank` | 未过滤 `rank_level` → 新菜绕过冷启动；且搜索只召回 rankLevel=2，出现「榜单有、搜索搜不到」 |
| 3 | `reportView` | 只在推荐路径调用，是互动率分母；而 like/cook/collect 来自全入口 → 分子全量分母单入口，互动率虚高 |

---

## 8. 论文素材

1. **以决策为目标的信息组织**：不以"全局最优排序"组织内容，而以"用户此刻能否做成"
   为第一信号，将社交证明与内容质量降为次级信号。
2. **食材可用度建模**：以非调料食材集合为必需集，区分硬性缺口与调料缺口，
   输出可执行的缺料清单而非抽象的匹配分数。
3. **冷启动机制的收敛**：分级曝光（推荐池 → 晋级）作为排序系统的流量分配输入，
   不单独对用户呈现，避免将系统内部机制暴露为产品概念。
4. **两种消费形态共享一套排序**：浏览与决策两种交互共用同一份排序结果，
   保证"帮我选"与"我自己挑"的结论一致。
5. **模块深度的非对称分配**：对差异化信号（食材可用度）深度建模，
   对通用信号（内容质量、情境）保持适度，避免在边际收益低的模块上过度设计。

---

## 9. 实现状态（2026-10-07 当晚）

| 阶段 | 内容 | 状态 |
|---|---|---|
| S0 | 三个真 bug | ✅ 完成并编译通过 |
| S1 | 四个信号 | ✅ 完成，个性化按设计留空实现 |
| S2 | 排序层 + 服务 + 接口 | ✅ 完成 |
| S3 | 前端浏览模式 | ✅ 完成（语法已校验，未跑端到端） |
| S4 | 前端决策模式 | ✅ 完成（语法已校验，未跑端到端） |

### 代码清单

后端（dish-service）：

| 文件 | 说明 |
|---|---|
| `discover/DiscoverProperties.java` | 阈值与权重，Nacos 下发 |
| `discover/DiscoverFilter.java` | 情境硬过滤条件 |
| `discover/signal/IngredientMatchSignal.java` | 食材匹配（深） |
| `discover/signal/QualitySignal.java` | 质量分（中） |
| `discover/signal/ContextSignal.java` | 情境（浅） |
| `discover/signal/TasteProfileSignal.java` | 个性化（空实现） |
| `discover/DiscoverRanker.java` | 加权合成 + 排序 |
| `discover/ScoredDish.java` | 打分结果 + 推荐理由 |
| `discover/DiscoverItemView.java` | 卡片视图 |
| `discover/DiscoverService.java` | 召回 → 过滤 → 打分 → 分页 |
| `discover/DiscoverController.java` | `/ai/discover/browse`、`/ai/discover/decide` |
| `service/DishViewCounter.java` | 浏览计数（从推荐模块迁出） |

前端（frontend-vue）：`views/DiscoverView.vue`、`styles/discover.css`，
`api.js` 加了 `discoverBrowse` / `discoverDecide`，路由加了 `/discover`，
顶栏「推荐」改为「发现」，旧 `/recommend` 重定向过来。

### 落地时对原设计的两处修正

1. **辣度筛选没做。** `DishDoc` 里没有 `spicyLevel`，ES 侧筛不了。
   补字段要改 mapping + 重建索引，而库里只有 21 道非辣菜，收益不抵成本。
   做法写在 `DiscoverFilter` 的注释里了（三步：DishDoc 加字段 → mapping → reindex）。

2. **卡片没有摘要和封面。** ES 的 `SOURCE_FIELDS` 本来就不含 `summary`、`coverUrl`，
   搜索侧的 `DishItem` 同样没有——整个检索链路都不带这两个字段。
   这里不破例去补一次查库，保持两条链路一致。
   前端确实需要封面时，加一次按 ID 批量补查即可（一次查询，成本很低）。

### 还没做的验证

- **端到端没跑。** 需要启动 dish-service + Nacos + ES，并推送新的
  `discover.*` 配置（当前只在 `nacos-config/dish-service.yaml` 留档，没推）。
- **前端构建没跑通。** `node_modules/esbuild` 的二进制在本机起不来
  （`The service was stopped`，与本次改动无关）。
  改用 `@vue/compiler-sfc` 解析校验了 `DiscoverView.vue` 的模板与脚本，均通过。
- **`es/eval/` 的排序实验脚本要重跑**，排序口径变了，旧结论失效。

### 旧榜单的处置

`DishRankService` 和四个榜的 SQL **全部保留**，顶栏「榜单」入口也保留。
一是重构前后对比本身就是答辩素材，二是万一新页有问题还有退路。
确认新页没问题后，删 `/rank` 路由、`RankView.vue`、`rank.css` 和顶栏那一项。
