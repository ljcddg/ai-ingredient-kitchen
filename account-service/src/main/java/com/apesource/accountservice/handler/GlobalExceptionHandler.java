package com.apesource.accountservice.handler;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.commonservice.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器。
 * <p>
 * 把各类异常统一收敛成 {@link Result}，让 Controller 只关注正常流程。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：属于预期内的错误，不打堆栈，只记 message。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常：{}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验失败：把第一个字段的错误提示返回给前端。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null ? "参数校验失败" : fieldError.getDefaultMessage();
        return Result.fail(400, message);
    }

    /**
     * 路径不存在：404 而不是 500。
     * <p>
     * 不单独处理的话，兜底会把「路径写错了」也打成 500 + 完整堆栈，
     * 调用方分不清是自己写错还是服务故障（dish-service 那边实测踩到过）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("接口不存在：path={}", e.getResourcePath());
        return Result.fail(404, "接口不存在：" + e.getResourcePath());
    }

    /** 兼容 throwExceptionIfNoHandlerFound=true 的情况 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("接口不存在：{} {}", e.getHttpMethod(), e.getRequestURL());
        return Result.fail(404, "接口不存在：" + e.getRequestURL());
    }

    /**
     * 兜底异常：非预期错误，必须打完整堆栈，方便排查。
     * 对外只返回笼统提示，不暴露内部实现细节。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail("服务器内部错误，请稍后重试");
    }
}
