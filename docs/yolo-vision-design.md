# YOLO 图片识别 → 食材清单 → 用户勾选 → 一键生成 全链路设计

> 版本：**v2（2026-10-16，代码已落地；v1 的 4 个待确认点已拍板，见 §12）**
> 上游文档：`docs/ai-service-split.md` §5（Phase 3 草案）—— 本文是其落地细化版
> 需求原话：「根据 yolo 模型识别多张图片的菜品内容，生成对应食材清单，然后供用户选择，然后生成」
> 架构定调（斌宝 2026-10-16）：「模型推理过程都迁移到 Python，擅长的事情给擅长的人做」

---

## 1. 需求拆解与范围

| # | 需求点 | 落地形态 |
|---|---|---|
| 1 | **多张图片**识别 | 一次请求带多图（上限 9 张），服务端批量推理、结果按图归组 |
| 2 | 识别**菜品/食材内容** | YOLO 目标检测 → 检出 label（食材名）→ 映射库内食材 ID |
| 3 | 生成**食材清单** | 多图检出结果**聚合去重**：同名食材合并计数、取最高置信度 |
| 4 | **供用户选择** | 前端候选网格：裁剪小图 + 名称 + 数量 + 置信度，高置信度默认勾选，用户可改 |
| 5 | **然后生成** | 勾选项直接回填厨房生成表单 → 走现有 `POST /ai/dish` 异步链路 |

**已确认边界**（沿用 ai-service-split §9，斌宝 2026-10-15 拍板）：
- 只做**图片**，视频不做（算力与压力）。
- 识别是**辅助不是权威**：候选项必须经用户勾选，勾选动作同时兜底误识别。
- 识别失败/服务不可用 → 回退**手动选食材**的现有流程，识别是加速项不是必经环节。

---

## 2. 架构原则：模型推理全归 Python（本次新增定调）

斌宝 2026-10-16 定调：「模型推理过程都迁移到 Python，擅长的事情给擅长的人做」。
据此确立**推理边界表**——所有"跑模型"的事只许出现在 ai-service，Java 只做编排：

| 能力 | 现在在哪 | 设计决定 |
|---|---|---|
| LLM 菜谱生成 | ai-service（Phase 1 已迁） | 维持 |
| YOLO 图片识别 | — | **新建在 ai-service**（本文主体） |
| `AiDishGenerator`（local 回滚实现，spring-ai-ollama） | dish-service | **删**。remote 链路联调通过后执行（§10 步骤 7） |
| `spring-ai-ollama` 依赖与 BOM、`AiProbeController` | dish-service | **删**（同上） |
| Java 版 `DishDraftValidator` / `NutritionCalculator` | dish-service | 远程模式下不再参与链路；切流验收后删（pytest 已逐条对照，45 全绿） |
| ES 关键词检索（`ReferenceDishRetriever`） | dish-service | **留**。ES DSL 踩过坑（terms 默认 OR），不 duplicated；Phase 2 embedding 重排才归 Python |
| `DegradedModeGuard`（ES 存活探活） | dish-service | **留**。判的是 ES 不是模型 |
| 队列 / 状态机 / 事务 / ES 写入 / 发布 | dish-service | **留**（本来就不碰模型） |

> 为什么敢删 local 回滚开关：回滚的本质是"切换前验证过 remote"。Phase 1 剩余联调
> （起 Ollama 跑全链路 + es/eval 指标对比）跑通之前不删；删的命令就是 `git revert`。
> **执行顺序红线：先联调验收，再删 local，不允许跳步。**

---

## 3. 关键现实：权重现状与两步走

当前权重 `ai-service/yolo_model/weights/yolov8n.pt` 是 **COCO 80 类预训练权重**
（person/car/apple/banana/broccoli/carrot/bowl…），**没有中式食材类别**。
项目 89 种食材要靠自建数据集训练（ai-service-split §5.3：「数据集是本体」）。

