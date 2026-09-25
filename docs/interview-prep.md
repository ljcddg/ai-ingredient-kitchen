# AI 灵感厨房 · 面试读码指南

> 用途：面试官沿简历深挖时，每条技术点的「代码证据 + 口述 + 雷区」。所有结论以代码为准，不凭印象。
> 项目根包 `com.apesource`，dish-service 端口 8082，account-service 8081，gateway 7000。

---

## 0. 电梯陈述（30s 串联）

> 我的项目是 AI 灵感厨房：用户输入手边食材，系统先用 ES 做食材召回 + 应用层精排，命中高质量菜就直接返回；否则走本地模型异步生成（taskId + 轮询）。我用网关做统一认证、布隆防穿透并做了 Redis 降级，用事务事件保证 MySQL 到 ES 的索引一致，解决了本地模型慢、输出不可控、缓存与索引不一致几个核心问题。

---

## 1. 食材召回与精排

### 1.1 两路检索（主数据 ID / 自由输入）
- **简历原话**：区分主数据 ID 与自由输入两种检索路径。
- **代码**：`DishSearchService.normalizeIngredients()` 把所选食材拆成 `ids` / `names` 两个**互斥桶**；`EsDslBuilder.buildIngredientClause()` 里 ID 路查 `ingredientIds`(long)、名字路查 `ingredientNames`(keyword)，因为 ES `terms` 一次只能查一个字段、且类型不同（long vs 字符串）无法塞同一数组。
- **口述**：用户点选的食材有主数据 ID，召回用 ID 匹配；手敲的（可能不在主数据里）只能用名字匹配。两路字段类型不同，所以查询分开拼。用 ID 更精确，还能归一去重。
- **雷区**：ES `terms` 默认 **OR**；全含必须逐项拼 `must`；软匹配用 `should` + `minimum_should_match`（且必须放在 `bool` 上，塞进 terms 会被当字段名静默退化）。

### 1.2 ID ↔ 名称对应
- **三层机制**：
  1. 静态主数据：`t_ingredient`(id/name/alias) + 前端 `frontend-vue/src/lib/ingredients.js`（88 种，id 必须与库一一对应）；
  2. 后端归一：`IngredientResolver.resolve()` 全读进内存建 `byId`/`byName`，自由输入按标准名/别名归一，未命中保留原文、ID 为 null、只 warn 不报错；
  3. ES 填充：`DishEsSyncService` 从 `t_dish_ingredient` 拆字段——`ingredient_id≠null` 进 `ingredientIds`，所有名字进 `ingredientNames`。
- **坑**：表外食材 `ingredient_id=NULL`，往 long 数组写会整条文档写入失败 → 代码 `if (ing.getIngredientId() == null) continue;` 剔除，但名字路兜住；前端加了食材但库没加 → 静默丢菜只有 warn。

### 1.3 软搜索澄清（无雷，但需讲清）
- `DishSearchRequest.allowPartial` 默认 `Boolean.FALSE`；`EsDslBuilder` 有 `allowPartial=true` 分支（`should`+`minimum_should_match` 阈值 60%），但**前端未启用、线上走硬匹配（must 全含）**。
- 简历一字未提软搜索，无雷。讲法：硬匹配为主 + 软匹配是默认关闭的备选开关。

### 1.4 直出判定（⚠️ 最高危雷区）
- **条件**（`DishSearchService.isDirectEligible()`）：**覆盖率 ≥ 0.8 且 评价人数 ≥ 10 且 威尔逊分 ≥ 0.84**（阈值在 `EsProperties.Judge`）。
- **关键纠正**：搜索和生成是**两个独立功能**，用户搜不到才主动点"生成"；"减少无效模型调用"是产品流程层面的省，不是代码内 if-else 自动决策。

---

## 2. AI 生成异步化

### 2.1 提交返 taskId + 轮询
- `DishGenerateService.submit()`：校验 + 食材归一 → 任务**落库**(`ai_generate_task`, `status=PENDING`) → 投内存队列 → 立刻返 `taskId` + 排队位置（毫秒级）。前端轮询 `GET /ai/tasks`。
- **任务持久化到 DB 而非纯内存**：状态可查、可恢复、支持任务历史。

