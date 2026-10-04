# 菜品检索：Elasticsearch 落地方案

> 配套 `sql/dish.sql`（MySQL 侧数据模型）一起看。
> **MySQL 是 source of truth，ES 只是检索副本** —— 这条是下面所有设计的前提。

---

## 一、版本与依赖

### ⚠️ 当时手上的是 ES 7.16.2（历史记录，先读这段）

> **状态更新**：这一节记录的是当时的服务端环境 —— 本机解压版 ES 7.16.2，该环境**已退役、不会再启动**。
> 现在 ES 只跑在 Docker 里：名为 `es` 的容器（`docker.1ms.run/elasticsearch:8.16.0`，单节点、无 security、纯 http）。
> 当时"不引 Spring Data ES、手写 `RestClient`"（下面方案 B）这个判断**事后被验证是有效的**：
> 从 7.16.2 换到 8.16.0，**业务代码一行都没改**。
> 下面的内容保留原样，作为当时的决策与踩坑记录。

**Boot 3.2.4 + Spring Data ES 5.2 + ES 7.16.2 是官方没有任何保证的组合**，三个原因：

1. Spring Data ES 5.2.x 官方声明的兼容目标是 **8.11.1**，对 7.x 未做承诺
2. Elastic 官方对版本差的表述是：**forward compatible 有保证**（客户端可用于更高版本服务端）；
   反过来，**8.x 客户端连 7.x 服务端属于 *"only backwards compatible … without guarantees
   made"* —— 明确不保证**
3. **Boot 3.2.4 只能用 Spring Data ES 5.2.x**（Spring Data 2023.1 BOM 决定）。
   想降到支持 7.17 的 4.4.x 会连带破坏 Spring Framework 6.1 兼容 ——
   4.4.x 是 Spring 5.3 时代的产物，跨不过 Boot 3 这条线

补充两个事实：
- `elasticsearch-java`（官方 Java API Client）的 7.x 分支**只维护到 7.17**，
  7.16 那版是 **beta**（随 7.16 首发，7.17 才 GA）
- ES 7.16 已于 2022 年停止维护，7.17 是 7.x 的最后一个 minor

**当时的三条路**

| 方案 | 服务端 | 客户端 | 代价 |
|---|---|---|---|
| **A（推荐）** | 升到 8.11.x | `spring-boot-starter-data-elasticsearch`（自动 5.2.x） | **只改一个 docker 镜像 tag** |
| **B** | 保留 7.16.2 | 不引 Spring Data，用 Spring `RestClient` 直接发 JSON | 手写 JSON 与解析，无类型安全 |
| **C** | 保留 7.16.2 | `co.elastic.clients:elasticsearch-java:7.16.2` | 要显式覆盖 Boot BOM 版本（踩过的坑）+ 吃到 beta API |

**为什么推荐 A**：索引本来就要新建，**没有数据迁移成本**；改的只是一个镜像 tag；
而且 8.x 是主流，学习价值更高。这条路最省事。

**为什么 B 也完全可行**：ES 7.x 与 8.x 的 **REST 层查询 DSL 基本一致**，
所以本文件第三、四节的 mapping 和查询**一个字都不用改**。走 B 只是放弃 Spring Data 的
Repository 抽象 —— 而你要写的本来就是手写 DSL，对当前场景没有实际损失。

**走 B / C 时的 Docker 差异**：ES 7.x 默认**不开启 security**，
所以那条 `xpack.security.enabled=false` 可以去掉，镜像换成
`docker.elastic.co/elasticsearch/elasticsearch:7.16.2` 即可。

**为什么 mapping 和 DSL 三条路通用**（都验证过版本起点）：

| 用到的特性 | 起始版本 | 7.16.2 可用 |
|---|---|---|
| `terms_set` 查询 | 6.1 | ✅ |
| `function_score` + `gauss` 衰减 | 1.x | ✅ |
| `search_after` 深分页 | 5.0 | ✅ |
| 无 type 的扁平 mapping | 7.0（mapping type 已废弃） | ✅ |
| `byte` / `double` / `keyword` / `date` 类型 | 通用 | ✅ |

