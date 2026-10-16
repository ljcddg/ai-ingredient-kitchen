# AI 灵感厨房 · ai-ingredient-kitchen

> 挑几样食材，系统先在菜库里找现成的 —— 命中高质量的做法直接返回；
> 没有就让 AI 现场生成一道。生成结果经校验后可发布进公共菜库，
> 被搜索、被排名、被收藏。
> 懒得选食材？**拍张照片**，YOLO 认出图里有什么，勾选后直接进检索或生成。

**用户既是消费者也是生产者**：AI 生成的内容会沉淀回菜库，菜库越厚、直出率越高、AI 调用越少。

<p>
  <img alt="Java" src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.2.4-6DB33F?logo=springboot&logoColor=white">
  <img alt="Spring Cloud" src="https://img.shields.io/badge/Spring%20Cloud-2023.0.1-6DB33F?logo=spring&logoColor=white">
  <img alt="Nacos" src="https://img.shields.io/badge/Nacos-2.3.2-2F7DD1">
  <img alt="Elasticsearch" src="https://img.shields.io/badge/Elasticsearch-8.16-005571?logo=elasticsearch&logoColor=white">
  <img alt="Redis" src="https://img.shields.io/badge/Redis-Redisson%203.26-DC382D?logo=redis&logoColor=white">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white">
  <img alt="Ollama" src="https://img.shields.io/badge/LLM-Ollama%20local-000000">
  <img alt="YOLO" src="https://img.shields.io/badge/YOLO-v8n%20ultralytics-7B42BC">
  <img alt="Python" src="https://img.shields.io/badge/Python-3.12-3776AB?logo=python&logoColor=white">
  <img alt="Flask" src="https://img.shields.io/badge/Flask-3.x-000000?logo=flask&logoColor=white">
</p>

---

## 这是什么

一个用 AI 做美食的平台。用户选（或输入）食材，得到一道能做出来的菜：

```
                    ┌──────────── 用户选 / 输食材 ────────────┐
                    ▼                                        │
          ① 检索菜库（ES 粗排 + 应用层精排）                     │
                    │                                        │
        ┌─── 命中高质量（直出） ───┐         ┌── 未命中 / 质量不足 ──┐
        ▼                        ▼         ▼                     │
   直接返回结果              返回 + 说明原因  ② AI 生成（排队执行）    │
        │                                   │                    │
        │                           ③ 落库为草稿(0)              │
        │                                   │                    │
        │                           ④ 校验发布 → 已发布(1)        │
        │                                   │                    │
        └──────────── ⑤ 进索引 → 被检索 / 上榜 / 被收藏评分评论 ───┘
                            （内容沉淀回菜库，反哺 ① 的直出率）
```

闭环的关键在 **④ 和 ⑤**：没有「发布 + 进索引」这一步，AI 的产出一辈子搜不到，
整个功能等于没闭环。所以「生成成功 → 自动试投一次发布」「撤回时同步删除索引」这两件事必须做。

### 内容沉淀：菜库如何自我扩张

```
用户自由输入食材（如「松露」）
        → 记录到 t_ingredient_candidate
        → 累计出现 ≥ 3 次
        → 转正：写 t_ingredient + 回填历史菜品 ID + 重写受影响菜的索引
```

用「先计数、够了再转正」做一道缓冲，让真实需求浮上来，垃圾输入（甚至脏话）自己沉下去 ——
因为食材主数据是所有归一化与营养计算的基准，不能被污染。

除此之外还有第三条内容来源：**用户手写菜谱**（`t_dish.source = 3`）。它不走模型 ——
食材页上「手写菜谱」按钮就地展开一个面板（不跳页），用户填菜名 / 成分表（食材 + 克数）/
做法步骤，面板里实时试算「每人份多少 kcal、覆盖率多少、还差什么」；营养一律按
`t_ingredient` 的成分表本地算，对不上就照 AI 那套降级并标注来源。
落库**一律先存草稿**，发布门槛与 AI 生成共用同一套校验（`DishPublishService.check`：
≥2 样食材、≥3 条步骤、菜名 ≤20 字、热量 > 0、不与已发布菜重名），
够门槛就自动进找菜库，不够就留在草稿里、把原因原样回给用户。

---

## 技术形态

**Spring Cloud 微服务（4 模块）+ Python 推理服务（ai-service）+ Vue3 前端**

| 模块 | 端口 | 职责 | 关键内容 |
|---|---|---|---|
| `common-service` | — | 跨服务技术能力 | `Result<T>`、`BusinessException`、`JwtUtil`、全局 `Long → String` 序列化 |
| `account-service` | 8081 | 账号与认证 | 注册 / 登录、BCrypt、Redisson 布隆过滤器、JWT 签发 |
| `dish-service` | 8082 | **业务核心** | AI 任务队列与编排、检索、发布、互动、榜单、ES 同步 |
| `ai-service` | 8090 | **推理中心**（Python） | 菜谱生成（提示词 + Ollama + 校验 + 重试）、营养计算、图片识菜（YOLO）；无状态，不连业务库，食材主数据随请求注入 |
| `gateway` | 7000 | 统一入口 | JWT 鉴权过滤器、路由、CORS、`DedupeResponseHeader` |
| `frontend-vue` | 5500 | 前端（Vue3 版） | 8 个页面（登录 / 工作台 / 识菜 / 详情 / 发现 / 榜单 / 我的 / 管理），`/api` 反向代理到网关 |
| `order-service` | — | 预留未放开 | 父 pom 中处于注释状态 |

**依赖方向单向**：`account / dish / gateway → common`，`dish → ai-service`（HTTP 调用，不走 Nacos 注册）。
`common` 只放技术能力，**领域实体一律不进 common**（避免变成"什么都往里塞"的垃圾桶）。

**推理边界**：`dish-service` 负责编排、检索、落库和任务队列；`ai-service` 负责提示词拼装、调模型、校验、重试和营养计算。Java 侧不直接调用模型。

### 版本矩阵（存在硬约束，改动前先看）

