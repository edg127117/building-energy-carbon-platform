package com.platform.audit.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.TraceContext;
import com.platform.audit.query.AuditQueryFilter;
import com.platform.audit.query.AuditQueryService;
import com.platform.audit.query.AuditQueryService.PreparedAuditQuery;
import com.platform.framework.exception.BusinessException;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
/** 创建与下载均重新校验双职责；导出文件路径永不进入 API 响应。 */
public class AuditExportService {
    private final AuditQueryService queryService;
    private final AuditExportRepository repository;
    private final BackendDutyService dutyService;
    private final BuildingScopeService buildingScopeService;
    private final AuditEvidenceWriter evidenceWriter;
    private final AuditGovernanceProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    @Transactional(rollbackFor = Exception.class)
    public AuditExportJob create(
            long userId, Collection<String> roles, AuditQueryFilter filter, String rawPurpose) {
        dutyService.requireDuty(userId, BackendDuty.AUDIT_EVIDENCE_EXPORTER);
        String purpose = purpose(rawPurpose);
        PreparedAuditQuery prepared = queryService.prepare(userId, roles, filter);
        long count = queryService.countUpTo(prepared, properties.getExportMaxRows() + 1L);
        if (count > properties.getExportMaxRows()) {
            throw AuditGovernanceErrors.conflict(
                    AuditGovernanceErrors.EXPORT_LIMIT_EXCEEDED, "审计导出记录数超过配置上限");
        }
        String queryJson = json(prepared);
        LocalDateTime now = LocalDateTime.now();
        AuditExportJob job = new AuditExportJob(
                id(), userId, purpose, queryJson, sha256(queryJson), "PENDING", null, null, null,
                now.plus(properties.getExportFileTtl()), null, TraceContext.current(), now,
                null, null, null);
        repository.insert(job);
        record(userId, prepared.filter().buildingId(), "AUDIT_EXPORT_CREATE", job.exportId(),
                "querySha256=" + job.querySha256() + ";expectedRows=" + count, job.traceId());
        events.publishEvent(new AuditExportRequested(job.exportId()));
        return job;
    }

    public AuditExportJob detail(long userId, Collection<String> roles, String exportId) {
        requireExportDuties(userId);
        AuditExportJob job = visibleJob(userId, exportId);
        requireCurrentScope(userId, roles, stored(job));
        return job;
    }

    @Transactional(rollbackFor = Exception.class)
    public Download download(long userId, Collection<String> roles, String exportId) {
        requireExportDuties(userId);
        AuditExportJob job = visibleJob(userId, exportId);
        requireCurrentScope(userId, roles, stored(job));
        if (!"COMPLETED".equals(job.status()) || job.expiresAt().isBefore(LocalDateTime.now())) {
            throw AuditGovernanceErrors.conflict(
                    AuditGovernanceErrors.EXPORT_NOT_READY, "审计导出文件尚不可下载或已过期");
        }
        Path path = safePath(job.filePath());
        if (!Files.isRegularFile(path)) {
            throw AuditGovernanceErrors.conflict(
                    AuditGovernanceErrors.EXPORT_NOT_READY, "审计导出文件尚不可下载或已过期");
        }
        repository.markDownloaded(exportId, userId);
        record(userId, stored(job).filter().buildingId(), "AUDIT_EXPORT_DOWNLOAD", exportId,
                "rowCount=" + job.rowCount() + ";fileSha256=" + job.fileSha256(), TraceContext.current());
        return new Download(path, "audit-export-" + exportId + ".csv");
    }

    private void requireExportDuties(long userId) {
        dutyService.requireDuty(userId, BackendDuty.AUDIT_EVIDENCE_VIEWER);
        dutyService.requireDuty(userId, BackendDuty.AUDIT_EVIDENCE_EXPORTER);
    }

    private AuditExportJob visibleJob(long userId, String exportId) {
        if (exportId == null || !exportId.matches("[a-f0-9]{32}")) {
            throw AuditGovernanceErrors.forbidden(AuditGovernanceErrors.EXPORT_FORBIDDEN);
        }
        AuditExportJob job = repository.find(exportId).orElseThrow(
                () -> AuditGovernanceErrors.forbidden(AuditGovernanceErrors.EXPORT_FORBIDDEN));
        if (job.requestedBy() != userId) {
            throw AuditGovernanceErrors.forbidden(AuditGovernanceErrors.EXPORT_FORBIDDEN);
        }
        return job;
    }

    private void requireCurrentScope(long userId, Collection<String> roles, PreparedAuditQuery stored) {
        Set<String> current = buildingScopeService.getAccessibleBuildingIds(userId, roles);
        Set<String> frozen = stored.allowedBuildingIds();
        boolean allowed = frozen == null ? current == null : current == null || current.containsAll(frozen);
        if (!allowed) throw AuditGovernanceErrors.forbidden(AuditGovernanceErrors.EXPORT_FORBIDDEN);
    }

    private PreparedAuditQuery stored(AuditExportJob job) {
        try {
            return objectMapper.readValue(job.queryJson(), PreparedAuditQuery.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审计导出查询快照损坏", exception);
        }
    }

    private Path safePath(String rawPath) {
        if (rawPath == null) throw AuditGovernanceErrors.conflict(
                AuditGovernanceErrors.EXPORT_NOT_READY, "审计导出文件尚不可下载或已过期");
        Path base = Path.of(properties.getExportDirectory()).toAbsolutePath().normalize();
        Path value = Path.of(rawPath).toAbsolutePath().normalize();
        if (!value.startsWith(base)) throw new IllegalStateException("审计导出文件路径越界");
        return value;
    }

    private void record(long userId, String buildingId, String action, String exportId,
                        String summary, String traceId) {
        evidenceWriter.append(new AuditEvidence(
                "SYSTEM_SECURITY", buildingId, "USER", userId, action, "AUDIT_EXPORT", exportId,
                null, null, null, summary, "SUCCESS", null, traceId, LocalDateTime.now(),
                properties.getEnvironmentMode(), false));
    }

    private String json(PreparedAuditQuery query) {
        try {
            return objectMapper.writeValueAsString(query);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审计导出查询快照生成失败", exception);
        }
    }

    private static String purpose(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(
                400, AuditGovernanceErrors.EXPORT_PURPOSE_INVALID, "审计导出用途不能为空");
        String normalized = value.trim();
        if (normalized.length() > 500 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(400, AuditGovernanceErrors.EXPORT_PURPOSE_INVALID, "审计导出用途无效");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }

    public record Download(Path path, String fileName) {
    }
}
