# AI 服务拆分方案：dish-service → Java（业务编排）+ Python/Flask（AI 推理中心）

> **修订记录**
> - v1（2026-10-15）：LLM 生成链路迁移方案
> - v2（2026-10-15）：定位升级为"推理中心"——新增 RAG 生成增强与 YOLO 视觉识别规划，明确解耦原则与分阶段路线图
> - v3（2026-10-15）：斌宝确认——YOLO **只做图片识别**（视频压力太大，明确不做），识别成功后展示候选项由用户勾选；
>   其余按 v2 执行；新增远期 Phase 4「推荐机器学习化」与数据量增长影响说明
> - v4（2026-10-15）：**Phase 1 代码落地**（实施记录见 §11）——`ai.generate.mode` 缺省 local，
>   未切换前现有行为逐字节不变；其余阶段仍是规划
>
> 结论先行：**能拆，而且边界很干净**——真正依赖模型的代码高度集中在 `dish-service` 的 `ai` 包内，
> 且已经按"调度 / 落库 / 调模型"分层，抽出模型层几乎不碰其他代码。
> Phase 1 已按斌宝确认实施；**其余阶段（RAG / YOLO / 推荐 ML）不动代码、不装依赖、不下载模型**。

---

## 1. 现状盘点：模型相关代码在哪

整个项目里"模型"只有一个落点：**AI 生成菜谱链路**（`POST /ai/dish` 异步任务）。
其余所谓"智能"都是规则公式（`dish_score`、威尔逊评分、发现流信号），没有模型。
所以这次拆的实质是：**把"调模型的那一层"从 dish-service 挪到独立的 Python 服务**。

| 组件 | 现在在哪 | 依赖什么 | 拆分后去向 |
|---|---|---|---|
| `prompts/dish-generate-system.txt` | dish-service 资源目录 | 无 | **→ Python** |
| `AiDishGenerator`（提示词拼装 + Ollama 调用 + 重试循环 + 营养校验） | ai 包 | Spring AI、`IngredientMapper`、`IngredientDirectory`、`ReferenceDishRetriever` | **→ Python**（拆成 `generator.py` + `ollama_client.py`） |
| `DishDraftValidator`（三道防线的第三道：格式/约束/一致性共 20+ 条规则） | ai 包 | `IngredientDirectory` | **→ Python**（`validator.py`） |
| `NutritionCalculator`（成分表 × 用量算六项） | ai 包 | `IngredientMapper` | **→ Python**（`nutrition.py`） |
| `ReferenceDishRetriever`（ES 检索相似菜谱 + 渲染进提示词） | ai 包 | `DishSearchService`(ES)、`DishMapper` | **检索先留 Java，渲染去 Python**；Phase 2 起重排归 Python（见 §4 RAG） |
| `DishTaskQueue` / `DishGenerationExecutor` / `DishTaskStateMachine` / `DishTaskCompletionService` | ai 包 | MySQL 任务表 | **留 Java**（队列、状态机、取消/重排，本来就不碰模型） |
| `DishDraftWriter`（事务落库 + 候选食材记录） | ai 包 | MySQL | **留 Java**（改用响应里带回的本地营养结果，不再自己算） |
| `GenerationRecorder`（生成记录落库） | ai 包 | MySQL | **留 Java**（meta 原样透传落库） |
| `DegradedModeGuard`（ES 挂了时限流） | ai 包 | ES ping | **留 Java**（判的是 ES，不是模型） |
| `IngredientDirectory`（食材主数据内存目录） | service 包 | `IngredientMapper` | **留 Java**，新增"快照导出"喂给 Python |
| `IngredientCandidateService`（表外食材候选） | service 包 | MySQL | **留 Java**（记录点在 writer） |
| `NutritionRecomputeService`（全库营养重算，批量） | ai 包 | MySQL + `NutritionCalculator` | **编排留 Java，计算改调 Python**（新开一个批量接口） |
| `AiProbeController`（Spring AI 兼容性探针） | ai 包 | Spring AI | **删**（实验结论已出，迁走更没用） |
| `spring-ai-ollama-spring-boot-starter` 依赖 | dish-service pom | — | **删**（Boot 3.2.4 非官方组合的隐患一并消除） |
| `es/eval/*.py`（实验脚本） | 项目根 | HTTP 打 dish | **留 Python**，之后可直接打 ai-service |