| 组件 | 版本 | 约束说明 |
|---|---|---|
| JDK | 17 | 全局用 `jakarta.*`，不是 `javax.*` |
| Spring Boot | 3.2.4 | — |
| Spring Cloud | 2023.0.1 | 必须与 Boot 3.2.4 配对 |
| Spring Cloud Alibaba | **2023.0.1.0** | 三者严格对应；内含 Nacos Client 2.3.2 |
| MyBatis-Plus | 3.5.5 | 用 `mybatis-plus-spring-boot3-starter`（Boot 3 专用） |
| jjwt | 0.12.5 | 拆成 api / impl / jackson 三包 |
| Redisson | 3.26.1 | 内置 `redisson-spring-data-32`，匹配 Boot 3.2.x |
| MySQL 驱动 | 8.3.0 | 显式写 version，否则覆盖 Boot BOM 的管理 |
| Elasticsearch | 8.16.0 | 只跑在 Docker（单节点、已关 security），见下方说明 |
| Ollama | `gemma4:e2b`（默认） | 本地推理；模型名在 `ai-service/config.yaml`，dish-service 经 Nacos `ai.remote.model` 随请求下发 |
| Python | 3.12 / Flask 3.x | ai-service 依赖见 `ai-service/requirements.txt`，生产用 gunicorn 常驻 |
| torch / torchvision | 2.6.0+cu124 / 0.21.0+cu124 | ai-service 识菜推理用；**cu124 定制版 PyPI 没有，必须本地 whl 装**（实测 RTX 4060 单图 4.4ms） |
| ultralytics | 8.4.177 | YOLO 官方库；权重 `ai-service/yolo_model/weights/yolov8n.pt`（COCO 80 类预训练，中式食材类需自训数据集） |

---

## 核心链路

### 1. 认证：布隆过滤器前置 + 降级不挂服务

```
用户名 → mightContain?
   ├─ false（100% 准）→ 直接拒绝「用户名已存在」，不查库
   └─ true（可能误判）→ 查库确认 → 不存在才走 BCrypt → 落库 → 布隆 add
```

**两条铁律**：
1. 说「**不存在**」100% 可信 → 直接拒；
2. 说「**存在**」可能是误判 → **必须查库确认**，不能直接拒。

把这两条搞反，会把正常用户挡在门外（假阳性）或放进重复用户名（假阴性）。

**Redis 不可用必须降级**：`mightContain` 失败一律返回 `true`（宁可慢不可错）；
`add` 失败只记 `pendingWrites`，**绝不抛异常** —— 注册方法带 `@Transactional`，
抛出去会把用户已写好的账号回滚掉。连续失败进入 30 秒冷静期。

### 2. AI 做菜：为什么必须自建队列

本地 5B 级模型生成一道菜要 **12~26 秒**，用户不可能对着转圈等。
所以 `POST /ai/dish` 立即返回 `taskId`，前端轮询 `GET /ai/tasks` 拿进度。

| 方案 | 为什么不合适 |
|---|---|
| `ThreadPoolExecutor` + 阻塞队列 | 任务入队后**无法调整顺序**，拿不到稳定的「我排第几」 |
| `PriorityBlockingQueue` | 只在插入时排序，插入后**无法重排** —— 与「用户改顺序」直接冲突 |
| MQ（RocketMQ / RabbitMQ） | 「改顺序」「取消排队」在 MQ 模型里很别扭，还要额外维护一份位置状态 |

**自建 `DishTaskQueue`**：`lock` 保护的有序 `List` 做等待队列
（重排需要整体可见的顺序，无锁队列反而更难写对；队列操作频率极低，锁竞争可忽略）
\+ `ConcurrentHashMap` 记录执行中任务。

```java
// 调度线程骨架
while (!interrupted) {
    synchronized (lock) {
        while (waiting.isEmpty() || running.size() >= concurrency) lock.wait();
        next = waiting.remove(0);
        running.put(next.taskId(), next);
    }
    executor.submit(() -> runAndFinish(next));   // ← 交给执行线程池，调度线程绝不自己执行
}
```

> **调度线程绝不能自己执行任务**：否则它跑一个 10 秒的生成时，
> 没人接新任务，用户提交后连「排队中」的状态都拿不到。

**任务状态机**：`PENDING → RUNNING → SUCCESS / FAILED`，任意阶段可 `CANCELLED`。

**输出质量控制：三道防线**（只靠提示词不够，小模型长输出会漂移）

| 防线 | 做法 | 作用 |
|---|---|---|
| ① 系统提示词 | 明确 JSON 结构 + 字段规则 + 用量范围 | 告诉它要什么 |
| ② `format: "json"` | Ollama 的约束解码参数 | 实测有效：不加会裹 ` ```json ` 代码块 |
| ③ 应用层校验 + 重试 | ai-service 的 `validator.py` 逐字段校验类型/范围/约束，不合格把**具体原因**拼回提示词重试 1 次 | 兜住小概率 |

> 失败信息要分两份：给用户的是人话（「AI 没能用『X』想出合适的做法，换个搭配再试试」），
> 给模型的是字段级细节（「prepTimeMin=0 不在 2~60 之间」）。把后者展示给用户等于没说。

### 3. 检索：`terms` 默认是 OR，不是 AND

这是 90% 的人第一次用 ES 做「包含全部」时踩的坑：

```json
// ❌ 以为这是「必须含 101 和 102 和 103」
// ✅ 实际语义：含任意一个 —— OR 召回
{ "terms": { "ingredientIds": [101, 102, 103] } }
```

**正确写法**：每种食材拼一个独立 `term` 放进 `must` 数组。
并且这个拼装**只允许在一处**（`EsDslBuilder.buildIngredientClause`）做，避免各写各的。

**两条召回路**（让「表外食材」也能被搜到的关键）：

| 食材类型 | 查询字段 |
|---|---|
| 有主数据 ID 的（「番茄」→ id=101） | `ingredientIds`（long 数组） |
| 表外食材（用户自由输入的「松露」） | `ingredientNames`（keyword 数组） |

没有第二条路，用了表外食材的菜**谁也搜不到**。

**直出规则**（确定性规则，同输入同输出）：

```
覆盖率 ≥ 0.8  且  评价人数 ≥ 10  且  威尔逊分 ≥ 0.84
```

不满足时也**不返回空**，而是带上**可解释的 missReason**
（「最接近的『番茄土豆汤』评分偏低（0.70 < 0.84）」），前端据此提示「要不要让 AI 生成一道」。

**降级**：ES 不可用时**返回空列表而不是抛异常**，前端自然走到「AI 生成」分支 ——
检索挂了不影响主流程。

### 4. 榜单：为什么用威尔逊下界而不是平均分

一道菜 1 个人打 5 分（平均 5.0）不该压过 100 个人打 4.6 分的菜。

威尔逊区间下界会对样本量小的评分做惩罚，**天然抗刷分**：

```
p = 加权平均分 / 5，z = 1.96（95% 置信）
lower = (p + z²/2n - z * sqrt(p(1-p)/n + z²/4n²)) / (1 + z²/n)
```

SQL 侧与 ES 侧的威尔逊口径必须一致，否则两边排序会对不上。

### 5. 收藏：唯一键 + 逻辑删除会冲突

取消收藏置 `deleted=1` 后再收藏，`INSERT` 会撞唯一键。正确做法是**恢复式收藏**：

```sql
INSERT INTO t_dish_collect (dish_id, user_id, deleted) VALUES (?, ?, 0)
ON DUPLICATE KEY UPDATE deleted = 0, create_time = NOW();
```

**计数一律按明细表重算**，绝不用 affected rows 做加减。

### 6. 发现流：从「排名」到「决策」

旧的四个榜单各自排序（热门 / 高分 / 最新 / 冷启动），用户要在 tab 之间来回切去找想要的东西。
问题不在公式，在**问题定义**：模块回答的是「哪道菜排第一」，而用户想问的是「我今天做什么」。

2026-10-07 重构为一条流水线。**下面这三个改进是串联关系，不是并列**：

```
输入（手边食材 + 情境 + 用户）
   ↓
