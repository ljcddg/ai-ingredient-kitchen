package com.apesource.dishservice.handler;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.commonservice.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;

/**
 * 全局异常处理：把异常统一转成 {@link Result}，别让 500 堆栈直接透给前端。
 *
 * <p>说明：account-service 里也有一份几乎一样的实现。
 * 之所以没上移到 common-service，是因为「异常处理器要不要生效」属于各服务的
 * Web 层决策（比如将来某个服务要自定义错误结构），放公共模块反而会互相牵制。
 * 等第三个服务也需要时，再考虑抽出来。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务校验失败：这类异常是预期内的，打 warn 就够，不用堆栈 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** @Valid 参数校验失败：取第一条提示返回，前端一次只提示一个更清楚 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("参数校验失败");
        log.warn("参数校验失败：{}", message);
        return Result.fail(400, message);
    }

    /** 兜底：未预期的异常必须打完整堆栈，否则线上没法查 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未处理异常", e);
        return Result.fail("服务器开小差了，请稍后再试");
    }
}
