package com.platform.audit.export;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
/** 文件生成结果与安全审计在同一 MySQL 事务收口，审计失败时不得把文件标记为可下载。 */
public class AuditExportFinalizer {
    private final AuditExportRepository repository;
    private final AuditEvidenceWriter evidenceWriter;
    private final AuditGovernanceProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void complete(AuditExportJob job, int rows, String filePath, String fileSha256, String executionTrace) {
        repository.complete(job.exportId(), rows, filePath, fileSha256);
        append(job, "AUDIT_EXPORT_COMPLETED", "SUCCESS", null,
                "rowCount=" + rows + ";fileSha256=" + fileSha256 + ";parentTraceId=" + job.traceId(),
                executionTrace, "SYSTEM");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void fail(AuditExportJob job, String errorCode, String executionTrace) {
        repository.fail(job.exportId(), errorCode);
        append(job, "AUDIT_EXPORT_FAILED", "FAILED", errorCode,
                "parentTraceId=" + job.traceId(), executionTrace, "SYSTEM");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void expire(AuditExportJob job, String executionTrace) {
        repository.markExpired(job.exportId());
        append(job, "AUDIT_EXPORT_FILE_EXPIRED", "SUCCESS", null,
                "rowCount=" + job.rowCount() + ";fileSha256=" + job.fileSha256(), executionTrace, "SYSTEM");
    }

    private void append(AuditExportJob job, String action, String result, String reasonCode,
                        String summary, String traceId, String actorType) {
        evidenceWriter.append(new AuditEvidence(
                "SYSTEM_SECURITY", null, actorType, null, action, "AUDIT_EXPORT", job.exportId(),
                null, null, null, summary, result, reasonCode, traceId, LocalDateTime.now(),
                properties.getEnvironmentMode(), false));
    }
}
