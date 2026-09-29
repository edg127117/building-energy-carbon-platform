package com.platform.weather.source;

/** 向任务层传递来源失败分类、可重试标记及退避时间，不将来源响应正文作为公共错误消息。 */
public final class WeatherSourceException extends RuntimeException {
    public enum Code {
        REQUEST_INVALID,
        SOURCE_TIMEOUT,
        SOURCE_INTERRUPTED,
        SOURCE_HTTP_ERROR,
        SOURCE_TOO_LARGE,
        STRUCTURE_ERROR,
        DATE_AMBIGUOUS
    }

    public enum Detail {
        NONE, ANCHOR_MISSING, ANCHOR_FORMAT, ANCHOR_DAY_MISMATCH,
        DAY_HEADER_MISSING, DAY_HEADER_MISMATCH, DAY_HEADER_INVALID
    }

    private Detail detail = Detail.NONE;

    public WeatherSourceException(Code code, Detail detail) {
        this(code);
        this.detail = detail;
    }

    public Detail getDetail() { return detail; }

    private final Code code;
    private final Integer status;
    private final boolean retryable;
    private final Long retryAfterMillis;

    public WeatherSourceException(Code code) {
        this(code, null, code == Code.SOURCE_TIMEOUT, null);
    }

    public WeatherSourceException(Code code, Throwable cause) {
        this(code, null, code == Code.SOURCE_TIMEOUT, null, cause);
    }

    public WeatherSourceException(Code code, Integer status, boolean retryable, Long retryAfterMillis) {
        this(code, status, retryable, retryAfterMillis, null);
    }

    private WeatherSourceException(Code code, Integer status, boolean retryable,
                                   Long retryAfterMillis, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
        this.retryAfterMillis = retryAfterMillis;
    }

    public Code getCode() {
        return code;
    }

    public Integer getStatus() {
        return status;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Long getRetryAfterMillis() {
        return retryAfterMillis;
    }
}
