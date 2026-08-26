package com.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.sensitive.SensitiveChangeRecord;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.audit.sensitive.SensitiveChangeStatus;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SensitiveChangeServiceIntegrationTest {
    private static final long ADMIN_ID = 1L;

    @Autowired private SensitiveChangeService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void prepareDuties() {
        jdbcTemplate.update("DELETE FROM sys_security_audit_event");
        jdbcTemplate.update("DELETE FROM sys_sensitive_change_request");
        jdbcTemplate.update("DELETE FROM sys_user_backend_duty");
        grant(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grant(BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        MDC.put(TraceContext.MDC_KEY, "0123456789abcdef0123456789abcdef");
    }

    @AfterEach
    void clearTrace() {
        MDC.remove(TraceContext.MDC_KEY);
    }

    @Test
    void developmentSelfApprovalStillUsesDraftSubmitApproveAndExecute() {
        SensitiveChangeRecord draft = service.createDraft(ADMIN_ID, "GRANT_BACKEND_DUTY",
                objectMapper.createObjectNode().put("userId", ADMIN_ID)
                        .put("dutyKey", BackendDuty.AUDIT_EVIDENCE_VIEWER.name()), "grant-viewer-1");
        assertThat(draft.status()).isEqualTo(SensitiveChangeStatus.DRAFT);

        assertThat(service.submit(ADMIN_ID, draft.requestId()).status())
                .isEqualTo(SensitiveChangeStatus.PENDING_REVIEW);
        SensitiveChangeRecord approved = service.approve(ADMIN_ID, draft.requestId(), "研发单人完整两步审核");
        assertThat(approved.status()).isEqualTo(SensitiveChangeStatus.APPROVED);
        assertThat(approved.selfApprovalDevMode()).isTrue();

        SensitiveChangeRecord executed = service.execute(ADMIN_ID, draft.requestId());
        assertThat(executed.status()).isEqualTo(SensitiveChangeStatus.EXECUTED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_user_backend_duty
                WHERE user_id=? AND duty_key=? AND status='ACTIVE'
                """, Integer.class, ADMIN_ID, BackendDuty.AUDIT_EVIDENCE_VIEWER.name())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event WHERE review_request_id=?
                """, Integer.class, draft.requestId())).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE review_request_id=? AND self_approval_dev_mode=TRUE
                """, Integer.class, draft.requestId())).isEqualTo(2);
    }

    @Test
    void executionFailureRollsBackHandlerAndPersistsFailureEvidence() {
        SensitiveChangeRecord draft = service.createDraft(ADMIN_ID, "REVOKE_BACKEND_DUTY",
                objectMapper.createObjectNode().put("userId", ADMIN_ID)
                        .put("dutyKey", BackendDuty.AUDIT_EVIDENCE_EXPORTER.name()), "revoke-missing-1");
        service.submit(ADMIN_ID, draft.requestId());
        service.approve(ADMIN_ID, draft.requestId(), "批准失败路径验证");

        assertThatThrownBy(() -> service.execute(ADMIN_ID, draft.requestId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(AuditGovernanceErrors.REQUEST_CONFLICT);
        SensitiveChangeRecord failed = service.detail(ADMIN_ID, draft.requestId());
        assertThat(failed.status()).isEqualTo(SensitiveChangeStatus.EXECUTION_FAILED);
        assertThat(failed.executionErrorCode()).isEqualTo("SENSITIVE_OPERATION_EXECUTION_FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT result FROM sys_security_audit_event
                WHERE review_request_id=? AND action_type='EXECUTE_SENSITIVE_CHANGE'
                """, String.class, draft.requestId())).isEqualTo("FAILED");
    }

    @Test
    void sameIdempotencyKeyCannotChangeFrozenCommand() {
        var first = objectMapper.createObjectNode().put("userId", ADMIN_ID)
                .put("dutyKey", BackendDuty.AUDIT_EVIDENCE_VIEWER.name());
        service.createDraft(ADMIN_ID, "GRANT_BACKEND_DUTY", first, "same-key");
        var changed = objectMapper.createObjectNode().put("userId", ADMIN_ID)
                .put("dutyKey", BackendDuty.AUDIT_EVIDENCE_EXPORTER.name());

        assertThatThrownBy(() -> service.createDraft(ADMIN_ID, "GRANT_BACKEND_DUTY", changed, "same-key"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(AuditGovernanceErrors.REQUEST_CONFLICT);
    }

    private void grant(BackendDuty duty) {
        jdbcTemplate.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID().toString().replace("-", ""), ADMIN_ID, duty.name(),
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), ADMIN_ID,
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));
    }
}
