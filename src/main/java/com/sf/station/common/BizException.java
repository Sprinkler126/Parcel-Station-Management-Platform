package com.sf.station.common;

/**
 * 业务异常。payload 承载错误码表约定的 data 载荷，供前端做二次动作
 * （例如 P2003 携带 suggestedCode 供"一键采纳"）。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object payload;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object payload) {
        super(message);
        this.errorCode = errorCode;
        this.payload = payload;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getPayload() {
        return payload;
    }

    /** 业务异常不需要堆栈，避免高频抛出时的性能开销 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
