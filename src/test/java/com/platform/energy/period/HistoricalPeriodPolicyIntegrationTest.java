package com.platform.energy.period;

import com.platform.energy.period.api.EnergyPeriodContracts.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HistoricalPeriodPolicyIntegrationTest {
    private static final Set<String> ROLES = Set.of("ENERGY_MANAGER");
    private static final LocalDateTime SEP = LocalDateTime.of(2025, 9, 1, 0, 0);
    @Autowired private EnergyPeriodGovernanceService service;
    @Autowired private JdbcTemplate jdbc;
    @BeforeEach void authority() {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (101,'BLD001')");
        for (String duty : Set.of("ENERGY_RULE_MAINTAIN", "ENERGY_RULE_REVIEW")) {
            jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by) VALUES (?,101,?,'ACTIVE',CURRENT_TIMESTAMP,1)", duty, duty);
        }
    }
    private EnergyPeriodModels.PeriodPolicyVersion create(LocalDateTime from, LocalDateTime to) {
        return service.createPeriodPolicy(101, ROLES, new CreatePeriodPolicyRequest("BLD001",
                "Asia/Shanghai", 0, "REVIEW_REQUIRED", "FORMAL", "隔离测试历史证据", from, to));
    }
    private ApproveRequest review() { return new ApproveRequest(0, "隔离测试审核"); }

    @Test void historicalPeriodAndForwardVersionsUseBusinessTimeAndPreserveClosedHistory() {
        var current = create(SEP, null);
        service.approvePeriodPolicy(101, ROLES, current.versionId(), review());
        var past = create(SEP.minusMonths(8), SEP);
        var approved = service.approvePeriodPolicyHistorical(101, ROLES, past.versionId(), review());
        assertThat(approved.approvedAt()).isAfter(SEP);
        assertThat(service.effectivePolicy("BLD001", SEP.minusMonths(1)).versionId()).isEqualTo(past.versionId());
        assertThat(service.effectivePolicy("BLD001", SEP).versionId()).isEqualTo(current.versionId());
        var next = create(SEP.plusYears(1), null);
        service.approvePeriodPolicy(101, ROLES, next.versionId(), review());
        assertThat(service.periodPolicyVersion(past.versionId()).effectiveTo()).isEqualTo(SEP);
        assertThat(service.periodPolicyVersion(current.versionId()).effectiveTo()).isEqualTo(SEP.plusYears(1));
        assertThat(service.effectivePolicy("BLD001", SEP).versionId()).isEqualTo(current.versionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_security_audit_event WHERE action_type='APPROVE_HISTORICAL_PERIOD_POLICY_VERSION'", Integer.class)).isEqualTo(1);
    }

    @Test void rejectsOverlapsAndOpenPeriodsWithoutChangingCurrentPolicyOrRestoringDisabledRecords() {
        var current = create(SEP, null);
        service.approvePeriodPolicy(101, ROLES, current.versionId(), review());
        var overlap = create(SEP.minusMonths(1), SEP.plusMonths(1));
        assertThatThrownBy(() -> service.approvePeriodPolicyHistorical(101, ROLES, overlap.versionId(), review())).hasMessageContaining("重叠");
        var open = create(SEP.minusMonths(1), null);
        assertThatThrownBy(() -> service.approvePeriodPolicyHistorical(101, ROLES, open.versionId(), review())).hasMessageContaining("闭合");
        assertThat(service.periodPolicyVersion(current.versionId()).effectiveTo()).isNull();
        jdbc.update("UPDATE biz_energy_period_policy_version SET status='DISABLED' WHERE version_id=?", current.versionId());
        var past = create(SEP.minusMonths(1), SEP);
        service.approvePeriodPolicyHistorical(101, ROLES, past.versionId(), review());
        assertThat(service.periodPolicyVersion(current.versionId()).status()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> service.effectivePolicy("BLD001", SEP)).hasMessageContaining("缺少");
    }
}
