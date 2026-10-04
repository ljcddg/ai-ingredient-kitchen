-- ============================================================================
-- migration-05：补历史欠账 —— 「名字其实命中主数据、ingredient_id 却是空」的明细行
--
-- 适用库：project0914        幂等：可重复执行        执行位置：MySQL 客户端
--
-- 为什么会有这批数据
-- ----------------------------------------------------------------------------
-- DishDraftWriter 原先只在「用户勾选的食材列表」里按名字找主数据 ID：
--   Long masterId = resolveIngredientId(item.getName(), payload);   // 只认用户勾过的
-- 于是 AI 自己补进来的食材（以及用户在输入框自由敲出来的）一律以 ingredient_id = NULL
-- 落库。后果有三条：
--   ① 这些食材进不了按 ID 的精确召回（只能靠名字被搜到）；
--   ② 营养计算查不到成分表 → 整道菜被判「覆盖不足」，退回 AI 估算值并如实标成来源 1；
--   ③ 还要被误记进候选表 t_ingredient_candidate，等管理员转正。
-- 应用侧已改为回退 {@code IngredientDirectory.resolveId()}（正式名 + 别名 + 去空白/大小写）。
-- 本脚本只补**已经落库**的历史行，不改变任何业务逻辑。
--
-- 只补「唯一能对上」的
-- ----------------------------------------------------------------------------
--   1) 正式名精确相同；
--   2) 别名命中，且该别名只属于一条主数据（一名字多归属时不动，宁可留空也不乱指）。
-- 表外食材（紫米、迷迭香、猪里脊肉、高脚鸟蛋、食用油 …）不会被误补。
--
-- 补完要做两件收尾（顺序无所谓，都可在管理接口上做）
-- ----------------------------------------------------------------------------
--   POST /api/dish/ai/es/reindex                    -- 让 ES 文档里的食材 ID 跟上
--   POST /api/dish/ai/admin/nutrition/recompute     -- 覆盖度变好，部分菜会从 AI 估算转为本地计算
-- ============================================================================

-- 补之前：能对上主数据却是空的行（开发库实测 6 行：鸡蛋 5、猪肉 1）
SELECT 'before' AS stage, COUNT(*) AS should_have_id
FROM t_dish_ingredient di JOIN t_dish d ON d.id = di.dish_id
WHERE di.ingredient_id IS NULL AND d.deleted = 0 AND (
    EXISTS (SELECT 1 FROM t_ingredient i WHERE i.deleted = 0 AND i.name = di.ingredient_name)
    OR EXISTS (SELECT 1 FROM t_ingredient i WHERE i.deleted = 0 AND i.alias IS NOT NULL
               AND FIND_IN_SET(di.ingredient_name, REPLACE(i.alias, '，', ','))));

-- 1) 正式名精确相同
UPDATE t_dish_ingredient di
JOIN t_ingredient i ON i.deleted = 0 AND i.name = di.ingredient_name
SET di.ingredient_id = i.id
WHERE di.ingredient_id IS NULL;

-- 2) 别名命中，且只属于一条主数据
--    （别名的逗号分隔在 SQL 里展开 —— 实测单行最多 4 个别名）
UPDATE t_dish_ingredient di
JOIN (
    SELECT nm, MIN(iid) AS iid, COUNT(DISTINCT iid) AS hits
    FROM (
        SELECT i.id AS iid,
               TRIM(SUBSTRING_INDEX(
                   SUBSTRING_INDEX(REPLACE(i.alias, '，', ','), ',', n.n), ',', -1)) AS nm
        FROM t_ingredient i
        JOIN (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) n
          ON n.n <= LENGTH(REPLACE(i.alias, '，', ','))
                    - LENGTH(REPLACE(REPLACE(i.alias, '，', ','), ',', '')) + 1
        WHERE i.deleted = 0 AND i.alias IS NOT NULL AND i.alias <> ''
    ) parts
    WHERE nm <> ''
    GROUP BY nm
    HAVING hits = 1
) m ON m.nm = di.ingredient_name
SET di.ingredient_id = m.iid
WHERE di.ingredient_id IS NULL;

-- 补之后：这个数必须是 0
SELECT 'after' AS stage, COUNT(*) AS should_have_id
FROM t_dish_ingredient di JOIN t_dish d ON d.id = di.dish_id
WHERE di.ingredient_id IS NULL AND d.deleted = 0 AND (
    EXISTS (SELECT 1 FROM t_ingredient i WHERE i.deleted = 0 AND i.name = di.ingredient_name)
    OR EXISTS (SELECT 1 FROM t_ingredient i WHERE i.deleted = 0 AND i.alias IS NOT NULL
               AND FIND_IN_SET(di.ingredient_name, REPLACE(i.alias, '，', ','))));

-- 顺带看一眼：仍然为空的是不是都是真正的表外食材（应当如此）
SELECT 'still_null' AS stage, di.ingredient_name, COUNT(*) AS rows_cnt
FROM t_dish_ingredient di
WHERE di.ingredient_id IS NULL
GROUP BY di.ingredient_name
ORDER BY rows_cnt DESC, di.ingredient_name;