因此拆成互不阻塞的两步：

| 步骤 | 内容 | 价值 |
|---|---|---|
| **A. 工程链路先行** | 用现有 COCO 权重打通「上传 → 检测 → 清单 → 勾选 → 生成」全链路 | 接口契约、Java 编排、前端交互全部就位；能认出 apple/banana/broccoli/carrot 等约 12 类食物，其余走 unmappedLabels + 手动兜底 |
| **B. 数据本体到位** | 89 类食材数据集 → 训练 → `best.pt` 放进 `yolo_model/weights/` | **Java/前端零改动**，识别效果即达可用水平 |

本文设计对两个步骤都成立：接口按「模型输出 label → 映射表 → 食材 ID」解耦，
换权重只换文件。类别映射表同时覆盖两种来源（§6.3）。

---

## 4. 总体链路

```
前端(多图上传) → gateway(JWT鉴权) → dish-service(落盘+聚合+映射) → ai-service(YOLO批量推理)
                                                                      ↓ 检出 label+框+置信度
前端(候选网格勾选) ← 聚合食材清单 ← 两级映射(表+别名) ←──────────────┘
   ↓ 勾选确认
POST /ai/dish(现有异步链路: 排队→gemma生成→轮询→结果)
```

分工一句话：**Python 只回答"图里有什么"（label+框+置信度），Java 决定"这是什么食材"（映射+聚合），用户决定"用哪些"（勾选）。**

---

## 5. Python 侧设计（ai-service）

### 5.1 新增文件

| 文件 | 职责 |
|---|---|
| `ai_service/vision.py` | `YoloDetector` 单例：懒加载、device 配置化、**批量 predict**、conf 阈值、`np.fromfile` 读图（防中文路径坑） |
| `ai_service/routes.py` | 新增 `POST /api/v1/vision/dishes` |
| `config.yaml` | 新增 `vision:` 段（weights/device/conf/imgsz/max_images） |
| `tests/test_vision.py` | `pytest.importorskip("ultralytics")` 保护——没装依赖的环境自动跳过 |

### 5.2 检测器要点

- **懒加载 + 常驻**：首次请求时加载权重（启动不加载，没装 ultralytics 也能起服务做生成）；
  之后常驻。yolov8n 权重 6MB，显存占用小，与 gemma 抢显存压力可忽略。
- **批量推理**：`model.predict(source=[img1, img2, ...])`，ultralytics 原生支持 list 输入，
  9 张图一次 batch，GPU 上远快于循环单张。
- **锁**：Flask `--threads 8` 下模型单例共用，predict 调用加 `threading.Lock`。
  GPU 单图 4.4ms（实测），锁无感；CPU 模式串行也可接受（MVP 定位）。
- **权重路径**：一律写 `yolo_model/weights/<文件名>` 相对路径——写裸名 `yolov8n.pt`
  ultralytics 会尝试**联网下载**（requirements.txt 注释已强调，别再踩）。
- **推理完 `torch.cuda.empty_cache()` 不调用**：yolov8n 常驻显存才有速度，清缓存反而慢。

### 5.3 接口契约（Java → Python）

```
POST /api/v1/vision/dishes
{
  "requestId": "vision-1731649200000",
  "images": [
    {"imageId": "img-1", "mediaPath": "D:/.../dish-service/uploads/vision/2026/10/uuid1.jpg"},
    {"imageId": "img-2", "mediaPath": "...uuid2.jpg"}
  ]
}

→ {
  "ok": true, "costMs": 120, "model": "yolov8n.pt", "device": "cuda:0",
  "images": [
    {"imageId": "img-1", "detections": [
      {"label": "apple", "confidence": 0.87, "box": [120, 80, 260, 260]}
    ]}
  ]
}
```