信号层：食材匹配（深）· 质量分（中）· 情境（浅）· 个性化（空实现）
   ↓
排序层：feedScore 加权合成（权重经 Nacos 下发）
   ↓
呈现层：├─ 浏览模式（列表 + 筛选器）
        └─ 决策模式（一屏一道，"换一个"取下一位）
```

只有呈现层的两种模式是并列的，它们共用上游全部结果。

**模块深度是非对称的** —— 力气花在别人没有的东西上：

| 模块 | 深度 | 理由 |
|---|---|---|
| 食材匹配 | **深** | 唯一独有的信号；输出三档位 + 可执行的缺料清单，而不只是一个分数 |
| 质量分 | 中 | 威尔逊下界已是标准答案，再堆公式的边际收益极低 |
| 情境 | **浅** | 实测 112 道菜里 75 道是 2 人份、91 道不辣，维度高度集中，撑不起复杂模型 |
| 个性化 | 空实现 | `taste_tag` 87% 有值但极散（家常19、酱香9、清淡9…），硬算只会得到噪声 |

**冷启动顺带完成了一次收敛**：它不再是独立榜单，而是排序的一个输入。
`EsDslBuilder.buildRecallBody` 加了 `includePool` 开关，发现流传 `true` 就召回推荐池的新菜
（`rankLevel=1`），搜索仍传 `false` —— 旧行为一点没变。

详见 [`docs/discover-redesign.md`](docs/discover-redesign.md)。

### 7. 识菜：拍张照片，YOLO 认出图里有什么

```
用户上传 ≤9 张图片（dish-service 落盘，ASCII UUID 文件名）
   → Java VisionClient 把 mediaPath 列表发给 ai-service /api/v1/vision/dishes
   → Python YOLO 逐图检测：label + box + confidence（单图 GPU 4.4ms 级）
   → Java VisionAggregator 两级映射：t_vision_label（COCO 英文名显式配）
        → IngredientDirectory.resolveId()（覆盖自训后的中文名）
        → 都不中进 unmappedLabels
   → 前端候选网格（裁剪图 + 名称 + 数量 + 置信度，高置信默认勾选）
   → 用户勾选：映射成功的进 ingredientIds，表外名字进 freeIngredients
   → 一键带回工作台，走既有检索 / 生成链路
