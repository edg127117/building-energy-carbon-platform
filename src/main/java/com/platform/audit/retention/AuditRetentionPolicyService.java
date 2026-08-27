package com.platform.audit.retention;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.TraceContext;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
/** 审核执行时创建新版本，禁止修改已批准期限或用数据库容量缩短合规期限。 */
public class AuditRetentionPolicyService {
    private final AuditRetentionPolicyRepository repository;
    private final BackendDutyService dutyService;
    private final AuditEvidenceWriter evidenceWriter;
    private final AuditGovernanceProperties properties;
    private final AuditCleanupRunRepository cleanupRunRepository;

    @Transactional(rollbackFor = Exception.class)
    public AuditRetentionPolicy apply(
            RetentionPolicyCommand command, String requestId, long submittedBy, long approvedBy) {
        int version = repository.nextVersionForUpdate(command.sourceModule());
        repository.supersedeCurrent(command.sourceModule());
        LocalDateTime now = LocalDateTime.now();
        AuditRetentionPolicy policy = new AuditRetentionPolicy(id(), command.dataCategory(),
                command.sourceModule(), command.retentionPeriod(), command.cleanupEnabled(),
                command.effectiveAt(), version, "CURRENT", command.changeReason(), requestId,
                approvedBy, now);
        repository.insert(policy);
        evidenceWriter.append(new AuditEvidence("SYSTEM_SECURITY", null, "USER", approvedBy,
                "APPLY_AUDIT_RETENTION_POLICY", "AUDIT_RETENTION_POLICY", policy.policyId(),
                Integer.toString(version), requestId, null,
                "source=" + policy.sourceModule() + ";period=" + policy.retentionPeriod()
                        + ";enabled=" + policy.cleanupEnabled(),
                "SUCCESS", null, TraceContext.current(), now, properties.getEnvironmentMode(),
                submittedBy == approvedBy));
        return policy;
    }

    @Transactional(readOnly = true)
    public List<AuditRetentionPolicy> list(long userId) {
        dutyService.requireDuty(userId, BackendDuty.AUDIT_RETENTION_MANAGER);
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<AuditCleanupRun> cleanupRuns(long userId, int requestedLimit) {
        dutyService.requireDuty(userId, BackendDuty.AUDIT_RETENTION_MANAGER);
        if (requestedLimit < 1 || requestedLimit > 200) {
            throw invalid("清理批次查询数量必须在1至200之间");
        }
        return cleanupRunRepository.findRecent(requestedLimit);
    }

    public record RetentionPolicyCommand(
            String dataCategory,
            String sourceModule,
            String retentionPeriod,
            boolean cleanupEnabled,
            LocalDateTime effectiveAt,
            String changeReason
    ) {
    }

    static BusinessException invalid(String message) {
        return new BusinessException(400, AuditGovernanceErrors.RETENTION_POLICY_INVALID, message);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