**关键观察**：`DishGenerator` 是个接口（`AiDishGenerator` 是唯一实现），上游
`DishGenerationExecutor` 只依赖接口。**替换实现 = 新增一个类 + 改一行配置**，
执行器、状态机、落库、发布、ES 同步一行不用动。

---

## 2. ai-service 的定位：推理中心

v1 里它是"菜谱生成服务"，v2 起定位升级为**推理中心**——所有"跑模型"的事都归它：

| 能力 | 归属 | 状态 |
|---|---|---|
| LLM 菜谱生成（提示词 + 校验 + 重试 + 营养） | ai-service (Python) | Phase 1 迁移 |
| RAG 检索增强（embedding 语义重排） | ai-service (Python) | Phase 2 |
| YOLO 图片识别（照片 → 候选，用户勾选） | ai-service (Python) | Phase 3（**先不下载任何依赖**） |
| 业务编排 / 事务 / 索引写入 / 发布 / 发现流 | dish-service (Java) | 不动 |
| 前端 / gateway / 鉴权 | 不动 | 不动 |

Java 网页与 Python 推理的**解耦原则**（四层，拆分的立论之本）：

1. **契约解耦**：Java 只认 HTTP JSON 契约，不关心背后是 gemma、YOLO 还是将来的云端 API。
   换模型 = 改 Python 一侧，Java 零改动。
2. **数据解耦**：Python 不写业务库、不存业务文件。主数据快照随请求注入，
   上传的图片由 dish-service 落盘后传路径。没有分布式事务问题。
3. **部署解耦**：独立进程、独立端口（8090）、独立发布节奏。ai-service 不进 gateway、
   不走 Nacos 注册（无状态计算服务不需要服务发现，配置化地址 + 健康检查足够）。
4. **演进解耦**：加模型（YOLO）、换模型（gemma→qwen→云端）不动 Java；
   Java 升级框架不动 Python。两边按自己的节奏迭代。

---

## 3. 三方案对比（为什么选"厚 Python"）

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 薄 Python | Python 只包"提示词 → Ollama → 原始 JSON"，校验/重试/营养全留 Java | 改动最小 | "AI 逻辑"的大头还在 Java，拆分意义不大；Spring AI 依赖还要留着 | ✗ |
| **B. 厚 Python（选定）** | **Python 负责整条"生成质量闭环"：提示词、调模型、校验、重试一次、营养本地计算与比对；Java 负责队列、状态机、落库、ES、发布** | 边界清晰；Java 专注并发编排与事务；提示词/规则迭代不再重启业务服务；为 RAG、YOLO 等后续模型留位 | 多一个部署单元；validator 要移植（有单测可对照） | **✓** |
| C. 全迁 | 连 ES 检索、发现流排序也搬去 Python | Python 统一 AI 栈 | ES DSL 是踩过坑的（terms 默认 OR、status 过滤），duplicated 一份风险大于收益；发现流强依赖 MySQL+ES 现状 | ✗（RAG 化后可局部评估） |

---

## 4. RAG 生成增强设计（Phase 2）

### 4.1 现状：RAG 的雏形已经有了

`ReferenceDishRetriever` 就是最朴素的 RAG：ES 关键词召回相似菜谱 → 渲染进提示词。
短板也明确：纯词汇匹配，"西红柿/番茄"靠别名表兜，"想做个下饭的"这种语义表达完全检索不到。

### 4.2 升级路线：两段式（召回 + 语义重排）

