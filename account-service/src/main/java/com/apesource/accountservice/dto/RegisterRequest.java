package com.apesource.accountservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求参数。
 * <p>
 * 用独立的 DTO 而不是直接收 {@code User} 实体，好处是：
 * 前端传什么字段由 DTO 决定，避免客户端直接构造 status、id 等敏感字段。
 */

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度需在 4-20 位之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
    private String password;

    @Size(max = 20, message = "昵称最长 20 位")
    private String nickname;
}
