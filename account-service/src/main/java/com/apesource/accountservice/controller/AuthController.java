package com.apesource.accountservice.controller;

import com.apesource.accountservice.dto.LoginRequest;
import com.apesource.accountservice.dto.LoginResponse;
import com.apesource.accountservice.dto.RegisterRequest;
import com.apesource.accountservice.service.UserService;
import com.apesource.commonservice.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册 / 登录。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 注册。
     * <p>
     * {@code @Valid} 触发 DTO 上的校验注解，不合法会抛 MethodArgumentNotValidException，
     * 由全局异常处理器转成统一 Result。
     */
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success("注册成功", userService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success("登录成功", userService.login(request));
    }
}