- `box` = `[x, y, w, h]` 像素，供 Java 侧裁剪小图用（裁剪归 Java，遵守"Python 不存业务文件"）。
- 失败分类沿用现有风格：`ok:false` + `errorKind`（`model_unreachable` 类改为
  `model_not_loaded`/`bad_request`/`internal`），错误信息明示，不卡超时。
- 读超时 60s（9 张图 CPU 模式上限足够；三层超时链见 ai-service-split §6.3，单独配）。

### 5.4 依赖安装（一次性）

```bash
# venv: C:\Users\K墨几\.workbuddy\binaries\python\envs\ai-service（3.12.10，torch cu124 已在）
pip install -i https://pypi.tuna.tsinghua.edu.cn/simple ultralytics==8.4.177
```

直连 PyPI 会被开发机代理掐断，必须走镜像（同 requirements.txt 注释）。

---

## 6. Java 侧设计（dish-service）

### 6.1 对外接口（前端 → gateway → dish-service）

```
POST /ai/vision/dishes        （multipart/form-data，files 多文件，≤9 张，单张 ≤10MB）
→ Result<VisionResult>
```

```json
{
  "requestId": "vision-1731649200000",
  "costMs": 380,
  "images": [
    {"imageId": "img-1",
     "imageUrl": "/uploads/vision/2026/10/uuid1.jpg",
     "detections": [
       {"label": "apple", "confidence": 0.87, "cropUrl": "/uploads/vision/crops/img-1_0.jpg",
        "mapped": true, "ingredientId": "89", "ingredientName": "苹果"}]}
  ],
  "candidates": [
    {"ingredientId": "89", "name": "番茄", "count": 3, "maxConfidence": 0.91,
     "checked": true, "sourceImageIds": ["img-1", "img-2"]}
  ],
  "unmappedLabels": [{"label": "bowl", "count": 2, "maxConfidence": 0.8}]
}
```

- **同步接口**，不用异步任务队列：YOLO 单张 GPU 4.4ms、CPU 秒级，9 张上限内同步返回；
  异步任务表是给 gemma（12~26s/道）准备的，识别复用它是高射炮打蚊子。
- **必须登录**：接口**不进 gateway 白名单**（识别要记 userId、要限流），走 JWT。
- `X-User-Id` 照现有约定从网关注入的 header 读（同 `DishGenerateController`）。

### 6.2 新增组件

| 组件 | 职责 |
|---|---|
| `VisionController`（controller 包） | 接 multipart、参数校验（张数/大小/类型）、调 service、包装 Result |
| `VisionService`（service 包） | 编排：落盘 → 调 Python → 聚合 → 映射 → 裁剪 → 组结果 |
| `VisionClient`（ai 包） | RestClient 调 ai-service（仿 `RemoteDishGenerator` 构造方式，读超时 60s） |
| `VisionProperties`（ai 包，`@ConfigurationProperties("ai.vision")`） | base-url / weights 名 / conf 阈值 / max-images / upload-dir / read-timeout |
| `WebConfig`（config 包，如无则建） | `addResourceHandlers`：`/uploads/**` → `file:./uploads/` |

**落盘规则**：
- 目录 `uploads/vision/2026/10/`，文件名 **UUID + `.jpg`（纯 ASCII）**——
  中文路径会让 cv2.imread 静默失败，Python 侧虽然用 `np.fromfile` 兜底，
  存储层仍保持 ASCII 是双保险。
- 校验：只收 image/jpeg、image/png；超 10MB 或超 9 张直接 400 拒。
- `uploads/` 加进 `.gitignore`（新目录，别把测试图提交进仓库）。

**静态资源链路（实现时修正：不需要新增网关路由）**：

v1 计划给 gateway 加一条 `Path=/uploads/**` 路由。实现时发现**复用现有
`dish-service-route` 更好**（少一处配置 = 少一个出错点）：

