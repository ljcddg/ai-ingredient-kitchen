package com.apesource.dishservice.handler;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.commonservice.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * 路径压根不存在：返回 404 而不是 500。
     *
     * <p>为什么值得单独写一条：兜底的 {@link #handleException} 会把
     * {@code NoResourceFoundException} 也当成"未预期异常"打出完整堆栈、回一句
     * "服务器开小差了"。这样调用方（和排查的人）分不清"我路径写错了"和"服务真的挂了"——
     * 实测 {@code /api/dish/ai/no-such-endpoint} 就是这么变成 500 的。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("接口不存在：path={}", e.getResourcePath());
        return Result.fail(404, "接口不存在：" + e.getResourcePath());
    }

    /** 兼容 throwExceptionIfNoHandlerFound=true 的情况，语义同上 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("接口不存在：{} {}", e.getHttpMethod(), e.getRequestURL());
        return Result.fail(404, "接口不存在：" + e.getRequestURL());
    }

    /**
     * 路径参数类型不对（例如把 {@code /ai/dish/{id}} 的 id 写成 notanumber）：
     * 这是调用方传错了，属于 400 而不是服务故障。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("路径参数类型不匹配：name={}, value={}", e.getName(), e.getValue());
        return Result.fail(400, "参数 " + e.getName() + " 格式不正确");
    }

    /**
     * 唯一键冲突：典型场景是同一个创作者又传了一道同名菜
     * （{@code t_dish.uk_name_creator(name_key, creator_id)} 挡下来的）。
     *
     * <p>为什么值得单独一条：这类冲突是<b>调用方能自己解决</b>的（换个名字就行），
     * 报成"服务器开小差了"等于把用户填了半天的表单判了死刑，还查不出原因。
     * 走这一条的前置检查是 {@code ManualDishWriter.nameConflict}，它挡住绝大多数情况；
     * 这里是并发/逻辑删除残留（唯一键不区分 {@code deleted}）的兜底。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突：{}", e.getMessage());
        return Result.fail(409, "已经有一道同名的菜了（也可能是重复提交），换个菜名再试");
    }

    /** 兜底：未预期的异常必须打完整堆栈，否则线上没法查 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未处理异常", e);
        return Result.fail("服务器开小差了，请稍后再试");
    }
}