| 阶段 | 召回 | 重排 | 说明 |
|---|---|---|---|
| Phase 2a | 沿用 ES 关键词（现链路，失败无害） | **Python embedding 重排**：对召回的候选按语义相似度排序，取 top-3 注入提示词 | 不 duplicated ES DSL；embedding 失败自动退回纯关键词参考，不影响生成 |
| Phase 2b（可选） | 召回也归 Python（直连 ES 或自持向量索引） | 同上 | 需要评估：ES 加 `dense_vector` 字段要改 mapping 重建索引；或 FAISS/Chroma 本地向量库（无新中间件） |

- embedding 选型：sentence-transformers 中文小模型（如 bge-small-zh，百 MB 级，CPU 可跑，
  GPU 更快），**具体型号待实测选型**。
- **知识库范围待定**：A 仅菜谱（ES 现有索引）；B 菜谱 + 烹饪规则文档（文档分块入向量库）。
  "生成菜的规则用 RAG 实现"如果指后者，需要先有规则文档的整理与分块方案（见 §9 遗留问题）。
- 收益：语义泛化（同义词/别名表管不过来的）、规则类知识可注入、为发现流的
  `TasteProfileSignal`（空实现）打基础。

---

## 5. YOLO 图片识别设计（Phase 3，视频不做，先不下载）

### 5.1 场景（已确认）

拍一张照片 → 识别其中的食材/菜品 → **展示候选项（名称 + 数量 + 置信度），由用户勾选要用哪些** →
带入生成输入（一键生成）或搜索定位。**视频识别压力太大，明确不做。**

用户勾选是必要环节：识别是辅助不是权威，勾选动作同时解决了误识别兜底和"用哪些"的决策。

### 5.2 接口契约（草案）

```
POST /api/v1/vision/dishes
请求：{ "requestId": "...", "mediaType": "image",
       "mediaPath": "/upload/2026/10/xx.jpg" }   // dish-service 落盘后的路径
响应：{ "ok": true,
        "candidates": [ { "label": "番茄", "count": 2, "confidence": 0.87,
                          "mappedId": "89", "mappedName": "番茄",
                          "cropImage": "/upload/.../crop_1.jpg" } ],
        "unmappedLabels": [] }
```

- **label → 库内 ID 的映射在 Java 侧**：食材走 `IngredientDirectory` 归一；
  菜品按 `name_key` 匹配 `t_dish`。需要类别定义表（如 `t_vision_label`：label、类型、引用 ID），
  新增主数据，归 Java。
- **裁剪小图（cropImage）可选**：把检测框裁出来随候选返回，用户看得见"它认的是哪一块"，勾选更有依据。
- **前端流程**：上传 → 候选网格（裁剪图 + 名称 + 数量 + 置信度，高置信度默认勾选）→
  用户改勾选 → 确认 → 已选项作为 `ingredients` 进 `/ai/dish`，或作为关键词进搜索。

### 5.3 模型与数据的现实

- ultralytics YOLOv8/v11 微调；**COCO 预训练没有中式菜品类**，必须自建数据集。
- 数据集成本：食材（89 类，且斌宝后期会加数据）类少、可自拍/爬虫攒料；
  菜品（153 类）标注按周算。**路线：Phase 3 先食材 MVP，菜品识别作 Phase 3.5**，数据量上来后再评估。
- GPU：RTX 4060 Laptop 8G，**单张图片推理是秒级**，与 gemma 抢显存的压力远小于视频场景；
  仍注意推理完释放显存。（2026-10-16 实测：cuda 可用，yolov8n 单图 4.4ms）
- 依赖量级：torch + ultralytics，CPU 版数百 MB、GPU 版 GB 级。
  **当前状态**：torch/torchvision（cu124 本地 whl）已装入 venv，权重已归位，
  只剩 ultralytics 一装（命令见 requirements.txt）；斌宝 2026-10-16 明确先不折腾架包。

### 5.4 失败与降级