**结论：mapping、查询、打分三块设计可以直接用，只需要重新决定「怎么连上去」。**

### 版本对应（已核实 Spring Data 官方 Version Matrix）

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 3.2.4（项目现状） | 不需要改 |
| Spring Data Elasticsearch | **5.2.x** | 属 Spring Data 2023.1 "Vaughan" |
| Elasticsearch 客户端 | **8.11.1** | 由 Boot 父 pom 管理 |
| Elasticsearch 服务端 | **8.11.x**（推荐 8.11.4） | 与客户端对齐，别用 8.12+ |

**依赖只要一行**，版本交给父 pom 管（本项目踩过"手动写 version 覆盖 BOM"的坑，别再来一次）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

### 本地起 ES（Docker）

**项目现在跑的就是这个容器**：容器名 `es`，镜像 `docker.1ms.run/elasticsearch:8.16.0`
（与 `elasticsearch:8.16.0` 是同一个镜像）。宿主端口映射 `9200:9200`、`9300:9300`，
连接地址仍是 `http://127.0.0.1:9200` —— **业务配置无需改动**。
数据落在名为 `es_data` 的数据卷里，集群名 `docker-cluster`。

```bash
docker run -d --name es \
  -p 9200:9200 -p 9300:9300 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  -e "ES_JAVA_OPTS=-Xms1g -Xmx1g" \
  docker.1ms.run/elasticsearch:8.16.0
```

- **`xpack.security.enabled=false` 必须加**：ES 8.x 默认开启安全认证 + 自签 HTTPS，
  不加的话客户端连不上，会报 SSL / 401 一类让人摸不着头脑的错。本地学习环境关掉最省事。
- **漏掉这两个环境变量会被误判成"网络不通"**：现在这个容器是显式关掉 security 的，所以 http 免密可用；
  重建容器时若漏了 `discovery.type` / `xpack.security.enabled`，症状就是 TLS 握手失败或 401。
- 内存给 1g 就够，别把 32G 机器的一半喂给它。

### 配置（Nacos 侧 `dish-service.yaml`）

```yaml
spring:
  elasticsearch:
    uris: http://127.0.0.1:9200
    connection-timeout: 3s
    socket-timeout: 30s
```

---

## 二、索引 Mapping

设计要点：
1. **食材用 `long` 数组**（存 `ingredient_id`），不用字符串、不用 nested
2. **主料单列一个字段**，召回时给更高权重
3. **`status` 必须进索引** —— 否则召回会带出草稿
4. 菜名用 `text` + `keyword` 双字段，为后续模糊搜索留口（需要 `ik` 插件，第一版可不装）

### 创建索引

```json
PUT /dish_index
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "refresh_interval": "1s"
  },
  "mappings": {
    "properties": {
      "dishId":            { "type": "long" },
      "name":              { "type": "text",
                             "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } } },
      "nameKey":           { "type": "keyword" },
      "status":            { "type": "byte" },
      "deleted":           { "type": "byte" },
      "source":            { "type": "byte" },
      "creatorId":         { "type": "long" },

      "ingredientIds":     { "type": "long" },
      "mainIngredientIds": { "type": "long" },
      "ingredientCount":   { "type": "integer" },

      "tasteTags":         { "type": "keyword" },
      "tagIds":            { "type": "long" },

      "baseServing":       { "type": "integer" },
      "difficulty":        { "type": "byte" },
      "cookTimeMin":       { "type": "integer" },

      "ratingAvg":         { "type": "double" },
      "ratingWilson":      { "type": "double" },
      "ratingCount":       { "type": "integer" },
      "collectCount":      { "type": "integer" },
      "viewCount":         { "type": "integer" },

      "energyKcal":        { "type": "double" },
      "publishTime":       { "type": "date" }
    }
  }
}
```

**为什么不用 nested？** nested 能表达"某个食材的用量 > 100g"这种**字段间配对**的条件，
代价是查询慢、写入贵。而召回阶段只需要回答"含不含某食材"，扁平数组完全够。用量去 MySQL 读。

