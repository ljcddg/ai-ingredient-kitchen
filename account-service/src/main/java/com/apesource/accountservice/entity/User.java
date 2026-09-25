package com.apesource.accountservice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户表实体。
 * <p>
 * 对应数据库表 {@code t_user}。注意这里没有用 Lombok 的 {@code @Data}，
 * 是为了显式控制字段可见性、并在 getter 上做保护，实际项目中如果统一用 Lombok 也可以。
 */
@TableName("t_user")
@Data
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键。
     * <p>
     * 用 {@link IdType#ASSIGN_ID}（雪花算法）而不是自增：
     * 分库分表后自增 ID 会冲突，且自增 ID 会暴露业务量。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录用户名，数据库唯一索引 */
    private String username;

    /** BCrypt 加密后的密码（固定 60 字符），数据库里绝不存明文 */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 手机号，可为空 */
    private String phone;

    /** 账号状态：0-禁用，1-正常 */
    private Integer status;

    /** 逻辑删除标记：0-未删除，1-已删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间，插入时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，插入和更新时都自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
