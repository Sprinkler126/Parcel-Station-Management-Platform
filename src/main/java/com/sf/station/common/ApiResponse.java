package com.sf.station.common;

import org.slf4j.MDC;

/**
 * 统一响应体（文档 §7.2）。
 *
 * @param code    业务码，"0" 为成功，其余见 {@link ErrorCode}
 * @param message 人类可读信息
 * @param data    业务数据或错误载荷
 * @param traceId 链路 ID，由 TraceIdFilter 写入 MDC
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), "success", data, MDC.get(TraceIdFilter.TRACE_ID));
    }

    public static <T> ApiResponse<T> fail(ErrorCode ec, String msg, T data) {
        return new ApiResponse<>(ec.code(), msg, data, MDC.get(TraceIdFilter.TRACE_ID));
    }
}
