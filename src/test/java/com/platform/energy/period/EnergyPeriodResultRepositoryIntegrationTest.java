package com.platform.energy.period;

import com.platform.energy.period.EnergyPeriodModels.CurrentProjection;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.period.EnergyPeriodModels.RecalculationBatch;
import com.platform.energy.period.EnergyPeriodModels.RecalculationItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EnergyPeriodResultRepositoryIntegrationTest {
    private static final Instant START = Instant.parse("2026-07-31T16:00:00Z");
    private static final Instant END = Instant.parse("2026-08-31T16:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 9, 0);

    @Autowired private EnergyPeriodResultRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistsCurrentSnapshotAndBoundedRecalculationState() {
        CurrentProjection current = current();
        repository.insertCurrent(current);
        assertThat(repository.findCurrent("PROJ001"))
                .satisfies(value -> {
                    assertThat(value.projectionId()).isEqualTo(current.projectionId());
                    assertThat(value.nativeQuantity()).isEqualByComparingTo(current.nativeQuantity());
                    assertThat(value.tce()).isEqualByComparingTo(current.tce());
                    assertThat(value.coverageRatio()).isEqualByComparingTo(current.coverageRatio());
                    assertThat(value.evidenceHash()).isEqualTo(current.evidenceHash());
                });

        PeriodSnapshot snapshot = snapshot();
        repository.insertSnapshot(snapshot);
        assertThat(repository.findVisibleSnapshot("PROJ001").snapshotId()).isEqualTo("SNAP001");

        RecalculationBatch batch = batch();
        repository.insertBatch(batch);
        repository.insertBatchItems(List.of(new RecalculationItem(
                "ITEM001", "BATCH001", "SNAP001", 1, "PENDING", null, null)));
        repository.updateBatchItem("ITEM001", "UNCHANGED", null, null);
        repository.refreshBatchCounters("BATCH001");

        assertThat(repository.findBatch("BATCH001"))
                .satisfies(value -> {
                    assertThat(value.processedItems()).isEqualTo(1);
                    assertThat(value.unchangedItems()).isEqualTo(1);
                });
        assertThat(repository.advanceBatch("BATCH001", "PENDING_REVIEW", "VALIDATING"))
                .isEqualTo(1);
        assertThat(repository.advanceBatch("BATCH001", "VALIDATING", "CALCULATING"))
                .isEqualTo(1);
        assertThat(repository.advanceBatch("BATCH001", "CALCULATING", "WRITING_RESULTS"))
                .isEqualTo(1);
        assertThat(repository.completeBatch("BATCH001", NOW.plusMinutes(1))).isEqualTo(1);
        assertThat(repository.findBatch("BATCH001").status()).isEqualTo("COMPLETED");
    }

    @Test
    void mergesRepeatedDirtySignalsIntoOnePeriodRecord() {
        repository.markDirty("DIRTY001", "BLD001", "POINT001", "MONTH",
                START, END, "LATE_DATA", NOW);
        repository.markDirty("IGNORED_ID", "BLD001", "POINT001", "MONTH",
                START, END, "CORRECTION", NOW.plusMinutes(1));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_energy_dirty_period
                WHERE building_id='BLD001' AND point_id='POINT001' AND period_type='MONTH'
                """, Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT event_count FROM biz_energy_dirty_period
                WHERE building_id='BLD001' AND point_id='POINT001' AND period_type='MONTH'
                """, Integer.class))
                .isEqualTo(2);
    }

    private CurrentProjection current() {
        return new CurrentProjection("PROJ001", "a".repeat(64), "BLD001", "POINT001",
                "MONTH", START, END, "Asia/Shanghai", "POLICY_VERSION001", "PROVISIONAL",
                1, "DEVELOPMENT_SIMULATION", "GRID_ELECTRICITY", new BigDecimal("928.127"),
                "kWh", new BigDecimal("0.1138075"), "tce", BigDecimal.ONE, "",
                "{\"source\":\"SIMULATION\"}", "c".repeat(64), null, END, NOW, NOW);
    }

    private PeriodSnapshot snapshot() {
        return new PeriodSnapshot("SNAP001", "b".repeat(64), "PROJ001", "BLD001",
                "POINT001", "MONTH", START, END, "Asia/Shanghai", "POLICY_VERSION001", 1,
                "LOCKED_COMPLETE", "DEVELOPMENT_SIMULATION", "GRID_ELECTRICITY",
                new BigDecimal("928.127"), "kWh", new BigDecimal("0.1138075"), "tce",
                BigDecimal.ONE, "", "", "{\"source\":\"SIMULATION\"}", "c".repeat(64),
                null, END, null, null, 101L, NOW);
    }

    private RecalculationBatch batch() {
        return new RecalculationBatch("BATCH001", "BLD001", "period-recalc-001",
                "d".repeat(64), "SAME_RULES", "PENDING_REVIEW", "研发模拟迟到数据复算",
                1, 0, 0, 0, 0, 101L, NOW, null, null, null, null, null);
    }
}
