package com.platform.audit.query;

import java.util.List;
import java.util.Set;

/** 单个权威审计源的 SQL 条件下推边界；实现不得先加载全表再在 Java 中过滤。 */
public interface AuditEvidenceProvider {
    String sourceModule();

    List<AuditEvidenceRecord> query(
            AuditQueryFilter filter, Set<String> allowedBuildingIds,
            AuditQueryCursor cursor, int limit);

    long countUpTo(AuditQueryFilter filter, Set<String> allowedBuildingIds, long limit);
}