### 关于 ik 分词器：现在需要装吗？

**不需要。** 而且原因很明确，不是"偷懒少装一个插件"：

`ik` 这类中文分词器**只对 `text` 类型字段生效**。而主召回链路的查询条件是
把 `term` 打在 `ingredientIds`（**`long` 数组**）上 —— **数值字段压根不走分词器**。

换句话说：**ik 服务的是"中文全文搜索"，而你选食材走的是"结构化 ID 精确匹配"，
两者不在同一条路径上。** 主链路一个分词器都不需要。

#### 什么时候才需要

只有一个场景：**菜名的模糊搜索**（用户在搜索框输入"番茄炒蛋"，要命中菜名）。
那时 `name` 字段是 `text` 类型，才轮到分词器上场。

**没有中文分词器会怎样**：ES 默认的 `standard` 分词器对中文是**按字切分** ——
"番茄炒蛋" → `番 / 茄 / 炒 / 蛋`。结果是搜"番茄"会命中所有带"番"或"茄"的菜，
**误召回爆炸、精度很差**。所以真要做中文搜索，中文分词器确实跑不掉。

#### 那时怎么选

| 方案 | 安装方式 | 版本对齐 | 效果 |
|---|---|---|---|
| **`analysis-smartcn`** | `bin/elasticsearch-plugin install analysis-smartcn`（**官方源，一条命令**） | ✅ 自动跟随 ES 版本 | 词典较小，菜名搜索够用 |
| **`ik`** | 手动下载 zip / `install <url>` | ⚠️ 必须与 ES 版本**严格一致** | 词典大、效果好，**支持自定义词典** |

**已确认 ik 有 8.11.4 对应版本**（infinilabs 的 release 列表里包含 `v8.11.4`）：

```bash
# 方式一：plugin cli（注意下面那个坑）
bin/elasticsearch-plugin install https://get.infini.cloud/elasticsearch/analysis-ik/8.11.4

# 方式二：下载 zip 解压后整体放进 plugins/ik/（推荐，目录结构完整）
# https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.11.4.zip
```

**⚠️ 装 ik 的一个真坑**：用 `elasticsearch-plugin install` 方式装，
**只会装 jar 包，`config` 目录里的词典文件不会带上**，启动后启动或调用时可能报：

```
Cannot invoke "org.wltea.analyzer.dic.DictSegment.match(char[], int, int)"
because "org.wltea.analyzer.dic.Dictionary.singleton" is null
```

→ 这也是**方式二更稳**的原因：整目录复制，`plugin-descriptor.properties` 和
`config/` 一起进去，结构完整。

**为什么美食项目最终可能还是会选 ik**：ik 支持**自定义词典**
（`config/IKAnalyzer.cfg.xml` + 扩展词典文件），你可以把"佛跳墙""狮子头""蚂蚁上树"
这类菜名词条灌进去，中文搜索命中率会明显好于通用词典 —— 这对你的场景是实打实的收益。

**结论：现在不装；等做菜名搜索时再装，优先 ik。**

---

## 三、核心查询：这里有个必踩的坑

### ⚠️ `terms` 查询默认是 OR，不是 AND

这是 90% 的人第一次用 ES 做"包含全部"时踩的坑：

```json
// ❌ 错误理解：以为这是"必须含 101 和 102 和 103"
// ✅ 实际语义：含 101 或 102 或 103 任意一个 —— 这是 OR 召回
{ "terms": { "ingredientIds": [101, 102, 103] } }
```

用户选了 3 种食材，你期望"必须都有"，结果 ES 给了"有任意一种的菜"，结果集会大得离谱。

### ✅ 正确写法一：多个 `term` 放 `must`（推荐，最直观）

```json
POST /dish_index/_search
{
  "query": {
    "bool": {
      "filter": [
        { "term": { "status": 1 } },
        { "term": { "deleted": 0 } }
      ],
      "must": [
        { "term": { "ingredientIds": 101 } },
        { "term": { "ingredientIds": 102 } },
        { "term": { "ingredientIds": 103 } }
      ]
    }
  },
  "size": 20
}
```