| 环节 | 做法 |
|---|---|
| 后端返回的图片地址 | 相对业务路径 `/uploads/vision/2026/10/xxx.jpg`（不关心前端怎么访问） |
| 前端拼地址 | `AppConfig.serviceUrl('dish', url)` → 网关模式得到 `/api/dish/uploads/...`；直连模式得到 `http://host:8082/uploads/...` —— 两种模式都对，且不写死 127.0.0.1 |
| 网关 | 走现有 `Path=/api/dish/**` 路由（StripPrefix=2 后正好落到 dish-service 的 `/uploads/**` 资源映射），**零新增路由** |
| **gateway 白名单必须加 `/uploads/**`** | 前端用 `<img src>` 展示图片，**标签带不了 Authorization 头**，不放行就是一片 401。匹配的是"剥前缀后"的路径，所以写 `/uploads/**`。安全权衡：文件名是 UUID 不可枚举，且数据接口仍走 JWT |
| 拦截器 | dish-service 的 `WebMvcConfig` 对 `/uploads/**` 显式 `excludePathPatterns`（图片不走管理员鉴权） |

**multipart 限制（双层都要配）**：
- dish-service：`spring.servlet.multipart.max-file-size=10MB`、`max-request-size=100MB`
  （Nacos `dish-service.yaml`；与 common.yaml 的 spring 段是**深度合并**，不冲突）。
- gateway：Spring Cloud Gateway 默认不卡 body，确认无全局 `max-request-size` 冲突即可。

### 6.3 映射：两级归一 + 一张配置表

```
label ──① 查 t_vision_label（精确命中）──→ ref_id（t_ingredient.id / t_dish.id）
      ──② 未命中 → IngredientDirectory.resolveId(label)（正式名+别名归一）
      ──③ 都不中 → unmappedLabels（明示返回，不静默丢弃）
```

为什么两级：COCO 输出英文 label（`apple`），`resolveId("apple")` 解析不了，必须显式配表；
自训模型输出中文食材名，走别名归一即可，无需逐条配。一张表兼容两种来源。

**新表 `t_vision_label`**（新增主数据，归 Java 管；DDL 放 `sql/`，初始数据脚本同目录）：

| 字段 | 说明 |
|---|---|
| `label` VARCHAR(64) 唯一 | YOLO 输出的类别名（COCO 英文名或训练后中文名） |
| `label_type` VARCHAR(16) | `ingredient` / `dish`（MVP 只用 ingredient） |
| `ref_id` BIGINT | 引用 `t_ingredient.id`；dish 型将来引用 `t_dish.id` |
| `min_confidence` DECIMAL(4,2) | 低于此值的检出**直接丢弃**（默认 0.50） |
| `default_checked` TINYINT | **预留字段**：默认勾选开关。当前实现用全局阈值 `ai.vision.default-checked-confidence`（0.8）；将来要对单个 label 细控（某类识别率低、希望用户手动确认）时才启用 |
| `enabled` → 实现为 `status` | 停用某个映射不删行（0-停用 / 1-启用） |
| `deleted` + 时间戳 | 遵循项目 `@TableLogic` / fill 规范 |

初始数据只配 COCO 食物类里**真正对得上**的 2 条
（`carrot → 胡萝卜`、`broccoli → 西兰花`，`ref_id` 用子查询按名字取，脚本幂等）；
`bowl`/`cup` 是容器不是食材，**不建映射**；apple/banana/pizza/cake 等库内没有对应食材，
**故意不配**——它们会进 `unmappedLabels`，正好把那条展示链路测到。

**聚合规则**（多图 → 一份清单）：
- 按 `ingredientId` 聚合：`count` = 各图检出数**求和**（两张图都有番茄 = 番茄×2）；
  `maxConfidence` = 各图中最大值；`checked` = `maxConfidence ≥ 0.8`（高置信默认勾）；
  `sourceImageIds` 记录来源，前端可反查是哪几张图认出来的。
- 聚合后清单按 `maxConfidence` 降序，前端网格顺序即此序。

### 6.4 与生成链路的衔接（关键：DTO 零改动）

