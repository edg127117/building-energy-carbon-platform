package com.platform.audit.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.audit.query.AuditEvidenceRecord;
import com.platform.audit.query.AuditQueryService;
import com.platform.audit.query.AuditQueryService.PreparedAuditQuery;
import com.platform.framework.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
/** 异步文件生成只消费数据库冻结的查询快照，不能重新扩大创建时的建筑范围。 */
public class AuditExportWorker {
    private static final Logger log = LoggerFactory.getLogger(AuditExportWorker.class);
    private static final String HEADER = String.join(",",
            "auditId", "sourceModule", "buildingId", "actorType", "operatorId", "actionType",
            "objectType", "objectId", "versionId", "reviewRequestId", "beforeSummary", "afterSummary",
            "result", "reasonCode", "traceId", "operationTime", "environmentMode", "selfApprovalDevMode");

    private final AuditExportRepository repository;
    private final AuditQueryService queryService;
    private final AuditExportFinalizer finalizer;
    private final AuditGovernanceProperties properties;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;

    public AuditExportWorker(
            AuditExportRepository repository,
            AuditQueryService queryService,
            AuditExportFinalizer finalizer,
            AuditGovernanceProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("auditExportExecutor") ThreadPoolTaskExecutor executor) {
        this.repository = repository;
        this.queryService = queryService;
        this.finalizer = finalizer;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(AuditExportRequested event) {
        try {
            executor.execute(() -> generate(event));
        } catch (TaskRejectedException failure) {
            failRejectedDispatch(event, failure);
        }
    }

    private void generate(AuditExportRequested event) {
        AuditExportJob job = repository.find(event.exportId()).orElse(null);
        if (job == null || !repository.markRunning(job.exportId())) return;
        String executionTrace = UUID.randomUUID().toString().replace("-", "");
        Path temp = null;
        Path target = null;
        MDC.put(TraceContext.MDC_KEY, executionTrace);
        try {
            if (!job.querySha256().equals(sha256(job.queryJson()))) {
                throw new IllegalStateException("审计导出查询快照校验失败");
            }
            PreparedAuditQuery prepared = objectMapper.readValue(job.queryJson(), PreparedAuditQuery.class);
            List<AuditEvidenceRecord> rows = queryService.exportRows(prepared, properties.getExportMaxRows());
            Path base = Path.of(properties.getExportDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(base);
            temp = base.resolve(job.exportId() + ".tmp").normalize();
            target = base.resolve(job.exportId() + ".csv").normalize();
            if (!temp.startsWith(base) || !target.startsWith(base)) {
                throw new IllegalStateException("审计导出路径越界");
            }
            writeCsv(temp, rows);
            move(temp, target);
            finalizer.complete(job, rows.size(), target.toString(), sha256(target), executionTrace);
        } catch (Exception failure) {
            deleteQuietly(temp);
            deleteQuietly(target);
            String errorCode = failure instanceof BusinessException business && business.getErrorCode() != null
                    ? business.getErrorCode() : "AUDIT_EXPORT_GENERATION_FAILED";
            try {
                finalizer.fail(job, errorCode, executionTrace);
            } catch (RuntimeException auditFailure) {
                log.error("审计导出失败状态写入失败: exportId={}, traceId={}",
                        job.exportId(), executionTrace, auditFailure);
            }
            log.error("审计导出生成失败: exportId={}, traceId={}, errorCode={}",
                    job.exportId(), executionTrace, errorCode, failure);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    /** 队列满时明确终止持久化任务，不能让它永久停留在 PENDING。 */
    private void failRejectedDispatch(AuditExportRequested event, TaskRejectedException failure) {
        AuditExportJob job = repository.find(event.exportId()).orElse(null);
        if (job == null) return;
        String traceId = UUID.randomUUID().toString().replace("-", "");
        try {
            finalizer.fail(job, AuditGovernanceErrors.EXPORT_QUEUE_FULL, traceId);
        } catch (RuntimeException auditFailure) {
            failure.addSuppressed(auditFailure);
        }
        log.error("审计导出队列已满: exportId={}, traceId={}", job.exportId(), traceId, failure);
    }

    private static void writeCsv(Path path, List<AuditEvidenceRecord> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write('\ufeff');
            writer.write(HEADER);
            writer.newLine();
            for (AuditEvidenceRecord value : rows) {
                writer.write(String.join(",",
                        csv(value.auditId()), csv(value.sourceModule()), csv(value.buildingId()),
                        csv(value.actorType()), csv(value.operatorId()), csv(value.actionType()),
                        csv(value.objectType()), csv(value.objectId()), csv(value.versionId()),
                        csv(value.reviewRequestId()), csv(value.beforeSummary()), csv(value.afterSummary()),
                        csv(value.result()), csv(value.reasonCode()), csv(value.traceId()),
                        csv(value.operationTime()), csv(value.environmentMode()),
                        csv(value.selfApprovalDevMode())));
                writer.newLine();
            }
        }
    }

    /** 防止逗号换行破坏结构，也防止电子表格把审计文本当作公式执行。 */
    static String csv(Object raw) {
        if (raw == null) return "";
        String value = raw.toString().replace("\u0000", "");
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 后续过期清理仍会按数据库任务范围重试；这里不能覆盖原始失败原因。
        }
    }
}