- 低置信候选照常返回但标注"待确认"；未匹配 label 进 `unmappedLabels` 明示。
- ai-service 不可用：返回错误，前端回退手动选择——**识别是加速项，不是必经环节**。

### 5.5 数据量增长的影响（斌宝后期会增加菜品/食材数据）

- 食材目录快照随请求注入：89 → 数百行也只是几十 KB，无感；`IngredientDirectory` 全量内存同理。
- validator 的 `canonicalNames` 扫描随食材数线性增长，数百量级无压力。
- YOLO 类别数 = 数据量的直接成本：食材类增长慢，菜品类增长会显著推高标注工作量——再次支持先食材。
- ML 推荐（Phase 4）：菜品/交互数据增长是模型效果的燃料，数据越多越该上。
- ES 索引：菜品量增长后全量重建耗时上升，`/ai/es/reindex` 仍够用；到万级再评估增量工具。

---

## 6. Phase 1 接口契约（LLM 生成，v1 内容保留）

**`POST /api/v1/generate`**（Java → Python，同步）

```json
{
  "requestId": "task-123",
  "ingredients": [{"id": "123", "name": "番茄"}],
  "tastes": ["家常"],
  "servings": 2,
  "constraints": {
    "mustUseIngredientIds": [], "mustUseIngredients": [],
    "excludeIngredientIds": [], "excludeIngredients": ["花生"],
    "maxCookTimeMin": 30, "availableEquipment": ["炒锅"]
  },
  "directory": [
    {"id": "89", "name": "番茄", "alias": "西红柿,tomato", "category": "蔬菜",
     "calorie": 18, "proteinGram": 0.9, "fatGram": 0.2,
     "carbGram": 3.9, "fiberGram": 1.2, "sodiumMg": 5}
  ],
  "references": [
    {"dishId": "456", "name": "番茄炒蛋", "mains": ["鸡蛋", "番茄"],
     "missing": ["葱"], "tasteTags": ["家常"], "cookTimeMin": 10,
     "stepHints": ["鸡蛋打散，加少许盐", "番茄切块，下锅炒出汁"]}
  ],
  "options": {"model": "gemma4:e2b", "temperature": 0.7,
              "maxAttempts": 2, "timeoutSeconds": 600}
}
```

响应（成功）：

```json
{
  "ok": true,
  "draft": { "name": "...", "summary": "...", "prepTimeMin": 10, "cookTimeMin": 15,
             "difficulty": 1, "spicyLevel": 0,
             "ingredients": [{"name": "番茄", "amountGram": 200}],
             "steps": ["..."], "seasonings": ["..."], "equipment": ["炒锅"],
             "chefTips": ["..."], "dietTags": ["..."], "substitutes": ["..."],
             "nutritionPerServing": {"calories": 210, "proteinGram": 8, "...": "..."} },
  "nutritionVerified": true,
  "localNutrition": {"trustable": true, "calories": 198.5, "proteinGram": 8.2,
                     "fatGram": 9.1, "carbGram": 15.3, "fiberGram": 1.8, "sodiumMg": 320},
  "attempts": 1, "costMs": 15231, "rawOutput": "...",
  "validation": {"problems": [], "constraintProblems": []},
  "meta": {"model": "gemma4:e2b", "promptVersion": "1a2b3c4d",
           "temperature": 0.7, "maxAttempts": 2, "referenceDishIds": ["456"]}
}
```

响应（失败）：`ok: false` + `errorKind`（见 §7）+ 同样的 attempts/rawOutput/validation/meta。

**`POST /api/v1/nutrition`**（给 `NutritionRecomputeService` 全库重算用，批量；同样带 directory 快照）

```json
{"directory": [{"id": "89", "name": "番茄", "alias": "西红柿", "category": "蔬菜",
                "calorie": 18, "protein": 0.9, "fat": 0.2, "carb": 3.9,
                "fiber": 1.2, "sodium": 5}],
 "items": [{"dishId": "101", "servings": 2,
            "ingredients": [{"name": "番茄", "amountGram": 200}]}]}
→ {"results": [{"dishId": "101", "trustable": true, "calories": 20.0, "...": "..."}]}
```