- `filter` 不参与打分，还能走缓存 —— status/deleted 这类**纯过滤条件一律放 filter**
- `must` 里每个 `term` 是独立条件，全部满足才算命中 → **这就是"硬约束"**
- 食材数量是动态的，代码里循环拼 `must` 数组即可

### ✅ 正确写法二：`terms_set`（ES 自带的"至少匹配 N 个"）

```json
{
  "query": {
    "bool": {
      "filter": [ { "term": { "status": 1 } } ],
      "must": [
        {
          "terms_set": {
            "ingredientIds": {
              "terms": [101, 102, 103],
              "minimum_should_match_script": { "source": "params.num_terms" }
            }
          }
        }
      ]
    }
  }
}
```

`params.num_terms` 是 ES 内置变量，等于传入的 terms 数量 → **必须全部匹配**。
想改成"至少匹配 2 个"，把 `source` 换成 `"Math.min(params.num_terms, 2)"`。

### 软匹配（允许缺食材，用于"相似推荐"）

当硬约束召回 0 条时的降级路径：

```json
{
  "query": {
    "bool": {
      "filter": [ { "term": { "status": 1 } } ],
      "should": [
        { "terms": { "mainIngredientIds": [101, 102, 103], "minimum_should_match": 2, "boost": 3 } },
        { "terms": { "ingredientIds":     [101, 102, 103], "minimum_should_match": 2 } }
      ],
      "minimum_should_match": 1
    }
  }
}
```

注意 `mainIngredientIds` 给的 `boost: 3` —— 命中主料的菜得分更高，这就是前面说的"主料权重"。

---

## 四、加权打分：用 `function_score` 一次算完

ES 自带的 BM25 是为文本设计的，对数值型的食材 ID 没意义。
要把「评分 + 热度 + 新鲜度」掺进排序，用 `function_score`：

```json
POST /dish_index/_search
{
  "query": {
    "function_score": {
      "query": {
        "bool": {
          "filter": [
            { "term": { "status": 1 } },
            { "term": { "deleted": 0 } }
          ],
          "must": [
            { "term": { "ingredientIds": 101 } },
            { "term": { "ingredientIds": 102 } }
          ]
        }
      },
      "functions": [
        {
          "field_value_factor": {
            "field": "ratingWilson",
            "factor": 10,
            "missing": 0
          }
        },
        {
          "field_value_factor": {
            "field": "collectCount",
            "factor": 0.5,
            "modifier": "log1p",
            "missing": 0
          }
        },
        {
          "gauss": {
            "publishTime": { "origin": "now", "scale": "30d", "decay": 0.5 }
          }
        }
      ],
      "score_mode": "sum",
      "boost_mode": "sum"
    }
  },
  "size": 20
}
```

三个要点：
- **`ratingWilson` 用威尔逊下界，不用 `ratingAvg`** —— 和 SQL 侧一致的抗刷分口径
- **收藏数用 `log1p` 修饰** —— 1000 收藏和 100 收藏不该差 10 倍权重，取对数压平
- **`gauss` 做时间衰减** —— 新发布的菜有小幅加成，老菜不归零

**但要注意：`function_score` 只能算"排序分"，不能替代"约束判定"。**
你的直出条件（`rating_count >= 10` 且 `rating_wilson >= 0.84`）依然要单独判断，
用 `_source` 里取出来的字段在应用层校验，别指望 ES 的 score 能表达这个。

---

## 五、数据同步：ES 只是副本

### 阶段一（现在）：提交后同步写 + 定时全量兜底

```java
// 用 AFTER_COMMIT 监听器，别在事务里直接写 ES
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onDishChanged(DishChangedEvent event) {
    // 组装文档 → 写 ES
}
```

- **不要在 `@Transactional` 方法体里直接调 ES**：ES 调用失败不会回滚 MySQL，
  而且事务未提交时 ES 可能先读到旧数据