```

设计要点（详见 [`docs/yolo-vision-design.md`](docs/yolo-vision-design.md)，v2 代码已落地）：

- **Python 只回答"图里有什么"**，label → 食材 ID 的映射在 Java 侧做 —— 与推理边界一致，
  换权重（COCO 预训练 → 自训 best.pt）零代码改动
- **识别是辅助不是权威**：勾选机制天然兜底误识别，`unmappedLabels` 明示没认出来的
- 当前权重是 COCO 80 类预训练，**没有中式食材类**；89 类食材数据集训出 best.pt 后
  放到 `yolo_model/weights/` 即生效，这是 Phase 3 剩下的主要工作

---

## 关键设计决策与取舍

| # | 决策点 | 选择 | 放弃了什么 | 理由 |
|---|---|---|---|---|
| 1 | ES 客户端 | 手写 `RestClient` | Spring Data 的 Repository 抽象 | Spring Data ES 5.2.x 的目标是 ES 8.11.1，服务端 8.16.0 仍不是官方钉死的版本；手写 RestClient 零版本耦合（7.16.2 → 8.16.0 业务代码一行未改）；DSL 本来就要手写 |
| 2 | 生成调度 | 自建内存队列 | MQ 的可靠性 / 天然分布式 | MQ 表达不了「改顺序 / 取消排队」；单实例下自建更简单可控 |
| 3 | 并发度 | 默认 1 | 吞吐 | 本地单模型串行最稳，且实测不慢 |
| 4 | 营养来源 | 成分表本地计算为准，算不出来再退回 AI 估算并标注来源 | 纯 AI 估算 | 小模型估算偏差大（实测平均 181%）；覆盖率不足（< 80%）时不硬算，宁可标「估算」 |
| 5 | 调料用量 | Plan A：AI 只答「要哪几样」 | AI 全权给用量 | 小模型给用量会幻觉（料酒 300g）；油热量漏掉会让整道菜热量差近一倍 |
| 6 | 表外食材 | 候选表沉淀 + 3 次转正 | 直接进主数据 | 主数据是计算基准，垃圾输入会污染整个召回链路 |
| 7 | 排序口径 | 威尔逊下界 | 平均分 | 平均分挡不住「1 人打 5 分」压过「100 人打 4.6 分」 |
| 8 | 执行中取消 | 只做逻辑取消 | 立刻释放资源 | 需要流式 + 断连改造，收益不抵复杂度（明确记为优化项） |
| 9 | 重启恢复 | 标记 `INTERRUPTED` | 自动重新入队 | 重放会产生重复草稿，让用户自己决定更安全 |
| 10 | ID 传输 | 全部 `Long` → String | 少一次转换 | 雪花 ID 19 位超 JS 精度，不转就丢精度 |
| 11 | 推理位置 | Python ai-service（HTTP） | Java 内嵌 Spring AI，dish-service 直连 Ollama | 提示词、校验、营养算法只维护一份；模型侧迭代不用重新打包 Java。代价是多一个服务，生成与营养计算的同步路径变成硬依赖 |

### ⚠️ 雪花 ID 必须序列化成字符串

19 位数字超出 JS `Number` 的精度范围（`Number.MAX_SAFE_INTEGER` = 2^53-1 只有 16 位），
直接返回前端会精度丢失、详情页 404。

项目在 `common` 里用 `JsonAutoConfiguration` 把**所有 `Long` 统一序列化为字符串**，零配置生效。
前端因此**一律把 ID 当字符串**，禁止 `Number(id)`。

---

## 降级矩阵

| 依赖 | 挂掉后的表现 | 设计意图 |
|---|---|---|
| Redis | 注册/登录仍可用（布隆降级为「直接查库」） | 缓存不可用不能等于服务不可用 |
| Elasticsearch | 检索返回空列表 → 前端引导走 AI 生成 | 检索是加速器，不是主流程的必要条件 |
| Ollama | 生成任务标记失败，提示用户重试 | 失败要说人话，不无限重试烧资源 |
| MySQL | 无降级（唯一强依赖） | source of truth |

---

## 数据模型

详见 **[docs/database.md](docs/database.md)**（表结构、设计取舍、字段约束全在里面）。

一句话概览：

| 库 | 表 | 用途 |
|---|---|---|
| `project0914` | `t_user` | 用户（用户名唯一、BCrypt 密码、逻辑删除） |
| `project0914` | `t_ingredient` | 食材主数据（含每 100g 营养），归一化与营养计算的**基准** |
| `project0914` | `t_dish` | 菜品主表（草稿态与已发布态共表，`status` 区分） |
| `project0914` | `t_dish_ingredient` | 菜品-食材明细（含用量） |
| `project0914` | `t_dish_rating` / `t_dish_comment` | 评分（一人一票）/ 评论（可多条） |
| `project0914` | `t_dish_collect` | 收藏（恢复式） |
| `project0914` | `t_ai_generate_task` | AI 生成任务（状态机 + 去重 + 配额） |
| `project0914` | `t_ai_generation_record` | 生成记录（模型 / 提示词版本 / 校验结果 / 原始输出截断），换模型后老结果仍可解释 |
| `project0914` | `t_dish_push_log` | 推荐流推流日志（一人一菜一行，24 小时去重靠**查询条件**而非唯一键） |
| `project0914` | `t_es_sync_task` | 索引同步待办（退避重试 + 人工补偿入口） |
| `project0914` | `t_tag` / `t_dish_tag` | 标签 |
| `project0914` | `t_ingredient_candidate` | 表外食材沉淀（3 次转正） |
| `project0914` | `t_vision_label` | 识别 label → 食材映射（COCO 英文名显式配，如 `tomato` → 番茄；自训中文名走 `resolveId()`） |
| `project0914` | `t_vision_detect_log` | 识别流水（requestId / 图 / 检出项 / 用户最终勾选），用于归因与后续统计 |
| `project0914` | `t_user_ingredient_pref` | 用户食材偏好（已建表，待启用） |

**通用约定**：主键统一 `IdType.ASSIGN_ID`（雪花）、`@TableLogic` 逻辑删除、
时间字段 `@TableField(fill)` + `MetaObjectHandler` 自动填充。

---

## 接口清单

> 下表为**经网关**的路径。网关 `StripPrefix=2`，到服务内部会剥掉 `/api/account`、`/api/dish`。

<details>
<summary><b>认证与用户</b>（account-service）</summary>

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/account/auth/register` | 注册（布隆前置 + BCrypt） |
| `POST` | `/api/account/auth/login` | 登录，返回 JWT（2h） |
| `GET` | `/api/account/user/profile` | 当前用户信息（需登录） |

</details>

<details>
<summary><b>检索与 AI 生成</b>（dish-service）</summary>

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/dish/search` | 食材检索：召回结果 + 覆盖率 + 是否直出 + missReason |
| `POST` | `/api/dish/ai/vision/dishes` | **识菜**（multipart，≤9 张）→ 候选食材（置信度 / 数量 / 裁剪图）+ 未映射 label；勾选结果回填检索或生成 |
| `POST` | `/api/dish/ai/dish` | 提交生成 → 立即返回 `taskId` + 排队位置 |
| `GET` | `/api/dish/ai/tasks` | 我的任务列表（状态 / 位置 / 进度） |
| `GET` | `/api/dish/ai/queue` | 队列总览 |
| `PUT` | `/api/dish/ai/tasks/{taskId}/order` | 调整排队顺序（**只能在自己任务之间换位**） |
| `POST` | `/api/dish/ai/tasks/{taskId}/cancel` | 取消（排队中 / 执行中都走这里） |
| `DELETE` | `/api/dish/ai/tasks/{taskId}` | 删除任务记录（**仅限已结束的**，不碰菜品） |
| `GET` | `/api/dish/ai/dish/{dishId}` | 菜品详情（营养 / 步骤 / 大厨建议 / 评分 / 评论） |
| `POST` | `/api/dish/ai/dish/manual/preview` | **手写菜谱试算**：只按食材成分表算营养、只报「还差什么」，不入库 |
| `POST` | `/api/dish/ai/dish/manual` | **手写菜谱入库**：`source=3`、营养走本地计算；够发布门槛就自动发布，不够就留草稿并回原因 |
| `GET` | `/api/dish/ai/recommend` | 推荐流一屏（**旧入口**，新页面已改用 `/ai/discover`） |
| `POST` | `/api/dish/ai/recommend/{dishId}/view` | **已废弃**：浏览计数改由详情接口统一负责，该端点不再累加 |
| `POST` | `/api/dish/ai/recommend/{dishId}/interact?type=like\|collect\|cook` | 上报互动 |

</details>

<details>
<summary><b>发现流</b>（dish-service，2026-10-07 新增，取代推荐页与榜单页）</summary>

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/dish/ai/discover/browse` | 浏览模式：一屏列表，每条带匹配档位（齐活 / 差一点 / 缺得多）+ 缺料清单 + 推荐理由 |
| `GET` | `/api/dish/ai/discover/decide` | 决策模式：一次备一批，"换一个"从这批里依次取 |