**`GET /health`** → `{"status": "up", "model": "gemma4:e2b", "promptVersion": "...", "ollamaReachable": true}`
（根路径 `/health` 与 `/api/v1/health` 双挂——探活走根是惯例）

> 字段名说明：`constraints` 用 Java `GenerateConstraints` record 的原字段名直接序列化，
> 不做二次映射 —— 少一处翻译就少一处对不上的可能。

### 6.1 ai-service 工程结构（Phase 1 落地时）

```
ai-service/
├── app.py                  # Flask 入口，注册蓝图、统一错误处理
├── config.yaml             # model / temperature / maxAttempts / timeoutSeconds / 并发信号量
├── requirements.txt        # flask, requests (或 httpx), gunicorn, pyyaml, pytest
├── prompts/dish-generate-system.txt   # 从 dish-service 原样搬来
├── ai_service/
│   ├── routes.py           # POST /api/v1/generate、POST /api/v1/nutrition、GET /health
│   ├── generator.py        # 重试循环（移植 AiDishGenerator.generate）
│   ├── ollama_client.py    # POST http://127.0.0.1:11434/api/chat，format=json，带超时
│   ├── validator.py        # 移植 DishDraftValidator 全部规则
│   ├── nutrition.py        # 移植 NutritionCalculator + 调料热量补偿 + 偏差校验
│   ├── prompt.py           # 系统提示词加载 + MD5 前 8 位版本号 + 用户消息拼装
│   └── schemas.py          # 请求/响应 dataclass + 校验
└── tests/                  # pytest：validator 规则逐条对照（见 §8 步骤 2）
# Phase 2 增加：rag/（embedding 重排）；Phase 3 增加：vision/（YOLO 图片识别）
```

### 6.2 Java 侧改动（全部在 dish-service 内）

| 改动 | 说明 |
|---|---|
| 新增 `RemoteDishGenerator implements DishGenerator` | Spring `RestClient` 调 `ai-service`；请求体按 §6 组装；响应映射回 `GenerateResult`；`localNutrition` 一并带回 |
| `DishDraftWriter` 少算一次营养 | 改用 `result.localNutrition()`，不再注入 `NutritionCalculator`（生成路径） |
| `IngredientDirectory` 加 `snapshot()` | 导出 `id/name/alias/category/六项营养`，随请求传给 Python |
| references 组装 | 放 `RemoteDishGenerator` 内（先检索再组请求），`DishGenerationExecutor` 不变 |
| 配置开关 | Nacos `dish.yaml` 增加 `ai.generate.mode: local｜remote`、`ai.remote.base-url`、`ai.remote.token`、`ai.remote.read-timeout-seconds: 660`。**`mode=local` 保留旧实现作为回滚开关** |
| 删依赖 | pom 去掉 `spring-ai-ollama-spring-boot-starter` 与 BOM；删 `AiProbeController` |
| `NutritionRecomputeService` | 循环改批量：按 100 道一批调 `/api/v1/nutrition`，其余逻辑（更新字段、登记 ES 同步）不变 |

### 6.3 超时链与错误分类

**三层超时，从内到外必须递增**，否则 Java 先断，Python 还在跑，错误信息全部错位：

| 层 | 默认值 | 说明 |
|---|---|---|
| Python → Ollama | `timeoutSeconds`（默认 600，沿用现有实测结论） | 兜"真的卡死"，不把并发变慢判失败 |
| gunicorn/Flask worker | 630s | 必须大于上层 |
| Java `RestClient` read timeout | 660s | 留 30s 给序列化和网络余量 |

**错误分类**（决定 `error_msg` 怎么写、用户看到什么）：