- 每天凌晨跑一次全量重建，兜住所有漏掉的同步

### 阶段二（进阶）：binlog 订阅（Canal）

实时、不侵入业务、天然解耦。但多一个组件，等你有真实同步痛点再上。

### 同步时必须带上的字段（漏一个就出事）

| 字段 | 漏了会怎样 |
|---|---|
| `status` | **草稿泄漏到推荐结果里**，用户看到别人没发布的菜 |
| `deleted` | 已删除的菜还能被搜出来 |
| `ratingWilson` | 排序退化，小样本刷分菜顶到第一 |
| `ingredientIds` | 召回直接失效 |

### `_id` 用雪花 ID

```
PUT /dish_index/_doc/2099335487637712897
```

用业务主键当 `_id`，重复写自动覆盖（幂等），不用自己去重。

---

## 六、坑清单

| 坑 | 表现 | 规避 |
|---|---|---|
| **`terms` 默认 OR** | 召回结果多到离谱，用户选的食材一个都没全 | 用 `must` 里多个 `term`，或 `terms_set` |
| ES 8 默认开安全认证 | 连不上 / SSL 报错 / 401 | Docker 加 `xpack.security.enabled=false` |
| 客户端与服务端版本不齐 | `NoNodeAvailableException` 等怪错 | 服务端锁 8.11.x，别用 8.12+ |
| 在事务里直接写 ES | MySQL 回滚了 ES 却写进去 / 读到旧数据 | `@TransactionalEventListener(AFTER_COMMIT)` |
| 忘了同步 `status` | **草稿泄漏** | 写进 `filter`，同步字段表里必须有它 |
| 深度分页 | `from + size > 10000` 直接报 `Result window is too large` | 用 `search_after`，别用大 `from` |
| 中文菜名搜不到 | 整句匹配不上 | 装 `ik` 分词器（按 ES 版本号下同版本插件） |
| 拿 ES 当数据库用 | ES 挂了业务全挂、数据也可能丢 | **MySQL 是 source of truth**，ES 挂了要能降级回 SQL 倒排 |

---

## 七、和 MySQL 侧的分工

| 职责 | MySQL | ES |
|---|---|---|
| 写入 / 事务 / 数据权威 | ✅ | ❌ 只读副本 |
| 食材组合召回（主） | 兜底降级用 | ✅ 主路径 |
| 加权排序 | 应用层算 | ✅ `function_score` 一次算完 |
| 食材用量明细 | ✅ | ❌ 不必进 |
| 评分原始记录 | ✅ | ❌ 只同步聚合值 |
| 菜名模糊搜索 | 全文索引可用但弱 | ✅ （装 ik 后） |
| 菜名去重 / 食材归一 | — | ✅ 语义相似场景（向量或 alias 表兜底） |

**降级预案**：ES 不可用时，自动回落到 `sql/dish.sql` 里的倒排查询。
两条路产出的结构必须一致，这样降级对前端透明 —— 这也是「先写 SQL 版」的价值。

---

## 八、落地实现（已在项目里真实跑通）

### 代码位置：`dish-service` 模块（端口 8082）

| 类 | 职责 |
|---|---|
| `config/EsProperties` | ES 地址 + 召回条数 / 直出阈值 / 综合分权重 |
| `config/EsClientConfig` | `RestClient` Bean（Spring 6.1 自带，**不发** compatible-with 头） |
| `es/EsDslBuilder` | **DSL 组装**：`must` 多 `term` 硬约束 + `function_score` 粗排 |
| `es/DishDoc` | ES 文档对象（对应 mapping） |
| `es/RecalledDish` | 一条召回结果（ES 分 + 应用层精排分） |
| `es/DishSearchRepository` | 执行查询、解析 hits；**ES 不可用时返回空列表而非抛异常** |
| `service/DishSearchService` | 参数规范化 → 召回 → 精排 → 直出判定 |
| `controller/DishSearchController` | `POST /search`（经网关是 `/api/dish/search`） |
| `handler/GlobalExceptionHandler` | 统一异常转 `Result` |

