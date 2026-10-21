package com.lanou.csh.exception;

import com.lanou.csh.util.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器。
 * <p>
 * 统一把异常转换为 {@link Result} 结构,避免异常堆栈直接暴露给前端,
 * 同时让「业务异常」与「系统异常」在日志中有明显区分。
 *
 * @author lanou
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常:属于可预期的流程分支,只记 warn,不打印堆栈 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.failure(400, e.getMessage());
    }

    /** 参数校验失败(如 @Valid 不通过) */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValidException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return Result.failure(400, msg);
    }

    /**
     * 上传文件超出 Spring 的限制时,给用户一句能看懂的话。
     * <p>
     * 不处理的话会落到下面的 Exception 兜底,前端只能看到「服务器内部错误」,
     * 用户会误以为是服务挂了。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大: {}", e.getMessage());
        return Result.failure(400, "图片太大了,单张请控制在 5MB 以内");
    }

    /** 兜底:未预料的异常,记录完整堆栈便于排查 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.failure(500, "服务器内部错误,请稍后重试");
    }
}
