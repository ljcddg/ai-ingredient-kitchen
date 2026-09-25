package com.apesource.commonservice.exception;

import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常。
 * <p>
 * 业务校验失败时直接抛这个异常，由全局异常处理器统一转成 {@code Result.fail}，
 * 避免在 Service 里到处写 if-else 拼装返回值。
 */
@Getter
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码 */
    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

}
