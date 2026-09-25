# AI 灵感厨房 · ai-ingredient-kitchen

> 挑几样食材，系统先在菜库里找现成的 —— 命中高质量的做法直接返回；
> 没有就让 AI 现场生成一道。生成结果经校验后可发布进公共菜库，
> 被搜索、被排名、被收藏。

**用户既是消费者也是生产者**：AI 生成的内容会沉淀回菜库，菜库越厚、直出率越高、AI 调用越少。

<p>
  <img alt="Java" src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.2.4-6DB33F?logo=springboot&logoColor=white">
  <img alt="Spring Cloud" src="https://img.shields.io/badge/Spring%20Cloud-2023.0.1-6DB33F?logo=spring&logoColor=white">
  <img alt="Nacos" src="https://img.shields.io/badge/Nacos-2.3.2-2F7DD1">
  <img alt="Elasticsearch" src="https://img.shields.io/badge/Elasticsearch-7.16-005571?logo=elasticsearch&logoColor=white">
  <img alt="Redis" src="https://img.shields.io/badge/Redis-Redisson%203.26-DC382D?logo=redis&logoColor=white">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white">
  <img alt="Ollama" src="https://img.shields.io/badge/LLM-Ollama%20local-000000">
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

---

## 技术形态

**Spring Cloud 微服务（4 模块）+ 双版本前端（原生 / Vue3）**

| 模块 | 端口 | 职责 | 关键内容 |
|---|---|---|---|
| `common-service` | — | 跨服务技术能力 | `Result<T>`、`BusinessException`、`JwtUtil`、全局 `Long → String` 序列化 |
| `account-service` | 8081 | 账号与认证 | 注册 / 登录、BCrypt、Redisson 布隆过滤器、JWT 签发 |
| `dish-service` | 8082 | **业务核心** | AI 生成队列、检索、发布、互动、榜单、ES 同步 |
| `gateway` | 7000 | 统一入口 | JWT 鉴权过滤器、路由、CORS、`DedupeResponseHeader` |
| `frontend-vue` | 5500 | 前端（Vue3 版） | 5 个页面，`/api` 反向代理到网关 |
| `order-service` | — | 预留未放开 | 父 pom 中处于注释状态 |

**依赖方向单向**：`account / dish / gateway → common`。
`common` 只放技术能力，**领域实体一律不进 common**（避免变成"什么都往里塞"的垃圾桶）。

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
| Elasticsearch | 7.16.2 | 见下方说明 |
| Ollama | `gemma3:4b` 级别本地模型 | 本地推理，一道菜 12~26 秒 |

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
| ③ 应用层校验 + 重试 | 逐字段校验类型/范围，不合格把**具体原因**拼回提示词重试 1 次 | 兜住小概率 |

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

---

## 关键设计决策与取舍

| # | 决策点 | 选择 | 放弃了什么 | 理由 |
|---|---|---|---|---|
| 1 | ES 客户端 | 手写 `RestClient` | Spring Data 的 Repository 抽象 | Spring Data ES 5.2.x 只兼容 ES 8.11.1，本机 7.16.2 不保证；DSL 本来就要手写 |
| 2 | 生成调度 | 自建内存队列 | MQ 的可靠性 / 天然分布式 | MQ 表达不了「改顺序 / 取消排队」；单实例下自建更简单可控 |
| 3 | 并发度 | 默认 1 | 吞吐 | 本地单模型串行最稳，且实测不慢 |
| 4 | 营养来源 | AI 估算 + 标注「仅供参考」 | 本地精确计算 | 食材可自由输入，主数据不可能全覆盖；本地计算降级为后台校验 |
| 5 | 调料用量 | Plan A：AI 只答「要哪几样」 | AI 全权给用量 | 小模型给用量会幻觉（料酒 300g）；油热量漏掉会让整道菜热量差近一倍 |
| 6 | 表外食材 | 候选表沉淀 + 3 次转正 | 直接进主数据 | 主数据是计算基准，垃圾输入会污染整个召回链路 |
| 7 | 排序口径 | 威尔逊下界 | 平均分 | 平均分挡不住「1 人打 5 分」压过「100 人打 4.6 分」 |
| 8 | 执行中取消 | 只做逻辑取消 | 立刻释放资源 | 需要流式 + 断连改造，收益不抵复杂度（明确记为优化项） |
| 9 | 重启恢复 | 标记 `INTERRUPTED` | 自动重新入队 | 重放会产生重复草稿，让用户自己决定更安全 |
| 10 | ID 传输 | 全部 `Long` → String | 少一次转换 | 雪花 ID 19 位超 JS 精度，不转就丢精度 |

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
| `project0914` | `t_tag` / `t_dish_tag` | 标签 |
| `project0914` | `t_ingredient_candidate` | 表外食材沉淀（3 次转正） |
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
| `POST` | `/api/dish/ai/dish` | 提交生成 → 立即返回 `taskId` + 排队位置 |
| `GET` | `/api/dish/ai/tasks` | 我的任务列表（状态 / 位置 / 进度） |
| `GET` | `/api/dish/ai/queue` | 队列总览 |
| `PUT` | `/api/dish/ai/tasks/{taskId}/order` | 调整排队顺序（**只能在自己任务之间换位**） |
| `POST` | `/api/dish/ai/tasks/{taskId}/cancel` | 取消（排队中 / 执行中都走这里） |
| `DELETE` | `/api/dish/ai/tasks/{taskId}` | 删除任务记录（**仅限已结束的**，不碰菜品） |
| `GET` | `/api/dish/ai/dish/{dishId}` | 菜品详情（营养 / 步骤 / 大厨建议 / 评分 / 评论） |

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
| `GET` | `/api/dish/ai/rank?type=hot\|top\|latest` | 榜单 |
| `POST` | `/api/dish/ai/es/reindex` | 全量重建索引（**当前无权限校验，对外前必须加**） |
| `POST` | `/api/dish/ai/ingredients/promote` | 食材候选转正 |
| `GET` | `/api/dish/ai/ingredients/candidates` | 候选食材列表 |

