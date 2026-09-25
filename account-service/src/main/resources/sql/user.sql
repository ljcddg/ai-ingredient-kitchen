-- ============================================================
-- account-service 用户表
-- ============================================================
CREATE DATABASE IF NOT EXISTS project0914
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE project0914;

DROP TABLE IF EXISTS t_user;

CREATE TABLE t_user
(
    id          BIGINT       NOT NULL COMMENT '主键（雪花ID，应用层生成）',
    username    VARCHAR(50)  NOT NULL COMMENT '登录用户名',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密后的密码（固定 60 字符）',
    nickname    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态：0-禁用，1-正常',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 唯一索引才是并发下防重复注册的最终保障，
    -- Service 里的先查后插在并发场景下仍可能同时通过，必须靠数据库兜底
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';
