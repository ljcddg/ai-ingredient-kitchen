package com.apesource.accountservice.controller;

import com.apesource.commonservice.constant.AuthConstants;
import com.apesource.commonservice.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户信息接口（演示网关透传身份的用法）。
 * <p>
 * 这个接口本身<b>不做任何认证</b> —— 认证已经在网关完成了。
 * 它直接从请求头里读 {@code X-User-Id}，这就是网关过滤器写进来的值。
 * <p>
 * 这正是网关统一认证的价值：每个业务服务不用再引 JWT 依赖、
 * 不用再写一遍拦截器，拿请求头就能知道「当前是谁在调」。
 * <p>
 * 安全前提：业务服务<b>不能</b>直接暴露在公网，必须只允许网关访问
 * （通常靠内网隔离或安全组实现）。否则别人绕过网关直接打这个端口，
 * 自己伪造一个 {@code X-User-Id} 就冒充成功了。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    /**
     * 查看当前登录用户。
     * <p>
     * {@code required = false} 是为了让网关关掉校验（本地调试）时不至于报 400，
     * 便于观察「没有网关时请求头长什么样」。
     */
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile(
            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = AuthConstants.HEADER_USERNAME, required = false) String username) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("username", username);
        data.put("source", "account-service 从网关透传的请求头中读取");
        return Result.success("查询成功", data);
    }
}
