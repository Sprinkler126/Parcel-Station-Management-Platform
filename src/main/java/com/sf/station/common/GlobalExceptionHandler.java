package com.sf.station.common;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理（文档 §7.2）。
 *
 * <p>禁止事项：异常堆栈返回前端。此处堆栈只进日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> biz(BizException e) {
        ErrorCode ec = e.getErrorCode();
        // 业务异常是预期内的流程分支，warn 级别即可，且不打堆栈
        log.warn("biz exception code={} msg={}", ec.code(), e.getMessage());
        return ResponseEntity.status(ec.httpStatus())
                .body(ApiResponse.fail(ec, e.getMessage(), e.getPayload()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> valid(MethodArgumentNotValidException e) {
        Map<String, String> errs = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField,
                        f -> Objects.requireNonNullElse(f.getDefaultMessage(), ""),
                        (a, b) -> a, LinkedHashMap::new));
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, "参数校验失败", errs));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> constraint(ConstraintViolationException e) {
        Map<String, String> errs = e.getConstraintViolations().stream()
                .collect(Collectors.toMap(v -> v.getPropertyPath().toString(),
                        v -> Objects.requireNonNullElse(v.getMessage(), ""),
                        (a, b) -> a, LinkedHashMap::new));
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, "参数校验失败", errs));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> missingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(
                ErrorCode.PARAM_INVALID, "缺少必填参数：" + e.getParameterName(), null));
    }

    /** 静态资源 404 不应落入兜底分支被记为 error */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND, "资源不存在", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> fallback(Exception e) {
        log.error("unexpected error", e); // 堆栈只进日志
        return ResponseEntity.status(ErrorCode.INTERNAL.httpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL, ErrorCode.INTERNAL.defaultMessage(), null));
    }
}
