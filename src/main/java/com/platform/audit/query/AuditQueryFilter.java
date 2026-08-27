package com.platform.audit.query;

import java.time.Instant;

/** 已规范化的审计查询条件；建筑范围由服务端另行解析，不能由调用方直接指定授权集合。 */
public record AuditQueryFilter(
        String sourceModule,
        String buildingId,
        Long operatorId,
        String actorType,
        String actionType,
        String objectType,
        String objectId,
        String result,
        String reasonCode,
        String traceId,
        Instant from,
        Instant to
) {
}
