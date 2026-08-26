package com.platform.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

/** 当前 HTTP 请求的服务端主追踪标识入口。客户端请求 ID 不会覆盖该值。 */
public final class TraceContext {
    public static final String HEADER = "X-Trace-Id";
    public static final String ATTRIBUTE = TraceContext.class.getName() + ".traceId";
    public static final String MDC_KEY = "traceId";

    private TraceContext() {
    }

    public static String current() {
        String traceId = MDC.get(MDC_KEY);
        return traceId == null ? "unavailable" : traceId;
    }

    public static String from(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String traceId ? traceId : current();
    }
}