| errorKind | 触发条件 | 用户可见信息 |
|---|---|---|
| `model_unreachable` | 连不上 Ollama / ai-service 无响应 | "AI 服务暂时不可用，请稍后重试" |
| `timeout` | 超过 `timeoutSeconds` | 沿用现有"模型响应超时"文案 |
| `validation_exhausted` | 两次尝试都没过校验 | 沿用现有"没能想出合适做法"+ 约束提示 |
| `internal` | Python 5xx / 响应解析失败 | "AI 服务异常"，日志记完整响应 |

---

## 7. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| validator 移植漏规则 | 生成质量静默劣化 | pytest 对照用例全绿才切流；`es/eval` 指标对比验收 |
| ai-service 挂了 | 生成失败（Phase 3 后连带识别失败） | `/health` + 明确错误分类 + `mode=local` 一键回滚（回滚只覆盖生成，识别无旧实现可回退——前端手动兜底） |
| 超时链断裂 | 错误信息错位、僵尸任务 | 三层超时写进配置注释，改值必须三层一起改 |
| 提示词版本口径漂移 | 实验批次不可比 | MD5 逻辑随文件一起迁，Java 不再参与版本计算 |
| 食材快照过期 | 归一分辨失败 | 快照随请求带，无缓存；转正后下一请求生效 |
| Ollama 并发被打满 | 全体变慢误判超时 | Python 侧信号量限并发（默认 2，与 Java 队列并发度对齐） |
| RAG：embedding 中文效果不达标 | 参考质量无提升 | 先实测选型再上线；重排失败自动退回关键词参考 |
| RAG：向量入库要改 ES mapping | 索引重建、影响 Java 写入 | Phase 2a 不改索引；2b 评估 FAISS/Chroma 本地库绕开 |
| YOLO：数据集标注成本 | 项目周期失控 | 先食材（89 类）MVP；菜品识别单独排期；预训练模型 + 少量数据微调先验证效果 |
| YOLO：与 gemma 抢显存 | 推理变慢 | 图片推理秒级、压力小；小模型（yolov8n 级）+ 推理完释放显存 |
| YOLO：误识别带入错误输入 | 用户要手动改 | 勾选机制天然兜底（高置信默认选、用户可改）；`unmappedLabels` 明示 |
| 简历/面试口径与代码不一致 | 面试翻车 | 每阶段完成后同步 `docs/interview/` 两份文档 |

---

## 8. 分阶段路线图与工作量

| 阶段 | 内容 | 估时 | 前置 |
|---|---|---|---|
| Phase 1 | LLM 生成链路迁移 —— **代码已落地**（§11），剩余切流联调/批量改道/清理/文档 | 3~4 天 | 确认本方案 |
| Phase 2 | RAG 两段式：embedding 重排（选型实测 → 上线 → 失败退回） | 3~5 天 | Phase 1 完成 |
| Phase 3 | YOLO 食材识别 MVP（数据收集/标注 → 微调 → `/api/v1/vision/dishes` → 前端候选项勾选回填） | 2~3 周（大头是数据） | Phase 1 完成；**依赖装不装到时再定** |
| Phase 3.5 | YOLO 菜品识别（数据集就绪、数据量上来后） | 待评估 | Phase 3 验收 |
| Phase 4（远期） | 推荐机器学习化：行为数据训练推荐模型，增强发现流精排（见 §10） | 待评估 | Phase 1 完成 + 行为数据积累 |

**Phase 1 实施步骤**（每步可独立验证、可回滚）：
1. 搭骨架：`ai-service/` Flask 工程 + `/health` + 提示词原样搬入。
2. 移植 + 对照测试：validator / nutrition / generator 逐条移植；Java 侧 `DishDraftValidator`
   现有单测搬成 pytest 参数化用例，**同一批 draft 两边结论必须一致**，全绿之前不删 Java 代码。