</details>

---

## 目录结构

```
ai-ingredient-kitchen/
├── pom.xml                    # 自建父 pom（版本统一管理）
├── common-service/            # 公共模块：Result / JwtUtil / 全局序列化
├── account-service/           # 账号服务 :8081
├── dish-service/              # 菜品服务 :8082（业务核心）
│   └── src/main/java/.../es/  # ES DSL 组装与查询
├── gateway/                   # 网关 :7000
├── frontend-vue/              # Vue3 前端（Vite 6）
├── nacos-config/              # Nacos 配置留档副本
├── es/                        # 索引 mapping / 测试数据 / 验证脚本
├── sql/                       # 建表语句（结构，不含数据）
└── docs/                      # 架构文档 / 数据库说明 / 面试要点
```

---

## 本地运行

### 依赖组件

| 组件 | 地址 | 备注 |
|---|---|---|
| MySQL 8 | `127.0.0.1:3306` | 库名 `project0914`，编码 `utf8mb4` |
| Redis | `127.0.0.1:6379` | 供 Redisson 布隆过滤器使用 |
| Nacos | `127.0.0.1:8848` | 配置中心 + 注册中心 |
| Elasticsearch | `127.0.0.1:9200` | 7.16.2 解压版 |
| Ollama | `127.0.0.1:11434` | 需先 `ollama pull` 对应模型 |

### 启动顺序

```
MySQL / Redis / Nacos / Elasticsearch / Ollama
        → gateway → account-service → dish-service → 前端
```

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

## 已知限制与技术债

| 类别 | 问题 | 影响 | 处理建议 |
|---|---|---|---|
| 功能缺口 | AI 自补的食材未过归一化 | 该食材进不了倒排索引，营养只能标「估算」 | 生成后补一遍 `IngredientResolver` |
| 功能缺口 | 账号中心未做（改昵称 / 改密码 / 旧 token 失效） | 用户无法自主维护账号 | 需动网关（token 带 `iat` + 比对 Redis 改密时间戳），**必须单独做** |
| 架构 | 生成队列是**单实例内存队列** | 无法水平扩容；重启会中断任务 | 迁 Redis ZSet 做分布式队列 |
| 架构 | 前端用**轮询**（1.5s）而非 SSE | 有无效请求开销 | 改 SSE + Ollama 流式输出 |
| 性能 | 本地单模型、并发度 1 | 全局吞吐瓶颈 | 换更大 GPU / 模型，或接云端 API |
| 安全 | `es/reindex` 与食材转正接口**无权限校验** | 对外暴露可被滥用 | 对外前加管理员校验 |
| 运维 | 数据源 / Redis 密码是 `root/root` 占位；Seata 已建库建表但未部署 | 不能直接上生产 | 配置外置 + 部署 Seata |
| 一致性 | ES 与 MySQL 之间是最终一致 | 有秒级窗口 | 已用 `AFTER_COMMIT` + 每日全量兜底 |

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 完整技术文档：全局设计 + 核心链路 + 决策取舍 |
| [docs/database.md](docs/database.md) | 数据库表结构详解（字段、约束、设计理由） |
| [docs/ai-kitchen.md](docs/ai-kitchen.md) | AI 做菜链路的细节实现 |
| [docs/interview-prep.md](docs/interview-prep.md) | 面试讲解要点 |
| [es/dish-search.md](es/dish-search.md) | Elasticsearch 检索方案详解 |

---

## 设计原则备忘

这个项目在实现过程中沉淀了几条「踩过才知道」的原则：

1. **缓存不可用 ≠ 服务不可用** —— 每个外部依赖都要想清楚挂掉时怎么办
2. **数据库唯一索引是并发防重的最终保障**，Service 层的先查后插兜不住
3. **source of truth 只能有一个** —— MySQL 是权威，ES 是副本，副本挂了要能降级
4. **不要在事务体里直接写外部系统** —— 用 `@TransactionalEventListener(AFTER_COMMIT)`
5. **护栏要在应用层做** —— 小模型的输出不能只靠提示词约束
6. **规则要确定性** —— 直出判定同输入必须同输出，避免「这次搜到了下次没搜到」
