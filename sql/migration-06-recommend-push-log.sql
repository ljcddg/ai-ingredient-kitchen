-- ============================================================
-- 迁移 06：补建推荐流推流日志表 t_dish_push_log（修「推荐页 500」）
--
-- 【为什么会有这一张要补】冷启动推荐池的候选查询里有 24 小时去重：
--     AND NOT EXISTS (SELECT 1 FROM t_dish_push_log l
--                     WHERE l.user_id = ? AND l.dish_id = d.id
--                       AND l.push_time > NOW() - INTERVAL 24 HOUR)
--   t_dish 上的冷启动字段（rank_level / dish_score / push_count / view_count …）
--   和索引 idx_rank_level_score 都在库里，唯独这张表不在 —— 于是
--     GET /api/dish/ai/recommend  →  code=500「服务器开小差了，请稍后再试」
--   （根因：Table 'project0914.t_dish_push_log' doesn't exist）。
--   缺表不是"少个功能"，是整条推荐流直接打挂 —— 前端表现为
--   「没能加载推荐 / 再试一次」。
--
-- 【幂等】CREATE TABLE IF NOT EXISTS：重复执行不出错，也不动已有数据。
-- 【口径】唯一键落在 (dish_id, user_id)：一人一菜只留一行，
--   重复推流走 ON DUPLICATE KEY UPDATE push_time = NOW() 刷新时间；
--   24 小时窗口是**查询条件**而不是唯一键 —— 唯一键管"只留一行"，
--   时间窗口管"多久之后可以再推"。见 docs/cold-start-design.md §2.3。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_dish_push_log (
  id        BIGINT   NOT NULL COMMENT '雪花ID',
  dish_id   BIGINT   NOT NULL COMMENT '菜品ID',
  user_id   BIGINT   NOT NULL COMMENT '被推给的用户',
  push_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次推流时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dish_user (dish_id, user_id),
  KEY idx_user_time (user_id, push_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐流推流日志（24小时去重）';

-- 自检：应输出 1 行 t_dish_push_log
SHOW TABLES LIKE 't_dish_push_log';
