package com.platform.energy.summary;

import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.summary.EnergySummaryModels.BoundaryPolicyVersion;
import com.platform.energy.summary.api.EnergySummaryContracts.ApproveBoundaryPolicyRequest;
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
class HistoricalBoundaryPolicyIntegrationTest {
    private static final Set<String> ROLES = Set.of("ENERGY_MANAGER");
    private static final LocalDateTime SEP = LocalDateTime.of(2025, 9, 1, 0, 0);
    @Autowired private EnergyBoundarySummaryService service;
    @Autowired private EnergyBoundarySummaryRepository repository;
    @Autowired private EnergyCatalogService catalog;
    @Autowired private JdbcTemplate jdbc;
    @BeforeEach void setup() {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (101,'BLD001')");
        for (String duty : Set.of("ENERGY_RULE_REVIEW", "ENERGY_CATALOG_REVIEW")) {
            jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by) VALUES (?,101,?,'ACTIVE',CURRENT_TIMESTAMP,1)", duty, duty);
        }
        catalog.approveItem(101, ROLES, "EIV_ELECTRICITY_1", new com.platform.energy.catalog.api.EnergyCatalogContracts.ApproveRequest(0, "隔离测试审核"));
        repository.insertIdentity("HISTORY_SUMMARY", "BLD001", "BOUNDARY", "ELECTRICITY", 101, LocalDateTime.now());
    }
    private String pending(String id, int version, LocalDateTime from, LocalDateTime to) {
        repository.insertVersion(new BoundaryPolicyVersion("HISTORY_SUMMARY", id, version,
                "BLD001", "BOUNDARY", "ELECTRICITY", "MAIN_METER_TOTAL", "PENDING_EXPERT", "FORMAL",
                "隔离历史依据", from, to, 0, 101, LocalDateTime.now(), null, null, null));
        return id;
    }
    private ApproveBoundaryPolicyRequest review() { return new ApproveBoundaryPolicyRequest(0, "隔离测试审核"); }
    @Test void fillsEarlierSummaryWindowAndKeepsBusinessDateSelectionAfterForwardApproval() {
        service.approvePolicy(101, ROLES, pending("CURRENT", 1, SEP, null), review());
        service.approvePolicyHistorical(101, ROLES, pending("HISTORY", 2, SEP.minusMonths(8), SEP), review());
        service.approvePolicy(101, ROLES, pending("NEXT", 3, SEP.plusYears(1), null), review());
        assertThat(service.policyForPeriod(101, ROLES, "BLD001", "BOUNDARY", "ELECTRICITY", SEP.minusMonths(1), SEP).versionId()).isEqualTo("HISTORY");
        assertThat(repository.findVersion("CURRENT").effectiveTo()).isEqualTo(SEP.plusYears(1));
        assertThat(repository.findVersion("HISTORY").effectiveTo()).isEqualTo(SEP);
    }
    @Test void overlappingHistoryIsRejectedWithoutClosingCurrentPolicy() {
        service.approvePolicy(101, ROLES, pending("CURRENT", 1, SEP, null), review());
        pending("OVERLAP", 2, SEP.minusMonths(1), SEP.plusMonths(1));
        assertThatThrownBy(() -> service.approvePolicyHistorical(101, ROLES, "OVERLAP", review())).hasMessageContaining("重叠");
        assertThat(repository.findVersion("CURRENT").effectiveTo()).isNull();
        assertThat(repository.findVersion("OVERLAP").status()).isEqualTo("PENDING_EXPERT");
    }
}
