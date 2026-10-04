# 前端（Vue 版）· 迁移试验

> **这是什么**：AI 灵感厨房前端的 **Vue 3 + Vite 迁移版**。
> 它与原来的原生版（`../frontend/`）**并存、互不干扰**，是一次"想撤随时能撤"的迁移试验。
> 不满意就双击项目根目录的 `rollback-frontend.bat` 回到原生版。

---

## 怎么跑

```bat
cd frontend-vue
npm install      （首次，已装可跳过）
npm run dev      开发服务器 → http://127.0.0.1:5500
```

或直接双击 `start.bat`。

**前置**：后端要在跑。本机启动顺序见下文「本机起服务」。

| 命令 | 作用 |
|---|---|
| `npm run dev` | 开发服务器（5500），带 `/api` 代理，改代码热更新 |
| `python serve-dist.py [端口]` | **跑构建产物（默认 5600）**：静态 + `/api` 反代 + SPA 兜底 |
| `npm run build` | 产出 `dist/`（生产构建，会做压缩与分包） |
| `npm run preview` | 官方预览（5600，不带 `/api` 代理，仅看静态） |
| `python verify-vue.py` | 端到端验证（无头 Chrome + CDP），自动探测 5500 / 5600 |

**演示/给别人看 → 用 `serve-dist.py`**（或双击 `start-dist.bat`）：启动即用、行为稳定。
**改代码 → 用 `npm run dev`**（热更新）。

> `serve-dist.py` 里那条 **SPA 兜底**是必需的：vue-router 用 history 模式，
> 没有"找不到就回 index.html"这条，直接刷新 `/rank` 会 404。

---

## 目录结构

```
frontend-vue/
├─ index.html            唯一 HTML 入口（SPA）
├─ vite.config.js        构建/代理配置（/api → 网关 7000）
├─ start.bat             一键启动
├─ verify-vue.py         端到端验证脚本
└─ src/
   ├─ main.js            入口：装配样式、路由、401 回调
   ├─ App.vue            根组件（含全局 Toast 容器）
   ├─ config.js          接口寻址（原样保留原生版逻辑，见下）
   ├─ api.js             请求封装 + 全部业务接口
   ├─ router/index.js    路由表 + 登录态守卫
   ├─ store/
   │  ├─ auth.js         登录态（reactive），ID 一律字符串
   │  └─ toast.js        Toast 状态
   ├─ lib/
   │  ├─ utils.js        校验 / 密码强度 / 复制 / JSON 着色
   │  ├─ constants.js    难度·辣度·来源 文案映射
   │  ├─ ingredients.js  食材 88 种 / 口味 / 人数 / 分组色相（须与 MySQL 严格一致）
   │  └─ floaters.js     登录页漂浮装饰（固定种子，逐行对齐原生版）
   ├─ components/
   │  ├─ TopBar.vue      顶栏（三内页共用）
   │  ├─ FxBg.vue        流光背景层
   │  └─ DishCard.vue    菜品卡片
   ├─ views/
   │  ├─ LoginView.vue   登录/注册
   │  ├─ KitchenView.vue 找菜工作台
   │  ├─ DishView.vue    菜品详情
   │  ├─ RankView.vue    榜单
   │  ├─ ProfileView.vue 我的
   │  └─ AdminView.vue   管理台（候选审核 / 索引同步 / 营养重算 / 下架）
   └─ styles/            ← 直接从原生版 css/ 复制而来，避免视觉回归
```

---

## 迁移进度

| 页面 | 路由 | 状态 |
|---|---|---|
| 登录 / 注册 | `/` | ✅ 已迁移并通过验证 |
| 找菜工作台 | `/kitchen` | ✅ 已迁移并通过验证 |
| 菜品详情 | `/dish/:id` | ✅ 已迁移并通过验证 |
| 榜单 | `/rank` | ✅ 已迁移并通过验证 |
| 我的 | `/profile` | ✅ 已迁移并通过验证 |
| 管理台 | `/admin` | ✅ 新增（普通用户进不去，管理员四个分段可用） |