两个端点**共用同一套排序结果**，只在"一次给几条"上分叉 —— 所以决策模式看到的第一道，
和浏览模式列表里排第一的必然是同一道。

公共参数：`ingredientIds`、`ingredientNames`（食材表之外的自由输入）、
`maxCookTimeMin`、`maxDifficulty`、`serving`；
浏览模式另加 `page` / `size`，决策模式另加 `offset`。
</details>

<details>
<summary><b>互动 / 我的</b></summary>

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` / `DELETE` | `/api/dish/ai/dish/{dishId}/collect` | 收藏 / 取消收藏 |
| `POST` | `/api/dish/ai/dish/{dishId}/rating` | 评分（一人一票） |
| `POST` | `/api/dish/ai/dish/{dishId}/comment` | 评论 |
| `GET` | `/api/dish/ai/my/dishes` | 我的菜品 |
| `GET` | `/api/dish/ai/my/collects` | 我的收藏 |

</details>

<details>
<summary><b>发布 / 榜单 / 运维</b></summary>

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/dish/ai/dish/{dishId}/publish` | 发布进找菜库 |
| `POST` | `/api/dish/ai/dish/{dishId}/unpublish` | 撤回（同步删索引） |
| `GET` | `/api/dish/ai/dish/{dishId}/publish-check` | 发布前校验（预检） |
| `GET` | `/api/dish/ai/rank?type=hot\|top\|latest\|cold` | 榜单（`cold` = 冷启动榜，按综合得分排，新菜从这里冒头） |
| `POST` | `/api/dish/ai/es/reindex` | 🔒 全量重建索引 |
| `POST` | `/api/dish/ai/admin/dish/{dishId}/unpublish` | 🔒 管理员强制下架 |
| `GET` | `/api/dish/ai/ingredients/candidates` | 🔒 待审食材候选清单（按热度倒序） |
| `POST` | `/api/dish/ai/ingredients/candidates/approve` | 🔒 审核通过 → 转正成正式食材 |
| `POST` | `/api/dish/ai/ingredients/candidates/reject` | 🔒 审核拒绝（理由必填） |
| `GET` | `/api/dish/ai/ingredients/candidates/reviewed` | 🔒 已审核历史（谁、何时、为什么） |
| `GET` | `/api/dish/ai/es/sync/status` | 🔒 索引同步积压统计（待同步 / 完成 / 待重试 / 已放弃） |
| `GET` | `/api/dish/ai/es/sync/tasks?status=&limit=` | 🔒 同步任务清单（排查"为什么索引里没有这道菜"） |
| `POST` | `/api/dish/ai/es/sync/retry` | 🔒 手动补偿失败任务（不等下一次退避） |
| `POST` | `/api/dish/ai/admin/nutrition/recompute` | 🔒 按食材成分表整库重算营养（返回偏差报告） |

> ⚠️ 运维接口的前缀**不统一**：多数是 `/ai/...`，只有"强制下架"和"营养重算"是 `/ai/admin/...`。
> 前端曾经因此把 `/ai` 整段漏掉，6 个管理接口全部打到空路径上
> （症状是管理台整页报错，却看不出是 URL 写错）—— 路径以 Controller 上的
> `@RequestMapping` 为准，别照着邻居猜。

🔒 = 需要管理员角色（`t_user.role = 1`）。判定在业务服务内完成后端拦截器**回源查库**得出，
不信任请求头里的任何角色字段 —— 请求头可以伪造，权限判断不能信它。

</details>

<details>
<summary><b>推理服务 ai-service</b>（Python，内部接口，不经网关，仅 dish-service 调用）</summary>

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/generate` | 菜谱生成（同步）：食材 / 约束 / 主数据快照 / 参考菜谱 → 草稿 + 校验结果 + 本地营养 |
| `POST` | `/api/v1/nutrition` | 批量营养计算：`directory` + `items[]` → 每道菜每人份营养与覆盖率 |
| `POST` | `/api/v1/vision/dishes` | 图片识别（YOLO）：`images[]`（mediaPath）→ 每图的 label + box + confidence；label → 食材 ID 的映射在 Java 侧 |
| `GET` | `/health` | 探活：返回模型名、提示词版本、Ollama 是否可达 |
| `GET` | `/api/v1/health` | 同上（契约保留的路径） |

服务只监听 `127.0.0.1`。dish-service 会发送 `X-Ai-Token` 请求头，但 ai-service 目前还没有校验它（见「已知限制」）。

</details>

---

## 目录结构

```
ai-ingredient-kitchen/
├── pom.xml                    # 自建父 pom（版本统一管理）
├── common-service/            # 公共模块：Result / JwtUtil / 全局序列化
├── account-service/           # 账号服务 :8081
├── dish-service/              # 菜品服务 :8082（业务核心）
│   └── src/main/java/.../
│       ├── ai/                # 任务队列 / 生成编排 / 远程 ai-service 客户端（生成 + 营养 + 识菜）
│       ├── es/                # ES DSL 组装与查询
│       ├── discover/          # 发现流：信号层 → 排序层 → 浏览 / 决策两种呈现
│       ├── recommend/job/     # 冷启动晋级与算分（后台定时任务，不对用户呈现）
│       └── service/           # 检索 / 发布 / 互动 / 榜单（旧，保留做对照）
├── ai-service/                # 推理服务 :8090（Python）：提示词 / 调模型 / 校验 / 营养计算 / 识菜
│   ├── app.py                 # Flask 应用工厂与入口
│   ├── ai_service/            # routes / generator / validator / nutrition / ollama_client / vision
│   ├── prompts/               # 系统提示词（字段定义须与 dish-service 的 DishDraft 一致）
│   ├── yolo_model/weights/    # yolov8n.pt（COCO 预训练；自训 best.pt 放这里即生效）
│   └── tests/                 # pytest
├── gateway/                   # 网关 :7000
├── frontend-vue/              # Vue3 前端（Vite 6）
├── nacos-config/              # Nacos 配置留档副本
├── es/                        # 索引 mapping / 测试数据 / 验证脚本
├── sql/                       # 建表语句（结构，不含数据）
└── docs/                      # 架构文档 / 数据库说明 / 发现流重构方案 / 面试要点
```

---

## 本地运行

### 依赖组件

| 组件 | 地址 | 备注 |
|---|---|---|
| MySQL 8 | `127.0.0.1:3306` | 库名 `project0914`，编码 `utf8mb4` |
| Redis | `127.0.0.1:6379` | 供 Redisson 布隆过滤器使用 |
| Nacos | `127.0.0.1:8848` | 配置中心 + 注册中心 |
| Elasticsearch | `127.0.0.1:9200` | Docker 容器 `es`（8.16.0，单节点、已关 security、纯 http 免密） |
| Ollama | `127.0.0.1:11434` | 需先 `ollama pull` 对应模型 |
| ai-service | `127.0.0.1:8090` | Python 推理服务，依赖 Ollama；dish-service 的 `ai.generate.mode=remote` 时必须先启动 |

### 启动顺序

```
MySQL / Redis / Nacos / Elasticsearch / Ollama
        → ai-service → gateway → account-service → dish-service → 前端