勾选结果天然落在 `DishGenerateRequest` 的两个字段上，**不需要改 DTO**：

| 勾选项 | 去向 | 理由 |
|---|---|---|
| 映射成功的食材 | `ingredientIds` | 有主数据 ID，参与营养校验、可进倒排索引 |
| unmappedLabels 里用户勾了的 | `freeIngredients` | 表外名字走自由输入通道（是否开放见 §12 待确认） |

回填后用户在厨房页补口味/人数/约束 → 提交 `POST /ai/dish` → 轮询 `GET /ai/tasks` →
展示结果。**生成链路一行不改。**

可选增强（MVP 不做，做实验时再加）：`DishGenerateRequest` 加 `source` 字段
（`manual`/`vision`）并进请求签名，用于 es/eval 区分生成输入来源。

---

## 7. 前端设计（frontend-vue）

### 7.1 流程

```
厨房页(KitchenView) ──点击「📷 识菜下厨」──→ 识菜视图(VisionView)
  上传区(多选/拖拽/预览/删除, ≤9张) ──开始识别(loading)──→ 结果区
  结果区左: 图片网格(缩略图+检测框+label角标)   右: 食材清单(名称/数量徽章/置信度条/勾选)
  unmappedLabels 区(置灰, 是否可勾见待确认)
  ──「用这些食材生成 →」──→ 回填厨房生成表单 ──补口味/人数──→ 提交(现有链路)
```

### 7.2 组件与文件

| 文件 | 说明 |
|---|---|
| `src/views/VisionView.vue` | 新视图，路由 `/vision`；从厨房页进入（带返回） |
| `src/styles/vision.css` | 新样式文件，**同步在 styles 入口加 import**（CSS 分层铁律） |
| `src/lib/vision.js` | API 封装：`FormData` 上传 + 响应类型，走 `serviceUrl('dish', path)` |

遵守现有约定：不写死 127.0.0.1（`detectDefaultApiBase()`）；工作台页不加 100dvh 锁高
（识菜页内容随图数伸缩，自然滚动即可，≤980px 也不需要回退逻辑）。

### 7.3 降级路径

- ai-service 不可用 / 返回 `ok:false` → 顶部黄条「识别服务暂不可用，可手动选择食材」，
  提供「手动选择」按钮跳回厨房表单——**识别是加速项，不是必经环节**（§1 已确认边界）。
- 检出为空 → 「没认出食材，换个角度拍或手动选」，同样给手动入口。
- 上传中/识别中：按钮 loading + 禁止重复提交。

---

## 8. 数据库变更汇总

| 变更 | 说明 |
|---|---|
| 新增 `t_vision_label` | 类别映射配置表（§6.3）。DDL + COCO 初始数据脚本：`sql/migration-07-vision.sql`，已执行、已验幂等 |
| 新增 `t_vision_detect_log` | 识别记录（requestId/userId/图数/检出JSON/勾选JSON/耗时）。**价值**：用户纠正与识别不一致的记录 = 后续训练难例。斌宝 2026-10-16 确认建（原为"可选"） |
| 不动 `t_ingredient` / `t_dish` / `t_ai_generate_task` | 生成链路完全复用现有表 |

---

## 9. 安全与运维

- 接口走 gateway JWT，**不加白名单**；X-User-Id 从网关注入读，同现有模式。
- 频率限制：MVP 靠「≤9 张/次 + 单用户识别记录（若建 log 表）」兜底；
  上量后再考虑按用户限流（参照 `DegradedModeGuard` 的思路）。
- `uploads/` 目录只服务 `/uploads/**`，Java 资源映射限定该前缀，不放任任意路径。
- 运维可见性：识别耗时 `costMs` 随响应返回；ai-service 日志打 model/device/张数。

---

## 10. 实施步骤（每步可独立验证、可回滚）