**六个页面全部完成**，验证结果：**253 断言 / 0 失败**（含真实后端全链路 + 真实浏览器点按）。

覆盖范围：

- **登录页**：渲染、Tab 切换、密码显隐、密码强度、前端校验对齐后端 DTO、
  记住用户名、路由守卫（含 `?redirect=` 回跳）、接口地址设置、真实注册+登录
- **找菜工作台**：88 种食材分 5 组、点选/取消、**搜索过滤后已选状态不丢**、
  自由输入（表内名字并入点选 / 表外名字标「自填」）、联想下拉键盘导航、
  口味与人数、**10 种上限**、真实召回（状态条 + 主推 + 候选）、提交 AI 生成 + 任务区
- **找菜模式与更多条件（9f）**：宽松/严格切换（严格档位整排消失）、缺料档位 1/2/3、
  忌口与厨具芯片、必用食材标记、最长耗时 —— **断言的是前端真实发出的请求体**
  （patch `window.fetch` 抓 `/api/dish/search` 与 `/api/dish/ai/dish` 的载荷，
  生成接口用伪造响应拦截，避免验证一次烧掉几分钟 GPU）
- **管理台（9g）**：普通用户访问 `/admin` 被弹回工作台且顶栏没有「管理」入口；
  注册账号后直连库提权 → 登录回传 `role=1` → 顶栏出现入口 → 四个分段
  （待审候选 / 索引同步 / 营养重算 / 下架）逐个点开，断言无错误框、统计格 ≥3 项、
  空 ID 时下架按钮 disabled
- **菜品详情**：发布/撤回按钮（仅创建者可见）、收藏、五星打分、评论发表（空评论被拦）、
  营养来源标注（本地计算 / AI 估算两条分支）、不存在的菜 → 错误态
- **营养来源标注（9h）**：列表与详情**必须同口径** —— 库里 source=1 的菜都是未发布草稿、
  详情页取不到，所以脚本会临时把一道已发布菜标成 AI 估算、验完 `finally` 还原，
  以保证这条渲染分支也真的被覆盖过
- **手写菜谱（9i）**：工作台点「手写菜谱（自己写一道）」→ **停在 `/kitchen` 的页内覆盖层**
  （不是新路由）→ 已点选食材**自动预填**成分表 → 门槛不够时上传按钮 disabled →
  实时试算（断言出现 `每人份 … kcal（覆盖率 …%）`）→ 上传 → toast「已发布到找菜库」→
  库里断言 `status=1 / source=3 / nutrition_source=2 / verified=1`、明细恰好 2 行且都挂主数据
  ID、首行 `main_flag=1`（空行没进明细）→ 详情页显示「营养按成分表计算」+「我的创作」→
  **同名再传一次**：面板当场提示「你已经有一道了…换个名字」且按钮 disabled，绕过面板直接打接口
  也只回人话（不是「服务器开小差了」）
- **推荐页（9j）**：`/ai/recommend` 真返回 5 条、`matchType` 字段在、`t_dish_push_log`
  **表存在**（缺表整页 500 的回归断言）
- **榜单**：三档切换、导航高亮、榜单项跳详情
- **我的**：双 Tab、草稿/已发布分组、空态、账号信息

---

## 迁移时守住的三条底线

### 0. 页面专属 CSS 用「页面根类名」整体限定（SPA 特有的坑）

原生版每个页面只引自己的 CSS，所以同名/通用类**永远不会同时存在**。
Vue 是 SPA，7 份 CSS 全部全局常驻 —— 会互相覆盖，而且**后加载的静默胜出**。

已经踩过三次，症状各不相同：

| 症状 | 真凶 |
|---|---|
| 登录取/注册两个按钮挤在左边、白块错位 | `profile.css` 的 `.tabs{display:flex}` 覆盖了登录页的 `grid` |
| 注册表单字段间隙 40px + 多出分隔线 | `kitchen.css` 的 `.field + .field{margin-top:22px;padding-top:20px;border-top}` |
| 内页配色被改（`--ink-3` 变浅等） | `style.css` 的 `:root` 后加载，全局覆盖了内页设计令牌 |

