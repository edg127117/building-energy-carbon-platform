package com.platform.audit.export;

import java.time.LocalDateTime;

/** 导出任务只引用冻结查询和短期文件，不保存领域审计内容副本。 */
public record AuditExportJob(
        String exportId, long requestedBy, String purpose, String queryJson, String querySha256,
        String status, Integer rowCount, String filePath, String fileSha256,
        LocalDateTime expiresAt, String errorCode, String traceId,
        LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime completedAt,
        LocalDateTime downloadedAt) {
}
