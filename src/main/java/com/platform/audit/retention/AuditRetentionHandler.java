package com.platform.audit.retention;

/** 单个权威审计源的候选统计、保全过滤和分批物理清理边界。 */
public interface AuditRetentionHandler {
    String sourceModule();

    AuditRetentionPreview preview(AuditRetentionScope scope);

    AuditCleanupBatch deleteNextBatch(AuditRetentionScope scope, int limit);
}