### 2.2 自建调度队列
- `DishTaskQueue`：内存有序 `ArrayList waiting` + `LinkedHashMap running` + **单锁** + dispatcher/worker 线程分离（调度线程绝不自己跑任务）。
- **为什么不用现成轮子**（注释对比表，加分点）：`ThreadPoolExecutor`+阻塞队列入队后无法调序；`PriorityBlockingQueue` 插入后才能排序、之后不可重排；MQ 改顺序/取消别扭。
- **为什么用 ArrayList 而不是 Queue/HashSet**：ArrayList 是**有序且 O(1) 随机访问**的集合，下标即排队位次（`index 0` = 下一个执行）；**HashSet/HashMap 才是不保序的**。排队（`add` 队尾 / `remove(0)` 队头）和调序（按下标重排子序列）都依赖这个有序性。`LinkedList` 虽也保序但按索引访问 O(n)，队列改动频率极低，ArrayList 更划算。

### 2.3 调序
- `move(taskId, userId, orderAmongMine)`：只收集"我的任务"原下标，重排我的子序列，再**按原下标放回**——别人的任务一位不动（防插队/变相抢占）。（反例：若用 `waiting.add(0, moved)` 整体后移插入，会把它后面的 B/C 任务全挤后一位 = 变相插队；按原下标 `set` 只写自己占用的 index，避开此问题。）
- 接口 `PUT /ai/tasks/{taskId}/order`，body `orderAmongMine`（"排到我任务里的第几位"，非全局）；`Service.move()` 先 `requireOwnTask` 校验归属，A 调不了 B 的；**正在执行的任务不在 waiting，调不了序**。

### 2.4 取消
- `cancel()`：排队中直接移除（真省资源）；执行中 `Future.cancel(true)` **逻辑取消**（底层 HTTP 可能已发出、AI 仍可能跑完，注释明说）。
- 接口走 PUT 而非 DELETE：取消只是进终态、记录仍留着（用户要看"是我自己取消的"）。

### 2.5 单用户配额
- `QueueProperties.maxPerUser` 默认 **5**（可配 `ai.queue.max-per-user`），`concurrency` 默认 1。
- `submit()` 在**同一把锁内**遍历 waiting+running 数该用户任务，`≥maxPerUser` 拒绝，提示"你已有 N 个任务在排队或生成中"，防连点占满队列。

### 2.6 状态机管理生命周期
- `AiTaskStatus` 6 态（数值与 `t_ai_generate_task.status` 对应）：`PENDING(0)→RUNNING(1)→{SUCCESS(2)/FAILED(3)}`，外加 `CANCELLED(4)`、`INTERRUPTED(5)`。
- **两条硬约束**：`isTerminal()` 终态不可再流转（成功不能改失败）；`isCancellable()` 仅 PENDING/RUNNING 可取消。
- `of(code)` 非法数字兜底返回 `FAILED`。改枚举必须同步改表注释。

> **澄清（面试官可能追问"你这算状态机吗"）**：光有枚举类(只列常量)≠状态机；状态机的精髓是"状态间怎么合法流转"。本项目是**轻量/谓词式状态机**——枚举固化 6 态 + 用 `isTerminal()`/`isCancellable()` 两个约束方法表达流转边界，业务改状态前调它们校验（终态不可回退、仅未结束可取消）。**没有**显式迁移表(Map<Status,Set<Status>>)也没用 GoF State Pattern，但对单实体足够。被问"迁移规则怎么定义"答：用两个布尔谓词表达约束，比显式迁移表更轻、也够用。简历"通过状态机管理生命周期"站得住。

> **手动 vs 自动（追问“状态怎么变”时答）**：本项目是**程序式/手动式**——状态由业务代码在各分支显式 setStatus 改变（submit()置PENDING、DishGenerationExecutor 置RUNNING/SUCCESS/FAILED、cancel()置CANCELLED、AiTaskCleanupRunner 置INTERRUPTED），**没有**引入 Spring Statemachine 等框架让“事件自动驱动流转”。isTerminal/isCancellable 是**防御性守卫**，需主动调用（如取消前查 isTerminal），不是框架强制；忘了调就可能写出非法流转——这是手动式固有代价，要诚实承认。升级路径：状态多/迁移复杂时可上 Spring Statemachine 托管迁移、非法跳转由框架拒绝。