**做法**：每个视图根元素带一个页面类名，各自 CSS 的选择器全部前缀它。

| 页面 | 根类名 | CSS |
|---|---|---|
| 登录 / 注册 | `.page-login` | `styles/style.css` |
| 找菜工作台 | `.page-kitchen` | `styles/kitchen.css` |
| 菜品详情 | `.page-dish` | `styles/dish.css` |
| 榜单 | `.page-rank` | `styles/rank.css` |
| 我的 | `.page-profile` | `styles/profile.css` |

边界规则：

- `:root` → 改挂到页面根（如 `.page-login{--brand:…}`），自定义属性可继承，正好只对本站生效
- `*` / `html` / `body` → 保持全局
- `@media` / `@supports` → 递归限定内部
- `@keyframes` → 原样保留（里面的 `0%` / `from` 不是选择器）

两个容易忘的配套点：

1. **登录页要显式写 `background: var(--page)`** —— `.form-panel` 自己不画背景，
   原来靠 `body` 的全局 `--page` 撑着；令牌限定后全局值变回内页的冷灰，不补就丢暖色调。
2. **Toast 容器在 `App.vue` 里、位于页面根之外**，所以 toast 样式统一由 `app.css` 提供。
   在页面 CSS 里写 `.page-x .toast*` 是**死规则**（永远不会匹配），别写。

**排查必须用运行时检测**（`.workbuddy/diag-css-leak.py`）：
静态比对选择器字符串抓不到 `.field + .field` 这类「选择器不同、却匹配同一批元素」的情况。
它利用 Vite dev 注入样式时带的 `data-vite-dev-id`，逐个规则 `querySelectorAll` 一遍，
就能精确列出「别的页面 CSS 命中了本页元素」。

### 1. 不碰后端

接口契约、网关、`jwt.secret` 一个字不改。Vue 版和原生版调的是同一批接口。

### 2. 雪花 ID 一律保持字符串

后端所有 `Long` 都序列化成字符串（19 位雪花 ID 超出 JS `Number` 安全范围，2^53）。
`store/auth.js` 里 `saveLogin()` 会 `String(userId)`，`isOwner()` 用字符串比较。
**任何时候都不要写 `Number(id)`。**

### 3. 接口寻址逻辑原样保留

`src/config.js` 的 `detectDefaultApiBase()` 与原生版语义一一对应 ——
它按"页面从哪来"推断接口地址，**不写死 `127.0.0.1`**。
写死的话，访客打开公网地址后，他的浏览器会去请求**他自己那台电脑**的端口，
报"连接被拒绝"，看起来像后端挂了，实际请求根本没出他那台机器。

> Vue 版多了一个 `__VUE_DEV_PROXY__` 编译期常量：Vite dev server 虽然也跑在 5500，
> 但它自带 `/api` 代理，所以走同源；而原生版的纯静态服务器 5500 没有代理，必须直打网关。
> 这个常量用来区分这两种同端口、但策略相反的情形。

---

## 本机起服务（重要）

### 后端启动

三个 jar（按顺序，**必须显式指定端口**）：

```bat
java -Dfile.encoding=UTF-8 -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar --server.port=7000
java -Dfile.encoding=UTF-8 -jar account-service/target/account-service-0.0.1-SNAPSHOT.jar --server.port=8081
java -Dfile.encoding=UTF-8 -jar dish-service/target/dish-service-0.0.1-SNAPSHOT.jar --server.port=8082
```

### ⚠️ 为什么必须写 `--server.port`

WorkBuddy 的运行环境会**给每个子进程注入 `SERVER__PORT` 环境变量**。
Spring Boot 的宽松绑定会把它当成 `server.port`，于是服务去抢 **WorkBuddy 自己**的端口，
必然失败，报的是：

