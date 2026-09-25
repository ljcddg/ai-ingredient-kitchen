package com.apesource.commonservice.result;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应结果包装类。
 * <p>
 * 所有接口都返回这个结构，前端只需要判断 code 一个字段，
 * 不用为每个接口写不同的解析逻辑。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码：200 成功，401 未认证，403 无权限，其余为失败 */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    public Result() {
    }

    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success() {
        return new Result<>(200, "操作成功", null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 未认证（401）：没带 token，或 token 无效/过期。
     * <p>
     * 单独抽出 401 是因为网关拦截未登录请求时要返回这个语义，
     * 前端看到 401 就知道该跳登录页了。
     */
    public static <T> Result<T> unauthorized(String message) {
        return new Result<>(401, message, null);
    }

    /** 无权限（403）：token 有效，但访问的资源不属于当前用户 */
    public static <T> Result<T> forbidden(String message) {
        return new Result<>(403, message, null);
    }
}