| 步骤 | 内容 | 验证点 | 状态（2026-10-16） |
|---|---|---|---|
| 1 | Python：装 ultralytics → `vision.py` + 路由 + `config.yaml` vision 段 + `importorskip` 测试 | 用一张本地图 curl `/api/v1/vision/dishes`，返回 COCO 检出 | ✅ 完成：ultralytics 8.4.177 已装；`52 passed`（原 45 + 新 7，含真跑模型的 smoke） |
| 2 | Java：VisionProperties + VisionClient + VisionService + VisionAggregator + VisionController + 静态资源映射 + DTO | `mvn test` 新增用例绿；不装 Python 也能编译过 | ✅ 完成：`113/113` 通过（含 `VisionAggregatorTest` 10 个）；compile 通过 |
| 3 | DB：`sql/migration-07-vision.sql`（两张表 + COCO 初始数据） | 脚本可重复执行（幂等） | ✅ 完成：已执行并二次执行验证幂等；2 条映射正确 |
| 4 | gateway 白名单 + multipart 配置（**修正：不需要新增路由**，见 §6.2） | 浏览器经网关能打开上传的图 | ⏳ 配置留档已改（`nacos-config/`），**待推 Nacos**（推送时 Nacos 需在运行） |
| 5 | 前端：VisionView + 厨房入口 + 回填生成表单 | 编译校验通过；全链路走通 | ✅ 代码完成（SFC/JS 语法已用 vue compiler-sfc + babel 校验）；⏳ 待服务起了做端到端 |
| 6 | 联调记录：COCO 权重下记录一次完整识别的响应样本（回填进 §5.3 作契约快照） | 样本归档 | ⏳ 待服务起 |
| 7 | **推理收敛**（§2）：Phase 1 联调验收后删 `AiDishGenerator`/spring-ai/BOM/`AiProbeController`；Java 版 validator/nutrition 切流验收后删 | 每删一个 `mvn test` 全绿；git 可 revert | ⏳ 等联调（注意：Nacos 留档里 `ai.generate.mode` 已是 `remote`，但 Java 侧 local 实现仍在——文档与代码不一致，见 §13） |
| 8 | 数据本体：89 类食材数据集训练 → `best.pt` 归位 `yolo_model/weights/` | **零代码改动**，同一接口效果即达可用 | ⏳ 待斌宝训练（Phase 3 的本体） |
| 9 | 文档同步：`docs/architecture.md` + `docs/interview/` 两份口径 | 简历/面试不说错 | ✅ `architecture.md` 已补；`docs/interview/` 待 §13 一起收口 |

---

## 11. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| COCO 权重没有中式食材类，识别率惨淡 | 体验差、像没做 | 两步走（§3）：工程链路先就位；unmappedLabels + 手动兜底；best.pt 到位即见效 |
| 中文路径导致 cv2 读图静默失败 | Python 侧报"图里什么都没有" | 存储纯 ASCII 文件名 + Python `np.fromfile` 双保险 |
| ultralytics 尝试联网下载权重 | 启动卡住/拉错模型 | 一律相对路径 `yolo_model/weights/xxx.pt` |
| 与 gemma 抢显存 | 两边都慢 | yolov8n 常驻显存小；device 配置化（`auto`/`cpu`/`cuda:0`），紧张时切 CPU（秒级仍可用） |
| multipart 被默认限制截断 | 大图上传 400 | 双层配置（dish + gateway 确认）写进 §6.2 |
| 误识别带入错误食材 | 生成结果偏题 | 勾选机制天然兜底：高置信默认勾、用户可改、unmapped 明示 |
| 删 local 后 remote 不稳定 | 生成全挂 | 执行红线：先联调验收再删；删的命令就是 git revert |
| 简历口径漂移 | 面试翻车 | 步骤 9 强制同步 docs/interview/ |

---

## 12. 决策记录（2026-10-16 已拍板）

v1 的 4 个待确认点，斌宝"按你的意思来"确认全部照建议执行：

