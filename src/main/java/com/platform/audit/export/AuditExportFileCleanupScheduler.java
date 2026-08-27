package com.platform.audit.export;

import com.platform.audit.AuditGovernanceProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
@RequiredArgsConstructor
/** 只删除到期导出文件，导出任务、数量、校验值和下载审计继续保留。 */
public class AuditExportFileCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(AuditExportFileCleanupScheduler.class);
    private final AuditExportRepository repository;
    private final AuditExportFinalizer finalizer;
    private final AuditGovernanceProperties properties;

    @Scheduled(fixedDelayString = "${audit-governance.export-cleanup-delay:PT1M}")
    public void cleanup() {
        for (AuditExportJob job : repository.findExpiredCompleted(100)) {
            String traceId = UUID.randomUUID().toString().replace("-", "");
            try {
                Path base = Path.of(properties.getExportDirectory()).toAbsolutePath().normalize();
                Path file = Path.of(job.filePath()).toAbsolutePath().normalize();
                if (!file.startsWith(base)) throw new IllegalStateException("审计导出清理路径越界");
                Files.deleteIfExists(file);
                finalizer.expire(job, traceId);
            } catch (IOException | RuntimeException failure) {
                log.error("审计导出文件到期清理失败: exportId={}, traceId={}",
                        job.exportId(), traceId, failure);
            }
        }
    }
}
