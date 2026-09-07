package com.platform.energy.efficiency;

/** 与统一异常响应一致的错误契约；客户端按 errorCode 判断版本、范围或职责失败。 */
public record EerpApiError(int code, String errorCode, String msg, boolean success, String traceId) {}
