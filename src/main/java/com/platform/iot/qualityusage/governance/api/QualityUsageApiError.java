package com.platform.iot.qualityusage.governance.api;

/** OpenAPI 中质量使用治理错误响应的稳定描述，不暴露数据库或内部异常。 */
public record QualityUsageApiError(
        Integer code,
        String msg,
        boolean success,
        String errorCode) {
}
