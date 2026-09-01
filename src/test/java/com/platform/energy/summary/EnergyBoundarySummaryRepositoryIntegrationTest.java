package com.platform.energy.summary;

import com.platform.energy.summary.EnergySummaryModels.BoundaryPolicyVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EnergyBoundarySummaryRepositoryIntegrationTest {
    @Autowired private EnergyBoundarySummaryRepository repository;

    @Test
    void persistsAndApprovesImmutableSimulationPolicyVersion() {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        repository.insertIdentity("POLICY001", "BLD001", "BOUNDARY001",
                "GRID_ELECTRICITY", 101L, from);
        BoundaryPolicyVersion pending = new BoundaryPolicyVersion("POLICY001", "VERSION001", 1,
                "BLD001", "BOUNDARY001", "GRID_ELECTRICITY", "MAIN_METER_TOTAL",
                "PENDING_EXPERT", "SIMULATION", "研发模拟口径", from, null, 0,
                101L, from, null, null, null);
        repository.insertVersion(pending);

        assertThat(repository.approve("VERSION001", 0, 202L, from.plusDays(1), "模拟审核"))
                .isEqualTo(1);
        assertThat(repository.findApproved("BLD001", "BOUNDARY001", "GRID_ELECTRICITY",
                from.plusDays(2))).satisfies(value -> {
                    assertThat(value.status()).isEqualTo("APPROVED");
                    assertThat(value.aggregationMode()).isEqualTo("MAIN_METER_TOTAL");
                    assertThat(value.configRevision()).isEqualTo(1);
                });
    }
}
