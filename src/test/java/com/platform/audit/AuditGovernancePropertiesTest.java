package com.platform.audit;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditGovernancePropertiesTest {
    @Test
    void productionCannotStartWithSelfApprovalEnabled() {
        AuditGovernanceProperties properties = new AuditGovernanceProperties();
        properties.setEnvironmentMode(AuditEnvironmentMode.PRODUCTION);
        properties.setAllowSelfApproval(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产环境禁止");
    }

    @Test
    void sameSubmitterAndReviewerAreRejectedWithoutDevelopmentException() {
        AuditGovernanceProperties properties = new AuditGovernanceProperties();
        properties.setEnvironmentMode(AuditEnvironmentMode.PRODUCTION);
        properties.setAllowSelfApproval(false);
        JdbcBackendDutyService service = new JdbcBackendDutyService(null, properties);

        assertThatThrownBy(() -> service.requireSeparation(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(AuditGovernanceErrors.SELF_APPROVAL_DENIED));
    }

    @Test
    void cleanupBatchLimitsRejectUnsafeConfiguration() {
        AuditGovernanceProperties properties = new AuditGovernanceProperties();
        properties.setRetentionCleanupBatchSize(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("清理周期或批次");
    }
}
