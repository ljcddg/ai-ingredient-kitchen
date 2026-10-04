package com.apesource.accountservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录成功返回体。
 * <p>
 * 注意这里只有 token 和用户基础信息，<b>不含 password</b>。
 * 实体直接返回给前端是常见的密码泄露源头，所以用 VO 隔离。
 */

@Data
@NoArgsConstructor
public class LoginResponse {

    /** 访问令牌，前端后续请求放在 {@code Authorization: Bearer <token>} 头里 */
    private String token;

    /** token 类型，固定 Bearer，方便前端统一拼接 */
    private String tokenType = "Bearer";

    /** 用户主键 */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /**
     * 角色：0-普通用户，1-管理员。
     * <p>
     * 前端拿它决定要不要显示管理入口。<b>它只用于界面展示</b>——
     * 真正的权限判定在服务端（dish-service 会回源查 {@code t_user.role}），
     * 所以提权/降权后不需要重新登录，前端那点显示差异刷新一下就好。
     */
    private Integer role;


    public LoginResponse(String token, Long userId, String username, String nickname, Integer role) {
        this.token = token;
        this.userId = userId;
        this.username = username;
        this.nickname = nickname;
        this.role = role;
    }
}
