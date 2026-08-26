package com.platform.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
/** 使用当前数据库事实执行职责和研发/生产职责分离校验。 */
public class JdbcBackendDutyService implements BackendDutyService {
    private final JdbcTemplate jdbcTemplate;
    private final AuditGovernanceProperties properties;

    @Override
    public boolean hasDuty(long userId, BackendDuty duty) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_user_backend_duty a
                JOIN sys_backend_duty d ON d.duty_key=a.duty_key AND d.status='ENABLED'
                WHERE a.user_id=? AND a.duty_key=? AND a.status='ACTIVE'
                  AND a.effective_at<=CURRENT_TIMESTAMP
                  AND (a.expires_at IS NULL OR a.expires_at>CURRENT_TIMESTAMP)
                """, Integer.class, userId, duty.name());
        return count != null && count > 0;
    }

    @Override
    public void requireDuty(long userId, BackendDuty duty) {
        if (!hasDuty(userId, duty)) {
            throw AuditGovernanceErrors.forbidden(AuditGovernanceErrors.DUTY_REQUIRED);
        }
    }

    @Override
    public void requireSeparation(long submitterId, long reviewerId) {
        if (submitterId != reviewerId) {
            return;
        }
        boolean developmentException = properties.isAllowSelfApproval()
                && properties.getEnvironmentMode() != AuditEnvironmentMode.PRODUCTION;
        if (!developmentException) {
            throw AuditGovernanceErrors.forbidden(AuditGovernanceErrors.SELF_APPROVAL_DENIED);
        }
    }

    @Override
    public void evict(long userId) {
        // 当前实现不缓存职责，数据库变化立即生效；空操作维持统一的失效契约。
    }
}