```

#### ai-service（Python）

```bash
cd ai-service
pip install -r requirements.txt          # 首次（Flask 等基础依赖）
# 识菜额外依赖：torch/torchvision 是 cu124 定制版，PyPI 没有，用本地 whl 装；
# 权重已随仓库在 yolo_model/weights/yolov8n.pt，无需下载
pip install <torch-2.6.0+cu124-cp312-win_amd64.whl> <torchvision-0.21.0+cu124-cp312-win_amd64.whl>
pip install ultralytics==8.4.177
ollama pull gemma4:e2b                   # 首次
python app.py                            # 开发模式
# Linux / macOS 常驻：gunicorn -w 2 --threads 8 -b 127.0.0.1:8090 app:app

curl http://127.0.0.1:8090/health        # 确认 ollamaReachable 为 true
```

识菜还要：执行 `sql/migration-07-vision.sql`（建 `t_vision_label` / `t_vision_detect_log`），
并在 Nacos `dish-service.yaml` 配 `ai.vision.*`（上传目录 / 单次张数上限 / 读超时）。

配置在 `ai-service/config.yaml`（Ollama 地址、模型、温度、重试次数、超时、并发上限），
可用环境变量 `AI_SERVICE_CONFIG` 指定其他路径。测试：`pytest`（在 `ai-service/` 下运行）。

### ⚠️ 命令行启动的两条铁律

**1. 必须显式传 `--server.port`**

某些运行环境会注入 `SERVER__PORT` 环境变量，Spring Boot 的宽松绑定会把它当成
`server.port` 且**优先级高于配置文件**，服务于是去抢宿主任意端口
（报 `Port 53482 was already in use`，且端口号每次都不同）。
命令行参数优先级最高，写死即解决。建议同时 `unset SERVER__PORT SERVER__HOST`。

**2. 命令行必须加 `-Dfile.encoding=UTF-8`**

JDK 17 在 Windows 中文环境下默认编码仍是 GBK（默认改 UTF-8 是 JDK 18 的 JEP 400）。
Nacos 上的配置带中文注释，用 GBK 去解 UTF-8 字节会失败；而这个异常被 `optional:` 前缀吞掉，
只留一行 ERROR，最终暴露出来的是 `Failed to determine a suitable driver class` ——
看起来完全是数据源问题。

```bash
java -Dfile.encoding=UTF-8 -jar dish-service/target/dish-service-0.0.1-SNAPSHOT.jar --server.port=8082
```

> IDEA 启动默认带 `-Dfile.encoding=UTF-8`，所以 IDEA 里不会遇到这个问题。

**3. 打包注意**

`package` 前先 `clean`，且**先停掉正在运行的服务**；
单独打业务模块要带 `-am`，否则 fat jar 会嵌进本地仓库里的旧 `common-service`，
症状是 `Long` 不再序列化成字符串、前端雪花 ID 精度丢失、详情页 404。

---

## 评估与验证

改进项不能只说「做完了」，得留下能复现的数字。测试集固定（`es/eval/testset-ranking.json`，162 组，
按食材组合哈希划分 validation / test），脚本各自独立、产物全部入库存档，**结论按数据推导，不写预设立场**。

| 实验 | 脚本 | 产物 | 一句话结论 |
|---|---|---|---|
| 排序对照（legacy vs satisfaction） | `es/eval/run-ranking-experiment.py` | `results-ranking.{md,json}` | test 划分 P@5 0.339 → **0.467**，最优缺料排第 1 0.569 → **0.639**，约束违反 0/0 |
| 检索 / 生成协同 | `es/eval/run-synergy-experiment.py` | `results-synergy.{md,json}` | 仍需调模型的比例 **93.2%**（严格口径）/ 80.2%（宽松）；检索 P50 45.5ms vs 生成 P50 144.8s，**约 3183 倍** |
| 生成质量（按生成记录统计） | `es/eval/run-generation-experiment.py` | `results-generation.{md,json}` | 小样本：格式通过 0.667、约束通过 1.0、一次通过 0.5；失败那条是**格式**问题不是约束问题 |
| 系统负载（顺序 vs 并发） | `es/eval/run-load-experiment.py` | `results-load.{md,json}`（另存 `results-load-timeout240.*` 作证据） | 队列不是瓶颈（排队 ≤239ms）；并发组墙钟 391s < 耗时之和 651s（比值 1.66，**存在重叠**） |
| 营养本地计算对照 | `ai-service/tests/test_nutrition.py`（原 `dish-service/verify-p2-nutrition.py` 已删除） | pytest 单测，ai-service 共 **52** 项（2026-10-16 通过，含识菜 7 项）；dish-service **116** 项 | 历史结论：小模型营养估算平均偏差 **181.79%**、最大 825.93% → 所以本地按成分表算，并把 AI 估算标成「估算」 |
| 手写菜谱重名拦截 | `dish-service` 的 `ManualDishWriterTest`（原 `verify-manual-dish.py` 已删除） | 12 个单元测试通过 | 同一创作者的第二道同名菜在**落库前**被拦下并给人话（不再是「服务器开小差了」） |
| 前端端到端（真实浏览器点按） | `frontend-vue/verify-vue.py` | **253 断言 / 0 失败** | 六个页面含管理台 + 手写菜谱（9i）+ 推荐页（9j）：断言前端**实际发出的请求体**（patch `fetch` 抓载荷），曾借此抓出管理台 10 个接口漏掉 `/ai` 前缀的真 bug |
| 人工可操作性（盲评材料 + 汇总口径） | `es/eval/human-eval-kit.py` | `es/eval/human-eval/`（`blind-sheet.md` / `ratings-template.csv` / `answer-key.csv`，`score` 出 `human-eval-report.md`） | 工具已就绪并按假评分表验证过汇总与加权 kappa；**现有 7 条样本两臂各自困在一个提示词版本上，不能直接对比**，要下结论得补 B 臂样本 + ≥2 名真人评分 |

几条必须一起交代的口径（细节在各报告里）：

- **失败样本不剔除**，且失败的归因要分档：格式不合格 / 约束不合格是两类问题，混在一起看不出该改哪边。
- `OLLAMA_NUM_PARALLEL` 是**设置值不是实测值**（环境变量只对重启后的 Ollama 生效）——报告里如实标注，
  要断言 Ollama 侧行为得另加「直接对 `/api/generate` 并发压测」的对照。
- 小样本（负载 2+2 个任务）只够验证观测口径，**不足以给稳定的 P95**；写进论文需要扩规模重复多次。
- **指标绑在"菜库快照"上**：排序/协同实验当时是 32→34 道已发布菜；现在库里已是 149 道（110 已发布，含验证脚本自己生成的），
  所以 `results-ranking.*` 里的 P@5 **不能拿来和今天的数据对比**。要重跑实验，先记下当下的
  `SELECT COUNT(*) FROM t_dish WHERE deleted=0 AND status=1` 再跑，报告里也要写清快照。
- 营养偏差、生成耗时等指标受机器状态影响大（冷启动 + 40960 上下文 + GPU 争用），报告记录了运行时的固定条件。
- 人工评价**不能只由生成模型自己完成**：`human-eval-kit.py build` 出盲评材料（序号打乱，是哪条策略只记在**不给评价者看的** `answer-key.csv` 里），
  `score` 汇总两臂均分并算评价者之间的**线性加权 Cohen's kappa**。一条必须说清的混杂：**现有记录里 A 臂（参考增强）全在 `prompt_version=076b89e0`、
  B 臂（直接生成）全在 `1379896b`** —— 两臂差的既是"有没有参考菜谱"也是"提示词版本"，跨版本比较无效；有效对比必须在**同一个提示词版本**下补齐两臂。

### 要跑人工评价时：怎么补齐 B 臂（三步）

现有 7 条可用样本两臂都不到 5 条，且各自困在一个提示词版本上，所以**要下结论必须重新生成 B 臂**。
`es/eval/fill-arm-b.py` 是这件事的执行脚本（预检 → 提交 → 等终态 → 回读确认这批真的是 B 臂）：

```bash
# 1) 先备份 Nacos，再把 dish-service.yaml 的 ai.generate.enhance-enabled 改成 false 并推送
python nacos-config/backup-nacos.py
#    推送方式：POST http://127.0.0.1:8848/nacos/v1/cs/configs
#    body: dataId=dish-service.yaml & group=DEFAULT_GROUP & tenant= & type=yaml & content=<整个文件内容>
#    内容必须用 UTF-8 无 BOM 读（PowerShell 的 Set-Content -Encoding UTF8 会写 BOM，Nacos 端会解析失败）

