package com.platform.audit.duty;

import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
/** 只供已批准敏感操作处理器调用的职责授予与撤销服务。 */
public class BackendDutyAssignmentService {
    private final JdbcTemplate jdbcTemplate;
    private final BackendDutyService dutyService;

    @Transactional(rollbackFor = Exception.class)
    public void grant(long userId, BackendDuty duty, LocalDateTime effectiveAt, LocalDateTime expiresAt,
                      String sourceRequestId, long operatorId) {
        requireUser(userId);
        LocalDateTime effective = effectiveAt == null ? LocalDateTime.now() : effectiveAt;
        if (expiresAt != null && !expiresAt.isAfter(effective)) {
            throw AuditGovernanceErrors.conflict(AuditGovernanceErrors.REQUEST_CONFLICT, "职责失效时间必须晚于生效时间");
        }
        int updated = jdbcTemplate.update("""
                UPDATE sys_user_backend_duty
                SET status='ACTIVE',effective_at=?,expires_at=?,source_request_id=?,created_by=?,created_at=?,
                    revoked_by=NULL,revoked_at=NULL,revoke_request_id=NULL
                WHERE user_id=? AND duty_key=?
                """, timestamp(effective), timestamp(expiresAt), sourceRequestId, operatorId,
                timestamp(LocalDateTime.now()), userId, duty.name());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO sys_user_backend_duty
                    (assignment_id,user_id,duty_key,status,effective_at,expires_at,source_request_id,created_by,created_at)
                    VALUES (?,?,?,'ACTIVE',?,?,?,?,?)
                    """, id(), userId, duty.name(), timestamp(effective), timestamp(expiresAt),
                    sourceRequestId, operatorId, timestamp(LocalDateTime.now()));
        }
        dutyService.evict(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(long userId, BackendDuty duty, String requestId, long operatorId) {
        requireUser(userId);
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcTemplate.update("""
                UPDATE sys_user_backend_duty
                SET status='REVOKED',revoked_by=?,revoked_at=?,revoke_request_id=?
                WHERE user_id=? AND duty_key=? AND status='ACTIVE'
                """, operatorId, timestamp(now), requestId, userId, duty.name());
        if (updated != 1) {
            throw AuditGovernanceErrors.conflict(AuditGovernanceErrors.REQUEST_CONFLICT, "职责授权状态已变化");
        }
        dutyService.evict(userId);
    }

    private void requireUser(long userId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, userId);
        if (count == null || count == 0) {
            throw AuditGovernanceErrors.conflict(AuditGovernanceErrors.REQUEST_CONFLICT, "目标账号不可用");
        }
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
