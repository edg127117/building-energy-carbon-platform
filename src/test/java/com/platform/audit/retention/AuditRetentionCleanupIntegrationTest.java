package com.platform.audit.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.BackendDuty;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AuditRetentionCleanupIntegrationTest {
    private static final long ADMIN_ID = 1L;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SensitiveChangeService sensitiveChangeService;
    @Autowired private AuditRetentionPolicyRepository policyRepository;
    @Autowired private AuditRetentionPolicyService policyService;
    @Autowired private AuditEvidenceHoldService holdService;
    @Autowired private AuditCleanupService cleanupService;
    @Autowired private AuditGovernanceProperties properties;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM sys_audit_cleanup_run");
        jdbc.update("DELETE FROM sys_audit_evidence_hold");
        jdbc.update("DELETE FROM sys_audit_retention_policy");
        jdbc.update("DELETE FROM sys_security_audit_event");
        jdbc.update("DELETE FROM sys_sensitive_change_request");
        jdbc.update("DELETE FROM sys_user_backend_duty");
        grant(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grant(BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        properties.setRetentionCleanupBatchSize(1);
        properties.setRetentionCleanupMaxBatches(10);
    }

    @Test
    void retentionPolicyRequiresSpecialDutyAndCreatesImmutableVersionsAfterApproval() throws Exception {
        var command = objectMapper.createObjectNode()
                .put("dataCategory", "SECURITY_EVENT")
                .put("sourceModule", "SYSTEM_SECURITY")
                .put("retentionPeriod", "P6M")
                .put("cleanupEnabled", true)
                .put("effectiveAt", LocalDateTime.now().minusMinutes(1).toString())
                .put("changeReason", "验证自然月保留策略");

        assertThatThrownBy(() -> sensitiveChangeService.createDraft(
                ADMIN_ID, SetAuditRetentionPolicyHandler.OPERATION_CODE, command, "retention-no-duty"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(AuditGovernanceErrors.DUTY_REQUIRED));

        grant(BackendDuty.AUDIT_RETENTION_MANAGER);
        executePolicyChange(command, "retention-v1");
        command.put("retentionPeriod", "P1Y").put("cleanupEnabled", false)
                .put("effectiveAt", LocalDateTime.now().plusDays(1).toString())
                .put("changeReason", "停用自动清理并保留新版本");
        executePolicyChange(command, "retention-v2");

        var versions = policyRepository.findAll();
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).policyVersion()).isEqualTo(2);
        assertThat(versions.get(0).retentionPeriod()).isEqualTo("P1Y");
        assertThat(versions.get(0).lifecycleStatus()).isEqualTo("CURRENT");
        assertThat(versions.get(1).lifecycleStatus()).isEqualTo("SUPERSEDED");
        assertThat(policyRepository.findDueCurrent(LocalDateTime.now()))
                .extracting(AuditRetentionPolicy::policyVersion).containsExactly(1);
        assertThat(policyRepository.findDueCurrent(LocalDateTime.now().plusDays(2))).isEmpty();
    }

    @Test
    void automaticCleanupUsesCalendarPeriodAndExcludesHoldsAndPendingApprovals() {
        grant(BackendDuty.AUDIT_EVIDENCE_HOLD_MANAGER);
        LocalDateTime fixedNow = LocalDateTime.of(2026, 8, 31, 12, 0);
        String deletedId = audit("DELETE_ME", fixedNow.minusMonths(6).minusSeconds(1), null);
        String boundaryId = audit("AT_CALENDAR_BOUNDARY", fixedNow.minusMonths(6), null);
        String heldId = audit("HELD", fixedNow.minusYears(1), null);

        var draft = sensitiveChangeService.createDraft(ADMIN_ID, "GRANT_BACKEND_DUTY",
                objectMapper.createObjectNode().put("userId", ADMIN_ID)
                        .put("dutyKey", BackendDuty.AUDIT_EVIDENCE_VIEWER.name()), "pending-protection");
        String pendingId = audit("PENDING_REVIEW", fixedNow.minusYears(1), draft.requestId());
        holdService.create(ADMIN_ID, Set.of("PLATFORM_ADMIN"), new AuditEvidenceHoldService.CreateHold(
                "SYSTEM_SECURITY", heldId, null, null, null, null, null, null,
                "INV-001", "安全调查未结案", "内部调查单", fixedNow.plusMonths(1)));

        AuditRetentionPolicy policy = policyService.apply(
                new AuditRetentionPolicyService.RetentionPolicyCommand("SECURITY_EVENT", "SYSTEM_SECURITY",
                        "P6M", true, fixedNow.minusDays(1), "六个自然月"),
                "policy-request", ADMIN_ID, ADMIN_ID);
        cleanupService.executePolicy(policy, fixedNow, "cleanup-trace");

        assertThat(exists(deletedId)).isFalse();
        assertThat(exists(boundaryId)).isTrue();
        assertThat(exists(heldId)).isTrue();
        assertThat(exists(pendingId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT held_count FROM sys_audit_cleanup_run", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT protected_count FROM sys_audit_cleanup_run", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_audit_cleanup_run", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void approvedExceptionDeletionRechecksCountAndNeverDeletesHeldEvidence() {
        grant(BackendDuty.AUDIT_EVIDENCE_HOLD_MANAGER);
        LocalDateTime old = LocalDateTime.now().minusYears(2);
        String first = audit("MANUAL_DELETE", old, null);
        String held = audit("MANUAL_DELETE", old.plusSeconds(1), null);
        holdService.create(ADMIN_ID, Set.of("PLATFORM_ADMIN"), new AuditEvidenceHoldService.CreateHold(
                "SYSTEM_SECURITY", held, null, null, null, null, null, null,
                "INV-002", "保留其中一条", "调查单", LocalDateTime.now().plusMonths(1)));
        AuditExceptionCleanupCommand command = cleanupService.normalizeException(
                new AuditExceptionCleanupCommand("MANUAL_SCOPE", "SECURITY_EVENT", "SYSTEM_SECURITY",
                        new AuditRetentionScope(null, null, "MANUAL_DELETE", null, null,
                                old.minusSeconds(1), old.plusDays(1)),
                        "删除脱敏测试数据", "研发数据销毁单", 1, 0));

        cleanupService.enqueueException(command, json(command), "exception-request", ADMIN_ID);
        cleanupService.runApprovedExceptions();

        assertThat(exists(first)).isFalse();
        assertThat(exists(held)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM sys_audit_cleanup_run", String.class))
                .isEqualTo("SUCCEEDED");

        String extra = audit("COUNT_CHANGED", old, null);
        AuditExceptionCleanupCommand changed = cleanupService.normalizeException(
                new AuditExceptionCleanupCommand("EARLY_DELETE", "SECURITY_EVENT", "SYSTEM_SECURITY",
                        new AuditRetentionScope(extra, null, null, null, null, null, null),
                        "数量快照不一致", "研发数据销毁单", 0, 0));
        assertThatThrownBy(() -> cleanupService.enqueueException(
                changed, json(changed), "count-changed", ADMIN_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(AuditGovernanceErrors.CLEANUP_SCOPE_CHANGED));
    }

    private void executePolicyChange(com.fasterxml.jackson.databind.JsonNode command, String key) {
        var draft = sensitiveChangeService.createDraft(
                ADMIN_ID, SetAuditRetentionPolicyHandler.OPERATION_CODE, command, key);
        sensitiveChangeService.submit(ADMIN_ID, draft.requestId());
        sensitiveChangeService.approve(ADMIN_ID, draft.requestId(), "研发单人完整两步审批");
        sensitiveChangeService.execute(ADMIN_ID, draft.requestId());
    }

    private String audit(String action, LocalDateTime time, String reviewRequestId) {
        String id = id();
        jdbc.update("""
                INSERT INTO sys_security_audit_event
                (audit_id,source_module,actor_type,action_type,object_type,object_id,review_request_id,
                 result,trace_id,operation_time,environment_mode,self_approval_dev_mode)
                VALUES (?,'SYSTEM_SECURITY','SYSTEM',?,'TEST_OBJECT',? ,?,'SUCCESS','test-trace',?,'TEST',FALSE)
                """, id, action, id, reviewRequestId, Timestamp.valueOf(time));
        return id;
    }

    private boolean exists(String auditId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_security_audit_event WHERE audit_id=?",
                Integer.class, auditId) == 1;
    }

    private void grant(BackendDuty duty) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, id(), ADMIN_ID, duty.name(), Timestamp.valueOf(now), ADMIN_ID, Timestamp.valueOf(now));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