> **两方法的真实调用现场（被问“有什么用”时答）**：`isTerminal()` 是破坏性操作前的安全栅栏——①`cancel()` 中终态任务取消做幂等不报错(156)；②`deleteTask()`/`clearFinishedTasks()` 只允许终态移除、非终态抛“请先取消”防造幽灵任务(200/230)；`isCancellable()` 在 `toView():394` 里 `setCancellable()` 传给前端控制“取消”按钮显隐。本质是固化“什么状态能干嘛”、取代散落魔法数字判断，加新状态只改枚举一处。两者当前数学互补(isCancellable==!isTerminal)但语义分离，预留 PAUSED 等分化空间。

### 2.7 重启清扫（⚠️ 加分）
- `AiTaskCleanupRunner`(`ApplicationRunner`)：`UPDATE ai_generate_task SET status=INTERRUPTED WHERE status IN(PENDING,RUNNING) AND create_time < startupTime`。
- **时间条件必需**：`ApplicationRunner` 在端口就绪后执行，存在窗口期，新提交任务 create_time 晚于 startupTime，无此条件会被误标中断（端到端实测踩过）。
- **标中断不自动重跑**：任务可能写了一半（重跑产脏数据）、用户可能早不需要。**队列内存重启即空，DB 残留由 Runner 兜底标中断**。

---

## 3. 模型输出质量控制