# 2) 重启 dish-service —— Nacos 配置改了不重启不生效
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*dish-service*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
java "-Dfile.encoding=UTF-8" -jar dish-service\target\dish-service-0.0.1-SNAPSHOT.jar --server.port=8082

# 3) 补 5 条 B 臂样本（约 25~45 分钟，单次生成 2~8 分钟）
python es/eval/fill-arm-b.py 5
```

跑完**务必把 `enhance-enabled` 改回 `true` 并再重启一次**（否则后面再也生成不出 A 臂）。
脚本的预检、登录、端口检查已实测；提交与回读两段要等真正补样本时才会跑到 —— 第一次跑如果报错，
先看它打印的 mysql stderr 原文，不要只看"没输出"。

补齐之后：`python es/eval/human-eval-kit.py build` 出材料 → 找 ≥2 人各填一份 `ratings-<名字>.csv` →
`python es/eval/human-eval-kit.py score` 出 `human-eval-report.md`。

---

## 已知限制与技术债

| 类别 | 问题 | 影响 | 处理建议 |
|---|---|---|---|
| 功能缺口 | ~~AI 自补的食材未过归一化~~ **已修（2026-10-02）** | 原影响：该食材进不了按 ID 的召回、营养只能标「估算」、还会被误记进候选表 | `DishDraftWriter` 现在在"用户勾选列表"之外回退 `IngredientDirectory.resolveId()`（正式名 + 别名 + 去空白/大小写）；历史欠账由 `sql/migration-05-ingredient-id-backfill.sql` 补（实测 6 行 → 0 行）；`IngredientDirectory` 改两遍加载，正式名优先于别名，冲突时打 warn |
| 运维 | ~~推荐页整页 500（「没能加载推荐 / 再试一次」）~~ **已修（2026-10-02）** | 根因：冷启动的推流日志表 `t_dish_push_log` 不在库里，而推荐候选查询里有对它的 `NOT EXISTS` 24 小时去重子查询 → `Table 'project0914.t_dish_push_log' doesn't exist`。`t_dish` 的冷启动字段与 `idx_rank_level_score` 索引都在，**只缺这一张表**，所以表现成"列表页整页打挂"而不是某个功能降级 | 补 `sql/migration-06-recommend-push-log.sql`（`CREATE TABLE IF NOT EXISTS`，可重复执行）。教训：**新功能引入的新表必须落成 `sql/` 下的脚本**，否则换库/恢复备份时表会静默消失，而代码只在真正访问到它时才炸 |
| 功能缺口 | 账号中心未做（改昵称 / 改密码 / 旧 token 失效） | 用户无法自主维护账号 | 需动网关（token 带 `iat` + 比对 Redis 改密时间戳），**必须单独做** |
| 架构 | 生成队列是**单实例内存队列** | 无法水平扩容；重启会中断任务 | 迁 Redis ZSet 做分布式队列 |
| 架构 | 前端用**轮询**（1.5s）而非 SSE | 有无效请求开销 | 改 SSE + Ollama 流式输出 |
| 安全 | 业务服务不能被限制为"只允许网关访问" | 直连 8082 伪造 `X-User-Id` 可冒充普通用户（**但拿不到管理员权限**，权限回源查库） | 部署时用网络策略/安全组只放行网关 |
| 性能 | 应用侧并发 2，而 Ollama `NUM_PARALLEL=1` | 队列不是瓶颈（实测排队 ≤239ms），瓶颈是单次模型调用（P50 数分钟） | 实测把 Ollama 提到 2 会爆 8G 显存回退 CPU（单请求 2.5s→7.4s），需先把上下文从 40960 调小；用户已决定**维持并行 1** |
| 性能 | 模型调用超时阈值设小会把「并发下变慢」误判成「卡死」 | 240s 时一次 2 vs 4 的实验 6/6 任务全灭 | 已提到 **600s**；阈值必须大于并发下的单任务上限，否则测的是阈值不是系统 |
| 运维 | 数据源 / Redis 密码是 `root/root` 占位；Seata 已建库建表但未部署 | 不能直接上生产 | 配置外置 + 部署 Seata |
| 一致性 | ES 与 MySQL 之间是最终一致 | 有秒级窗口 | `AFTER_COMMIT` 立即尝试一次 + `t_es_sync_task` 待办表（退避 5/15/45/120s，5 次后放弃）+ 管理员手动补偿 + 每日 04:00 全量核对；实测停掉 ES 后撤回仍成功，恢复后旧任务**只会删不会写回** |
| 验证 | 发现流（2026-10-07 新增）**尚未端到端验证** | 后端编译通过、前端语法已校验，但没启服务跑过 | 推 `discover.*` 到 Nacos（`nacos-config/dish-service.yaml` 已留档未推）→ 启 dish-service + ES → 打开 `/discover`。旧的 `/rank` 与 `RankView.vue` 保留做对照，确认后删除 |
| 架构 | 旧榜单（热门 / 高分 / 最新 / 冷启动）**仍与发现流并存** | 两套排序口径同时在库里，容易混淆 | 这是**有意保留**的：重构前后对比本身就是答辩素材。确认发现流没问题后删 `/rank` 路由 + `RankView.vue` + `rank.css` + 顶栏那一项 |
| 安全 | ai-service 不校验 `X-Ai-Token` | dish-service 已发送该请求头，Python 端没有读取校验；目前只监听 `127.0.0.1` | 对外暴露前补上校验（Nacos 的 `ai.remote.token` 已预留） |
| 运维 | Nacos 上的 `dish-service.yaml` 需手动同步 | 仓库里的 `nacos-config/` 只是留档副本，改动不会自动推送到 Nacos 服务端 | 改完后按「要跑人工评价时」一节的方式推送，并重启 dish-service |
| 可用性 | ai-service 挂掉时菜谱生成失败；手写菜谱营养预览降级为「不可信」 | 生成与营养计算的同步路径对 ai-service 有硬依赖 | 预览降级已实现；生成链路可考虑排队重试 |
| 功能缺口 | 识菜权重是 COCO 80 类预训练，**认不出中式食材** | 识菜页只能演示流程，`t_vision_label` 里配了的 COCO 类（如 bowl/broccoli）能映射 | 自建 89 类食材数据集微调，`best.pt` 放进 `ai-service/yolo_model/weights/` 即生效；这是 Phase 3 剩余主要工作 |
| 验证 | 识菜链路刚落地，**尚未端到端跑过**（编译 + 单测通过） | 真实上传 / 映射 / 回填没验证 | 起 ai-service（含 YOLO 依赖）+ dish-service → 打开 `/vision` 上传图片走全程 |

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 完整技术文档：全局设计 + 核心链路 + 决策取舍 |
| [docs/database.md](docs/database.md) | 数据库表结构详解（字段、约束、设计理由） |
| [docs/ai-kitchen.md](docs/ai-kitchen.md) | AI 做菜链路的细节实现 |
| [docs/interview-prep.md](docs/interview-prep.md) | 面试讲解要点 |
| [es/dish-search.md](es/dish-search.md) | Elasticsearch 检索方案详解 |
| [docs/ai-service-split.md](docs/ai-service-split.md) | 推理拆分到 Python ai-service 的设计与接口契约 |
| [docs/yolo-vision-design.md](docs/yolo-vision-design.md) | 视觉识别（YOLO）设计，**v2：代码已落地**（Python vision.py + Java VisionClient/Service + 前端识菜页；v1 的 4 个待确认点已拍板，见 §12） |

---

## 设计原则备忘

这个项目在实现过程中沉淀了几条「踩过才知道」的原则：

1. **缓存不可用 ≠ 服务不可用** —— 每个外部依赖都要想清楚挂掉时怎么办
2. **数据库唯一索引是并发防重的最终保障**，Service 层的先查后插兜不住
3. **source of truth 只能有一个** —— MySQL 是权威，ES 是副本，副本挂了要能降级
4. **不要在事务体里直接写外部系统** —— 用 `@TransactionalEventListener(AFTER_COMMIT)`
5. **护栏要在应用层做** —— 小模型的输出不能只靠提示词约束
6. **规则要确定性** —— 直出判定同输入必须同输出，避免「这次搜到了下次没搜到」