3. Java 新增 `RemoteDishGenerator` + `mode` 开关 + `IngredientDirectory.snapshot()`。
4. 切流联调：跑 `POST /ai/dish` 全链路；核对生成记录字段与迁移前一致。
5. 批量营养重算改道 `/api/v1/nutrition`，抽查偏差样本一致。
6. 实验对比：`es/eval` generation 实验，格式通过率/约束违反率/重试次数不劣化才继续。
7. 清理：删 spring-ai 依赖与 BOM、删 `AiProbeController`；`AiDishGenerator` 留 `local` 开关。
8. 文档同步：`docs/architecture.md` + `docs/interview/` 两份面试口径。

---

## 9. 已确认决策与遗留问题

**已确认（2026-10-15，斌宝）**：
1. YOLO 只做**图片**识别，视频不做（压力太大）。
2. 识别成功后**展示候选项，由用户勾选**用哪些（带入生成输入或搜索）。
3. 其余按 v2 方案执行（厚 Python、RAG 两段式、分阶段路线）。
4. 后期增加菜品/食材数据量；远期把菜品推荐升级为**机器学习模型增强**（§10）。

**遗留待定**：
- RAG 知识库范围：只检索菜谱，还是菜谱 + 烹饪规则文档？（影响分块方案，不阻塞 Phase 1/2a）
- Phase 4 推荐模型形态：双塔 / 协同过滤 / LTR，启动时按数据量选型。

---

## 10. 远期：推荐机器学习化（Phase 4）

现状：发现流是规则引擎（`IngredientMatchSignal` / `QualitySignal` / `ContextSignal`，
`TasteProfileSignal` 空实现）。斌宝规划：后期把菜品推荐升级为机器学习模型增强。

### 10.1 训练数据（现成的）

`t_dish_push_log`（推送/浏览）、`t_dish_collect`（收藏）、`t_dish_rating`（评分）——
行为数据都在 MySQL，由 Java 侧批量导出快照喂给 Python（遵守"Python 不写业务库"）。

### 10.2 形态候选（启动时按数据量选型）

- **LTR 精排**（最稳切入点）：在现有发现流候选集上做 learning-to-rank，召回不动；
- **协同过滤**：数据量中等时最简单、可解释；
- **双塔模型**（user tower + item tower + faiss）：数据量大后上位，embedding 召回。

### 10.3 与现有架构的关系

- 召回不变（发现流/ES），ML 只做精排增强 → **规则是 baseline，ML 是 enhancement，可 A/B、可回滚**。
- `TasteProfileSignal` 空实现正好是 ML 口味偏好的落点。
- 服务形态：ai-service 增加 `recommend/` 模块，离线训练（脚本/notebook）+ 模型文件存 Python 侧
  + 在线推理 REST。
- 数据量增长（斌宝规划）直接提升这类模型效果，是 Phase 4 的前置燃料。

---

## 11. 实施记录（Phase 1，2026-10-15 代码落地）

**已落地**（`ai.generate.mode` 缺省 = local，未切换前线上行为逐字节不变）：

**Python 侧（`ai-service/`）**：
- `app.py` / `config.yaml` / `requirements.txt` / `prompts/dish-generate-system.txt`
  （原样拷贝，MD5 `076b89e0` 与库内记录一致，实验批次口径不断）
- `ai_service/`：`schemas`（请求/响应结构）、`directory`（快照目录，正式名优先）、
  `prompt`（版本号 + 用户消息 + 参考渲染）、`validator`（DishDraftValidator 全规则移植）、
  `nutrition`（成分表计算 + 调料补偿 + 偏差校验）、`ollama_client`（`trust_env=False` 显式绕代理）、
  `generator`（重试循环 + 信号量限并发）、`routes`（generate / nutrition / health）
- `tests/`：**45 个 pytest 全绿** —— Java 三大单测（validator 29、nutrition 8、generator 3）
  逐条对照移植，另补重试行为与目录行为用例

**Java 侧（`dish-service/`）**：
- 新增 `RemoteDishGenerator`（RestClient，读超时 660s）、`RemoteProperties`（`ai.remote.*`）、
  `DishGeneratorConfig`（mode 二选一，local 默认）、`IngredientSnapshot` +
  `IngredientDirectory.snapshot()`