| # | 决策 | 结论 |
|---|---|---|
| 1 | unmappedLabels 是否允许用户勾选 | **允许**。勾选后作为「自由食材」带入生成（`freeIngredients` 通道本来就是为表外食材设计的）。前端 unmapped 区可勾选，文案说明"不进食材表" |
| 2 | 是否建 `t_vision_detect_log` | **建**。识别记录 + 用户勾选，是后续模型训练的难例来源。已随 `migration-07` 落地 |
| 3 | local 实现删除时机 | **Phase 1 联调（起 Ollama 跑 `POST /ai/dish` 全链路）通过后立即删**，不等 YOLO 联调。删完少一个维护面 |
| 4 | 识别接口上限 | **单次 9 张、单张 10MB**，后期调整只改配置（`ai.vision.max-images` / `max-file-size-mb`，两边与 Python `vision.max_images` 同步改） |

---

## 13. 遗留与发现（2026-10-16 实现时记录）

**实现环境情况（影响联调进度，非设计问题）**：

| 项 | 情况 | 处理 |
|---|---|---|
| Nacos 未运行 | 8848 端口不通（探测时的 502 是本机 `HTTP_PROXY` 造成的假象；`NO_PROXY` 后确认是拒绝连接） | 两份配置留档已改好，推送命令见 §14；multipart 与白名单都需**重启/刷新**才生效 |
| dish-service / gateway / ai-service / ES 均未启动 | 端到端联调无法在本轮完成 | 已完成的验证：Java `mvn test` 113 绿、Python `pytest` 52 绿、前端语法编译通过、DB 建表幂等 |
| **COCO 权重能对上的只有 2 类** | 89 种食材里只有胡萝卜、西兰花与 COCO 的食物类同名（无苹果/香蕉/橙子） | 印证 §3 的判断：步骤 A 的价值在打通链路，不是产出可用识别率；其余 label 进 unmapped，正好测该链路 |
| **代码与 Nacos 留档不一致** | `nacos-config/dish-service.yaml` 里 `ai.generate.mode: remote` 且注释写着"Java 本地实现已删除"，但 `AiDishGenerator` / `DishGeneratorConfig` 的 local 分支仍在 | 留给步骤 7 收口：**先联调 remote 通过，再删 local**（§2 的执行红线）；收口时同步改这条注释，别让注释跑在代码前面 |

---

## 14. 下一步（斌宝执行）

```bash
# 0. 确认 Nacos 在跑（本机有 HTTP_PROXY，curl 本地要加 NO_PROXY，否则看到的是代理的 502）
export NO_PROXY="127.0.0.1,localhost"

# 1. 备份 Nacos 现有配置（铁律：动 Nacos 前先备份）
D:/pycharm/python/python3.12.10/python.exe nacos-config/backup-nacos.py

# 2. 推送两份配置（识菜白名单 + ai.vision + multipart）
curl -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=gateway.yaml" --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode "type=yaml" --data-urlencode "content@nacos-config/gateway.yaml"
curl -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=dish-service.yaml" --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode "type=yaml" --data-urlencode "content@nacos-config/dish-service.yaml"

# 3. 起服务（顺序：Nacos → ai-service → dish/gateway → 前端）
cd ai-service && "C:/Users/K墨几/.workbuddy/binaries/python/envs/ai-service/Scripts/python.exe" app.py
# 探活：curl http://127.0.0.1:8090/health   → ollamaReachable 与 model 字段

# 4. 端到端：前端 /vision 上传两张图（建议拍胡萝卜/西兰花，COCO 能认）
#    预期：候选清单出现「胡萝卜 ×1 85%」+ unmapped 里出现其它 label；
#    勾选 → 生成 → 厨房页表单已预填「已从照片带入 N 样食材」
```

---

> 本文只做设计，不动代码。确认后按 §10 逐步实施，每步可独立验证。