- **三道防线（逐层落地）**：
  1. **系统提示词** `prompts/dish-generate-system.txt`：输出结构 + 字段规则（name≤12字、prepTimeMin 2~60、steps 4~8 条…）+ 负面约束（不加高端食材/不写 emoji）+ 一个完整示例 —— 先"教"模型要什么。
  2. **JSON 结构化** `AiDishGenerator.java:181` `options.setFormat("json")`：约束解码，保证输出合法 JSON、不被 ```json 代码块包住（否则解析直接失败）。
  3. **应用层校验** `DishDraftValidator`：反序列化后逐字段查 —— 文本长度 `checkText`、数值范围 `checkRange`、列表大小 `checkListSize`、用量钳制（料酒 300g 这类夸张值拦下 `checkIngredients`）、**用户食材覆盖率** `checkCoverage`（给的食材一种不能少）、营养区间 `calories∈[0,800]` `checkNutrition`。
- **错误重试** `generate()` 用 `for` 循环跑 `maxAttempts` 次：某次校验不通过，`callModel()` 把 `lastProblems`（如"prepTimeMin=0 不在 2~60 之间"）**原样拼回 userPrompt** 再发一次（`AiDishGenerator.java:167-176`）；实测小模型对"具体哪里错了"有反应、对"格式错误"笼统说法无反应 → 报错必须具体。全不过 → 抛异常标任务失败（给用户人话："AI 没能想出合适做法，换食材再试"）。
- **营养数据偏差优化** `verifyNutrition()`：用「食材主数据 × 用量」本地算一份热量，与 AI 给的值对比，偏差超容差（`nutritionTolerance`）置 `nutritionVerified=false`。用户不可见保险，前端照常标"AI 估算，仅供参考"。两个坑：①调料不进 `ingredients` → 本地值天然缺油盐，用 `SEASONING_KCAL_COMPENSATION=120` 补偿；②`calorie<=0` 的自动收录食材要放弃计算，否则把整道菜热量压低、冤枉正常结果。
- **调料遗漏/离谱用量优化（Plan A）**：提示词要求 AI **只列调料名、不写用量**（用量系统按标准配方补全）；`DishDraftValidator.SEASONING_KEYS` 把盐/糖/酱油等从 `ingredients` 拦出（只能放 `seasonings`），`MAX_SEASONING_GRAM=50` 钳制调料用量，并拦截水/高汤等无营养液体。既解决"漏调料"，又杜绝"料酒 300g"离谱值。

---

## 4. 认证与缓存降级

### 4.1 网关认证
- `JwtAuthGlobalFilter` 实现 `GlobalFilter` + `Ordered`，`getOrder()=-100`，排在 `NettyRoutingFilter`(`Integer.MAX_VALUE`) 前，转发前先认证。
- 四设计点（类注释明写）：
  ① 全局过滤器对所有路由生效，不用每条 route 配一遍；
  ② **白名单匹配"剥掉路由前缀后"的路径**：`matchPath()` 把 `/api/account/auth/login` → `/auth/login`（`STRIP_PREFIXES` 列表，与 `gateway.yaml` 的 `StripPrefix` 对应），再去白名单比。否则登录接口被自己拦死、谁都登不进（**踩过坑**）；
  ③ **先删外部传入的同名头再写**：`request.mutate()` 先 `headers.remove(X-User-Id/X-Username)` 再写入网关校验值 —— 攻击者伪造 `X-User-Id:1` 冒充管理员会被清掉，**以网关结论为准**；
  ④ `reject()` 用 `response.writeWith(...)` 真正结束响应（WebFlux 异步不能只设状态码就返回）；CORS 去重 `DedupeResponseHeader` 不能删。
- 根因：`gateway.yaml` 放路由/CORS/白名单；`jwt.secret` 与 account **逐字一致**（差一字全线 401）；token 取 userId 按 `Number` 处理（兼容 Integer/Long）。

### 4.2 布隆防重（⚠️ 易露雷区）
- `UserBloomFilter`：`RBloomFilter`（Redisson，位数组在 Redis、多实例共享、重启不丢）；`EXPECTED_INSERTIONS=100万`、`FALSE_PROBABILITY=0.01`。语义：**contains=false → 100% 不存在**（直接拒/放行）；**contains=true → 可能误判，必须查库确认**。真相以 DB 为准。
- **雷区（必背）**：简历"基于布隆实现注册防重"有歧义——布隆是"快速否定/防缓存穿透"，**不能真正防重，真防重靠 DB 唯一索引**。追问"布隆能完全防重吗"必须答：不能，1% 误判，返回"可能存在"还要查库，唯一约束兜底。
- **降级四件事**：① `mightContain` 读失败返 **true**（交查库，绝不误拒真实用户）；② `add` 写失败只记账 `pendingWrites` 不回滚（否则 `@Transactional` 连用户数据一起滚）；③ `pendingWrites` 非空时 `mightContain` **一律返 true**（布隆可能不完整，不信任否定结论）；④ 启动 `run()` 预热失败不影响启动；`warmUp` 用 `count()` 对比库记录数（估算数<记录数→重灌），覆盖"Redis故障→期间注册→重启"组合场景。
- 补充：`getFilter()` 懒初始化+双检锁+`tryInit`（已存在复用，避分布式竞态）；`clear()` **不支持删单元素**（清位影响他人），只能整体重建；30s 冷静期 `degradeUntil` 避免故障时每个请求白等超时。

---

## 5. 数据一致性

- **@TransactionalEventListener(AFTER_COMMIT)**：普通 `@EventListener` 在事务提交前触发，若回滚 ES 会留库里不存在的菜（幽灵菜）；AFTER_COMMIT 保证落定后才动作。
- 三条路径：`DishEsSyncListener` 启动 `ensureIndex` 自愈 / 发布后增量 / 每天 04:00 全量。
- **幽灵菜**（ES 有文档、MySQL 无行）：`pruneGhosts()` 拉索引全 ID 与 `t_dish` 做差集删除；`listAllDishIds` 失败返回空列表（宁可漏删不可误删）。来源：演示数据/草稿泄漏/直改库。
- `DishPublishService.publish()`：`@Transactional` + `publishEvent`，发布 = 草稿→已发布 + 写 publish_time，索引只放已发布。

---

## 读码优先级（按被问概率）

1. `DishSearchService` + `EsDslBuilder` + `DishPublishService`（召回精排 + 直出，雷区 1.4 / 1.1）
2. `DishTaskQueue` + `AiTaskStatus`（异步 + 状态机 + 调序，必让画）
3. `UserBloomFilter` + `JwtAuthGlobalFilter`（降级 + 认证，雷区 4.2）
4. `AiDishGenerator` + `DishDraftValidator` + 提示词（质量控制）
5. `DishEsSyncListener`（一致性 + 幽灵菜）

---

## 全局雷区速记

| # | 雷区 | 正确说法 |
|---|---|---|
| 1 | 直出"满足条件"说不出具体值 | 覆盖率≥0.8 且 评价人数≥10 且 威尔逊分≥0.84 |
| 2 | 以为"系统自动判断是否调 AI" | 搜索与生成是两个独立功能，用户主动点生成 |
| 3 | "调料遗漏"被追问答不上 | Plan A：AI 列名、系统补量，不进 ingredients |
| 4 | "布隆防重"被当成真防重 | 布隆只防穿透，真防重靠 DB 唯一索引 |
| 5 | 数据一致性只说"同步" | AFTER_COMMIT 避免回滚留幽灵 + 幽灵菜根因 |
| 6 | 软搜索说成"线上在用" | 默认关闭、前端未启用，线上走硬匹配 |