- `AiDishGenerator` 去掉 `@Component`（改由配置类注册，两个实现不会同时成为 bean）
- `application.yml` / `nacos-config/dish-service.yaml` 补 `mode` 与 `ai.remote.*` 默认值
- **`mvn test`：138/138 全绿**（改造未碰坏现有行为）

**冒烟（Ollama 未起状态下完成）**：
- `/health` → `up`，`promptVersion=076b89e0`，`ollavaReachable=false`
- `/api/v1/generate` → `ok:false` + `model_unreachable`，**立即返回不卡超时**，meta 完整带回
- `/api/v1/nutrition` → 116.4 kcal（与 Java 单测期望一致）、coverage 0.5 判不可信、
  别名「西红柿」正确解析到番茄

**与初版文档的差异（以代码为准）**：
- `constraints` 用 Java record 原字段名（`mustUseIngredientIds` 等），不二次映射
- `/api/v1/nutrition` 请求也带 `directory` 快照（Python 不连库）
- `/health` 根路径与 `/api/v1/health` 双挂
- `DishDraftWriter` 仍用 Java 侧重算营养（响应里的 `localNutrition` 已留好未消费）
- Python 用 `requests.Session(trust_env=False)` 直连 Ollama：开发机的 `HTTP_PROXY`
  会把发往 127.0.0.1 的请求送代理，症状是"代理连不上上游"，不关会完全找错方向

**运行方式**：开发 `cd ai-service && python app.py`；常驻
`gunicorn -w 2 --threads 8 -b 127.0.0.1:8090 app:app`。
测试：`cd ai-service && python -m pytest tests/`。

**venv（2026-10-16 重建）**：ai-service 专用隔离环境
`C:\Users\K墨几\.workbuddy\binaries\python\envs\ai-service`（**Python 3.12.10**，
用本机解释器 D:\pycharm\python\python3.12.10 创建，site-packages 隔离、不污染系统 Python）。
45 个 pytest 已在该环境复跑通过。旧的 3.13 `envs\default`  venv 已弃用（可删）。
迁 3.12 的原因：YOLO 的 torch/torchvision whl 是 cp312 定制版（cu124），3.13 装不了。

**YOLO 环境现状（2026-10-16，斌宝已下载好安装包）**：
- ✅ `torch 2.6.0+cu124` / `torchvision 0.21.0+cu124` 已装入上述 venv
  （本地 whl：`D:\explorer\NeatDM\` 下两个文件；斌宝已实测 `device='cuda:0'`、
  单图推理 4.4ms）
- ✅ 权重归位：`ai-service/yolo_model/weights/yolov8n.pt`（MD5 与源文件一致）；
  目录结构 `yolo_model/{weights,datasets}/`，训练产物与数据集之后都落这里
- ⬜ `ultralytics==8.4.177` 及剩余依赖未装——补装命令见 `ai-service/requirements.txt`
  末尾注释（走清华镜像，直连 PyPI 会被代理掐断）
- 下一步仍是 §5.3 的数据集：89 类食材 MVP，标注按周算，那才是 Phase 3 的本体

**Phase 1 剩余事项**（需 Ollama 起来后才能继续）：
1. 起 Ollama + ai-service，Nacos 把 `ai.generate.mode` 切 `remote`，跑 `POST /ai/dish`
   全链路，核对生成记录字段与迁移前一致
2. `NutritionRecomputeService` 循环改批量调 `/api/v1/nutrition`
3. `es/eval` generation 实验对比迁移前后指标，不劣化才继续
4. 清理：删 spring-ai 依赖与 BOM、删 `AiProbeController`
5. 文档同步：`docs/architecture.md` + `docs/interview/` 两份面试口径

---

> 剩余阶段实施顺序严格按 §8 走，每步可独立验证、可回滚。
> **YOLO 相关依赖与模型在 Phase 3 启动前不下载、不安装。**