```
Web server failed to start. Port 53482 was already in use.
```

这个端口号每次都不一样、项目里也 grep 不到，非常难查。
**命令行参数优先级高于环境变量**，所以显式传 `--server.port=` 就能压住它。

### 依赖中间件

| 组件 | 方式 |
|---|---|
| MySQL 3306 | 本机安装 |
| Redis 6379 / Nacos 8848 | Docker 容器（`redis6` / `nacos`） |
| Elasticsearch 9200 | Docker 容器（`es`，8.16.0） |

---

## 验证脚本要注意的坑

`verify-vue.py` 里踩过、并且已经修掉的八个「假失败」：

1. **跨次运行的 localStorage 残留** —— Chrome 被 kill 前不一定刷盘，上轮的 token
   会带进本轮，路由守卫把 `/` 重定向走，登录页断言集体失败。
   → 脚本最前面强制 `localStorage.clear()` + reload。
2. **CDP 连接被拒（403）** —— 新版 Chrome 默认拒绝非白名单 origin，
   必须加 `--remote-allow-origins=*`。
3. **同 URL `Page.navigate` 不重载** —— SPA 里它可能只切组件，
   `onMounted` 不会重跑。测"刷新后回填"要用 `Page.reload`。
4. **`v-if` 控制的元素会整个消失，对 `null` 取属性就是假失败** ——
   `document.querySelector('#manualProblems').children.length` 在"零问题"那一刻抛
   `TypeError: Cannot read properties of null`，而 `wait_for` 还会一路空转到超时，
   表现成一屏"看着像功能坏了"的 FAIL。**数条数要用
   `document.querySelectorAll('#manualProblems li').length`**（元素不在也返回 0）。
5. **选择器拼进 JS 要用单引号** —— `click_sel` / `set_val` 把选择器包进
   `querySelector(...)` 时，Python 里的 `\"` 只是 `"`，于是生成
   `querySelector(".ing[data-id="101"]")` → `SyntaxError: missing ) after argument list`，
   后面全是连锁假失败（按钮没点上 → 预填空 → 上传按钮仍禁用 → 没落库）。
   脚本里的选择器本身就带双引号，外层一律用**单引号**包。
6. **用固定 `sleep` 等 SPA 路由分包** —— Vite dev server 是"按需编译"的，
   `/admin` 的分包第一次请求才转换，冷启动时 2.5 秒不够 → 8 条断言一起 FAIL
   （`location.pathname` 还停在 `/kitchen`），而最后"控制台健康度"照样 PASS（分包最终加载成功、没有报错）。
   → 等页面就绪一律用 `wait_for(...)` 轮询。
7. **浏览器是单例，新起的 CDP 进程可能"借道"老实例** —— 同一 `--user-data-dir`
   已有实例在跑时，新进程转交 URL 后自己立刻退出，于是这一轮实际挂在**上次遗留的浏览器**上，
   那个作业被回收时就会在中间抛
   `ConnectionResetError: [WinError 10054] 远程主机强迫关闭了一个现有的连接`。
   → 启动前先按 profile 标记（`cdp-profile-vue`）清掉残留浏览器并等端口释放，
   启动后检查 `proc.poll() is not None`（立刻退出 = 借道了老实例）。
8. **一味"全停在 `/`" + `SecurityError: ... localStorage ... Access is denied`** ——
   这不是前端代码坏了，而是**前端服务掉了**：导航失败后浏览器停在错误页，错误页上读
   `localStorage` 直接抛异常，依赖登录态/页面元素的断言就连锁失败。
   → 先确认 5500（或 5600）**和** 8082 都活着，再去翻代码。

> 另一条更花钱的教训：**测试数据里出现"用户可见的唯一约束"时，不要用固定名字**。
> 手写菜谱的菜名走 `uk_name_creator(name_key, creator_id)`，固定名字第二次跑必然撞名、
> 只能留在草稿 → 假失败。脚本里用 `'手写验证' + 6 位随机数字`。
