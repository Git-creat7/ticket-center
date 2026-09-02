package asia.creat.common.exception;

import asia.creat.common.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验异常: {}", msg);
        return Result.error(400, "参数验证失败: " + msg);
    }

    @ExceptionHandler(BindException.class)
    public Result handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("数据绑定异常: {}", msg);
        return Result.error(400, "参数验证失败: " + msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result handleConstraintViolationException(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束校验异常: {}", msg);
        return Result.error(400, "参数验证失败: " + msg);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Result handleIllegalStateException(RuntimeException e) {
        log.warn("非法参数或状态异常: {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    /**
     * 静态资源不存在。
     *
     * 必须单独处理：否则被下面的 Exception 兜底接走，一张丢失的图片会变成
     * "服务器内部异常" 500 + 一条 ERROR 级堆栈。前端的 img onerror 靠真实
     * HTTP 状态码触发，所以这里用 @ResponseStatus 返回真正的 404，
     * 而不是业务错误那套「HTTP 200 + body.code」——图片请求不经过 axios 拦截器。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result handleNoResourceFound(NoResourceFoundException e) {
        log.warn("静态资源不存在: {}", e.getResourcePath());
        return Result.error(404, "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("系统发生未捕获异常: ", e);
        return Result.error("服务器内部异常，请稍后再试");
    }
}