**注意没有引任何 ES 依赖** —— 全靠 `RestClient` 发 JSON。
这也是为什么将来把服务端换成 8.x，代码一行都不用改。

### 工具脚本（`es/` 目录）

| 文件 | 用途 |
|---|---|
| `dish-index.json` | 索引 mapping，可直接 `PUT /dish_index` |
| `sample-dish-data.ndjson` | 9 条测试数据（**特意包含反例**：草稿、已删除、缺食材） |
| `init-dish-index.py` | 建索引 + 灌数据（删旧索引 → 建 → bulk） |
| `verify-recall.py` | 接口断言验证，9 条断言 |

### 验证结果（真实执行，非推演）

| 用例 | 所选食材 | 预期 | 结果 |
|---|---|---|---|
| 1 | 番茄 + 鸡蛋 | 召回 1001/1002/1004，`directReturn=true` | ✅ PASS |
| 2 | 番茄 + 土豆 | 召回 1007，`directReturn=false`（wilson 0.70 < 0.84） | ✅ PASS |
| 3 | 番茄 + 鸡蛋 + 牛腩 | 空结果，提示需要 AI 生成 | ✅ PASS |

**合计 9 条断言全部 PASS，0 FAIL。**

用例 1 同时完成了三组反证：
- 1003（缺鸡蛋）、1006（缺番茄）、1007（缺鸡蛋）**均未被召回** → **硬约束真的生效**
  （如果误用 `terms` 的 OR 语义，这三个必然会出现）
- 1008（`status=0` 草稿）、1009（`deleted=1`）**均未被召回** → filter 生效，无数据泄漏
- 精排顺序：番茄炒蛋(0.9635) > 西红柿炒鸡蛋(0.9292) > 番茄鸡蛋汤(0.8718) ✅

`missReason` 实测输出，可解释性达标：
- 用例 2：`最接近的「番茄土豆汤」评分偏低（0.70 < 0.84）`
- 用例 3：`库里还没有包含这 3 种食材的菜品，需要 AI 生成`

---

## 九、命令行启动的一个真坑（务必知道）

### 现象

```
[Nacos Config] Load config[dataId=dish-service.yaml, group=DEFAULT_GROUP] success
ERROR Error getting properties from nacos: ...
org.yaml.snakeyaml.error.YAMLException:
    java.nio.charset.MalformedInputException: Input length = 1
...
Exception ... Failed to instantiate [HikariDataSource]:
    Failed to determine a suitable driver class
```

### 根因

**Java 17 在 Windows 中文环境下 `file.encoding` 默认仍是 GBK**
（默认改成 UTF-8 是 Java 18 才引入的 JEP 400，Java 17 没有）。

Nacos 上的配置带中文注释，Spring Cloud Alibaba 的 `NacosDataParserHandler`
用这个默认编码去解码 UTF-8 的字节 → 中文注释处直接解码失败。

### 为什么这个坑特别难查

`spring.config.import` 写的是 `optional:` 前缀，**这个 YAML 异常不中断启动**，
只被记成一行 ERROR 就过去了。真正暴露给开发者的是后面那句
`Failed to determine a suitable driver class` —— 看起来完全是数据源的问题，
跟「编码」八竿子打不着。

### 为什么在 IDEA 里不会遇到

IDEA 启动 Java 程序时默认带 `-Dfile.encoding=UTF-8`，所以本机在 IDEA 里跑一切正常。

### 解法

命令行启动时显式加上：

```bash
java -Dfile.encoding=UTF-8 -jar dish-service/target/dish-service-0.0.1-SNAPSHOT.jar --server.port=8082
```

已固化到 `dish-service/start.bat`，脚本注释里也写了这段来龙去脉。

> 顺带一提：`--server.port=8082` 也是必要的。某些环境下会注入 `SERVER__PORT`
> 环境变量，Spring 的宽松绑定会把它解析成 `server.port` 且**优先级高于配置文件**，
> 导致端口不是 8082。命令行参数的优先级最高，能压住它。
