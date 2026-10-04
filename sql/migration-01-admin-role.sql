-- ============================================================
-- P0-1 管理员角色：给 t_user 加 role 列
--
-- 执行方式（在项目根目录）：
--   mysql -uroot -proot --default-character-set=utf8mb4 project0914 \
--     -e "source sql/migration-01-admin-role.sql"
--
-- 【为什么需要这一步】
--   说明文档第四节要求：「为用户增加普通用户和管理员角色。索引重建、
--   食材转正、内容下架等接口在后端执行管理员授权」。
--   但 t_user 原本只有 status（0 禁用 / 1 正常），没有任何权限维度，
--   结果是任何一个登录用户都能调 /ai/es/reindex 把全量索引重建一遍、
--   或调 /ai/ingredients/promote 把候选食材批量转正。
--
-- 【幂等性】
--   本脚本可重复执行：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
--   所以用 information_schema 判断后再动态拼 DDL。
-- ============================================================

-- ---------- 1. 加 role 列 ----------
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_user'
      AND COLUMN_NAME = 'role'
);

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE t_user ADD COLUMN role TINYINT NOT NULL DEFAULT 0 COMMENT ''角色：0-普通用户，1-管理员'' AFTER status',
    'SELECT ''role 列已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2. 指定管理员 ----------
-- 本地开发环境把最早的账号 bingbao 提为管理员；换账号改这里的 username 即可。
-- 注意：角色判定发生在服务端（dish-service 查库），所以改完不需要重新登录。
UPDATE t_user SET role = 1 WHERE username = 'bingbao' AND deleted = 0;

-- ---------- 3. 核对结果 ----------
SELECT id, username, nickname, status, role FROM t_user WHERE role = 1;
