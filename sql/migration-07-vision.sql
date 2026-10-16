-- ============================================================
-- 迁移 07：识菜链路建表（YOLO 图片识别 → 食材清单 → 用户勾选 → 生成）
--
-- 设计文档：docs/yolo-vision-design.md §6.3 / §8
--
-- 【两张表各解决什么】
--   t_vision_label      —— 模型 label → 库内主数据的映射配置。
--     COCO 预训练权重输出英文名（carrot/broccoli），IngredientDirectory.resolveId
--     解析不了，必须显式配；自训 89 类食材模型输出中文名，走别名归一即可。
--     一张表兼容"COCO → 自训"两种来源，换权重不用改代码。
--   t_vision_detect_log —— 每次识别的记录（含用户勾选结果）。
--     价值：用户勾选与识别不一致的记录 = 后续模型训练的难例数据；
--     也是"识别准不准"的唯一定量依据。
--
-- 【幂等】CREATE TABLE IF NOT EXISTS + 先删后插（label 维度）。
-- 【写库失败绝不抛】记录点的实现见 VisionService.writeLog（与 t_ingredient_candidate 同原则）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 类别映射表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_vision_label (
  id             BIGINT       NOT NULL COMMENT '雪花ID',
  label          VARCHAR(64)  NOT NULL COMMENT '模型输出的类别名（COCO英文名或自训中文名）',
  label_type     VARCHAR(16)  NOT NULL DEFAULT 'ingredient'
                              COMMENT '类型：ingredient=食材（ref_id→t_ingredient.id）/ dish=菜品（预留，ref_id→t_dish.id）',
  ref_id         BIGINT       NULL     COMMENT '引用主数据ID（按 label_type 解释）',
  min_confidence DECIMAL(4,2) NULL     COMMENT '该label最低置信度：低于它的检出直接丢弃；NULL=不过这道筛',
  default_checked TINYINT     NOT NULL DEFAULT 1
                              COMMENT '默认勾选（预留字段：当前实现用全局阈值 ai.vision.default-checked-confidence）',
  status         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-停用，1-启用',
  remark         VARCHAR(255) NULL     COMMENT '备注：为什么这么配/不配（如"bowl是容器不是食材"）',
  deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删，1-已删',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_label (label),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='识菜：模型label→主数据映射配置';

-- ------------------------------------------------------------
-- 2. 识别记录表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_vision_detect_log (
  id               BIGINT      NOT NULL COMMENT '雪花ID',
  request_id       VARCHAR(64) NOT NULL COMMENT '识别请求ID（Java/响应体/Python 三处一致，用于对账）',
  user_id          BIGINT      NULL     COMMENT '发起用户（网关注入的 X-User-Id）',
  image_count      INT         NOT NULL DEFAULT 0 COMMENT '图片张数',
  detect_json      JSON        NULL     COMMENT '检出明细：按图分组的 label/confidence/box/映射结果',
  selected_json    JSON        NULL     COMMENT '聚合清单 + 用户勾选后的食材（确认生成时回写 selected 部分）',
  used_for_generate TINYINT    NOT NULL DEFAULT 0 COMMENT '是否最终带入生成：0-否，1-是',
  cost_ms          INT         NULL     COMMENT '总耗时（毫秒，Python推理+Java编排）',
  error_msg        VARCHAR(500) NULL    COMMENT '失败原因（正常为 NULL）',
  create_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_request (request_id),
  KEY idx_user_time (user_id, create_time),
  KEY idx_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='识菜：识别记录（含用户纠正，模型难例来源）';

-- ------------------------------------------------------------
-- 3. COCO 预训练权重的初始映射（步骤 A：工程链路先行的最小集合）
--
-- 【为什么只有 2 条】COCO 80 类里食物相关的有十来个，但与本项目 89 种食材
-- 真正对得上的只有这两个：
--   carrot   → 胡萝卜（食材表有）
--   broccoli → 西兰花（食材表有）
-- 其余（apple/banana/orange/pizza/cake…）库里没有对应食材，**故意不配**：
-- 让它们进 unmappedLabels 明示给用户，正好验证 unmapped 这条展示链路。
-- 容器类（bowl/cup/bottle）不是食材，永远不配。
--
-- 【步骤 B】89 类食材模型训练完成后，label 直接是中文食材名，
-- 走 IngredientDirectory 别名归一即可，不需要在这里逐条补。
-- 幂等：先删本表这两个 label 再插（不动别的数据）。
-- ------------------------------------------------------------
DELETE FROM t_vision_label WHERE label IN ('carrot', 'broccoli');

INSERT INTO t_vision_label (id, label, label_type, ref_id, min_confidence, status, remark)
SELECT 1000000000000000001, 'carrot', 'ingredient', i.id, 0.40, 1,
       'COCO 食物类 → 胡萝卜（步骤A：链路验证用）'
FROM t_ingredient i WHERE i.name = '胡萝卜' AND i.deleted = 0;

INSERT INTO t_vision_label (id, label, label_type, ref_id, min_confidence, status, remark)
SELECT 1000000000000000002, 'broccoli', 'ingredient', i.id, 0.40, 1,
       'COCO 食物类 → 西兰花（步骤A：链路验证用）'
FROM t_ingredient i WHERE i.name = '西兰花' AND i.deleted = 0;

-- 自检：应输出 2 行（胡萝卜/西兰花）；若输出 0 行说明食材表里没有这两样
SELECT l.label, i.name AS mapped_name, l.min_confidence
FROM t_vision_label l LEFT JOIN t_ingredient i ON i.id = l.ref_id
WHERE l.deleted = 0;

-- 自检：应输出 2 行表名
SHOW TABLES LIKE 't_vision_%';
