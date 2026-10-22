package com.platform.iot.reliability;

import com.platform.audit.BackendDutyService;
import com.platform.energy.aggregation.EnergyAggregationAuthorization;
import com.platform.energy.aggregation.EnergyAggregationGovernanceRepository;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.CorrectionVersionView;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.CreateCorrectionVersionRequest;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelemetryConflictEnergyTest {
    private static final String CORRECTION_ID = "C1";
    private static final long EVENT_TIME = 1_800_000_000_000L;

    private TelemetryConflictTest fixture;
    private EnergyAggregationGovernanceRepository repository;

    @BeforeEach
    void setup() {
        fixture = new TelemetryConflictTest();
        fixture.setup();
        fixture.jdbc.update("""
                INSERT INTO biz_data_point
                (point_id,point_code,point_name,building_id,naming_rule_id,family_code,
                 component_code,suffix_code,data_type,unit,is_for_calc,status,del_flag)
                VALUES ('P1','METER_ELECTRICITY_TOTAL','能源累计测点','B1','RULE_WCR_MAIN',
                        'WCR','MAIN','PPE','ACCUMULATE','kWh',1,'ONLINE',0)
                """);
        fixture.jdbc.update("""
                INSERT INTO biz_energy_activity_correction
                (correction_id,building_id,meter_point_id,original_fact_identity,created_by,created_at)
                VALUES ('C1','B1','P1',?,7,CURRENT_TIMESTAMP)
                """, identity());
        repository = new EnergyAggregationGovernanceRepository(fixture.jdbc);
    }

    @Test
    void energySelectionSubmitsExistingReviewAndCannotDirectlyBypassLedger() {
        var result = submitPendingCorrection("CV_PENDING");

        assertThat(result.state()).isEqualTo("PENDING_REVIEW");
        assertThat(result.effectiveValue()).isEqualTo(100d);
        assertThat(result.correctionVersionId()).isEqualTo("CV_PENDING");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT selected_json FROM biz_telemetry_raw_intent
                WHERE point_key=? AND event_ms=?
                """, String.class, fixture.codec.key("P1"), EVENT_TIME)).isNull();
        assertThat(fixture.ledger.effective(List.of(fixture.event(100))))
                .containsExactly(fixture.event(100));
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_change_task", Integer.class))
                .isZero();

        var request = ArgumentCaptor.forClass(CreateCorrectionVersionRequest.class);
        verify(fixture.energy).createCorrection(eq(7L), eq(List.of("BUILDING_OWNER")), request.capture());
        assertThat(request.getValue().correctionId()).isEqualTo(CORRECTION_ID);
        assertThat(request.getValue().originalFactIdentity()).isEqualTo(identity());
        assertThat(request.getValue().originalValue()).isEqualByComparingTo("100");
        assertThat(request.getValue().correctedValue()).isEqualByComparingTo("120");
        assertThat(request.getValue().sourceType()).isEqualTo("DEVICE");
    }

    @Test
    void newConflictMakesOldEnergySubmissionIneligibleForReview() {
        submitPendingCorrection("CV_STALE");
        insertPendingCorrection("CV_STALE");

        fixture.transaction(() -> {
            repository.lockCorrectionIdentity(CORRECTION_ID);
            repository.requireCurrentTelemetryResolution("CV_STALE");
            return null;
        });
        fixture.ledger.reserve(fixture.event(130), () -> null);

        assertThat(fixture.ledger.find("P1", EVENT_TIME).conflictState()).isEqualTo("OPEN");
        assertThatThrownBy(() -> fixture.transaction(() -> {
            repository.lockCorrectionIdentity(CORRECTION_ID);
            repository.requireCurrentTelemetryResolution("CV_STALE");
            return null;
        })).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("TELEMETRY_CONFLICT_REVISION"));
        assertThat(fixture.jdbc.queryForObject("""
                SELECT status FROM biz_energy_activity_correction_version
                WHERE correction_version_id='CV_STALE'
                """, String.class)).isEqualTo("PENDING_REVIEW");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_change_task", Integer.class))
                .isZero();
    }

    @Test
    void approvedEnergyCorrectionIsConsumedOnceAndQueuesDurableTask() {
        submitPendingCorrection("CV_APPROVED");
        insertPendingCorrection("CV_APPROVED");

        assertThat(fixture.ledger.effective(List.of(fixture.event(100))))
                .containsExactly(fixture.event(100));

        fixture.transaction(() -> {
            repository.lockCorrectionIdentity(CORRECTION_ID);
            repository.requireCurrentTelemetryResolution("CV_APPROVED");
            assertThat(repository.approveCorrection("CV_APPROVED", 0, 9L,
                    LocalDateTime.of(2026, 1, 1, 0, 0), "审核通过", "Q0:1")).isEqualTo(1);
            repository.correctionChanged(repository.findCorrectionVersion("CV_APPROVED"));
            return null;
        });

        assertThat(fixture.jdbc.queryForObject("""
                SELECT state FROM biz_telemetry_change_task
                WHERE point_id='P1' AND building_id='B1' AND event_ms=?
                """, String.class, EVENT_TIME)).isEqualTo("PENDING");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT conflict_state FROM biz_telemetry_raw_intent
                WHERE point_key=? AND event_ms=?
                """, String.class, fixture.codec.key("P1"), EVENT_TIME)).isEqualTo("RESOLVED");
        assertThat(fixture.ledger.selections(List.of("P1"), EVENT_TIME, EVENT_TIME + 1))
                .hasSize(1)
                .extractingByKey(identity())
                .extracting(RawTelemetryLedger.Selection::versionId,
                        RawTelemetryLedger.Selection::selectedValue)
                .containsExactly("CV_APPROVED", 120d);
        assertThat(fixture.ledger.effective(List.of(fixture.event(100))))
                .containsExactly(fixture.event(120));
        assertThat(fixture.codec.decode(fixture.ledger.find("P1", EVENT_TIME).snapshotJson()).value())
                .isEqualTo(100d);
        assertThat(fixture.ledger.find("P1", EVENT_TIME).selectedJson()).isNull();
    }

    @Test
    void derivedCapacityFailureRollsBackEnergyApproval() {
        submitPendingCorrection("CV_CAPACITY");insertPendingCorrection("CV_CAPACITY");
        TelemetryConflictService.enqueue(fixture.jdbc,"P2","B1",EVENT_TIME,"other",1);
        org.springframework.test.util.ReflectionTestUtils.setField(repository,"maxDerived",1);
        assertThatThrownBy(()->fixture.transaction(()->{
            repository.lockCorrectionIdentity(CORRECTION_ID);repository.requireCurrentTelemetryResolution("CV_CAPACITY");
            repository.approveCorrection("CV_CAPACITY",0,9L,LocalDateTime.now(),"审核通过","Q0:1");
            repository.correctionChanged(repository.findCorrectionVersion("CV_CAPACITY"));return null;
        })).hasMessageContaining("capacity");
        assertThat(repository.findCorrectionVersion("CV_CAPACITY").status()).isEqualTo("PENDING_REVIEW");
        assertThat(fixture.ledger.effective(List.of(fixture.event(100)))).containsExactly(fixture.event(100));
    }

    @Test
    void restoringOriginalAfterAnApprovedCorrectionStillUsesTheExistingReview() {
        submitPendingCorrection("CV_APPROVED");insertPendingCorrection("CV_APPROVED");
        repository.approveCorrection("CV_APPROVED",0,9L,LocalDateTime.now(),"审核通过","Q0:1");
        fixture.transaction(()->{repository.correctionChanged(repository.findCorrectionVersion("CV_APPROVED"));return null;});
        var before=fixture.conflicts.detail(7,List.of("BUILDING_OWNER"),"P1",EVENT_TIME);
        when(fixture.energy.createCorrection(anyLong(),anyCollection(),any(CreateCorrectionVersionRequest.class))).thenReturn(pendingCorrection("CV_RESTORE"));
        var result=fixture.transaction(()->fixture.conflicts.resolve(7,List.of("BUILDING_OWNER"),"P1",EVENT_TIME,
                new TelemetryConflictService.ResolveRequest(null,before.revision(),"核实应恢复原值","工单2")));
        assertThat(result.state()).isEqualTo("PENDING_REVIEW");
        assertThat(result.effectiveValue()).isEqualTo(120d);
        assertThat(result.correctionVersionId()).isEqualTo("CV_RESTORE");
        var requests=ArgumentCaptor.forClass(CreateCorrectionVersionRequest.class);
        verify(fixture.energy,org.mockito.Mockito.times(2)).createCorrection(eq(7L),eq(List.of("BUILDING_OWNER")),requests.capture());
        assertThat(requests.getAllValues().getLast().correctedValue()).isEqualByComparingTo("100");
    }

    @Test
    void correctionHistoryUsesTheVersionApprovedAtEachWatermark() {
        var oldApproval = LocalDateTime.of(2026, 1, 1, 0, 0);
        var newApproval = oldApproval.plusHours(1);
        var futureApproval = newApproval.plusHours(1);
        insertPendingCorrection(CORRECTION_ID, "CV_OLD", 1, new BigDecimal("110"));
        assertThat(repository.approveCorrection("CV_OLD", 0, 9L, oldApproval, "审核通过", "Q0:1"))
                .isEqualTo(1);
        repository.disableCorrectionVersion("CV_OLD");
        insertPendingCorrection(CORRECTION_ID, "CV_NEW", 2, new BigDecimal("120"));
        assertThat(repository.approveCorrection("CV_NEW", 0, 9L, newApproval, "审核通过", "Q0:1"))
                .isEqualTo(1);
        insertCorrectionIdentity("C2", RawTelemetryLedger.identity("P1", EVENT_TIME + 1));
        insertPendingCorrection("C2", "CV_FUTURE", 1, new BigDecimal("130"));
        assertThat(repository.approveCorrection("CV_FUTURE", 0, 9L, futureApproval, "审核通过", "Q0:1"))
                .isEqualTo(1);

        assertThat(repository.findCorrectionVersion("CV_OLD").status()).isEqualTo("DISABLED");
        assertThat(repository.correctionsAsOf("B1", "P1", oldApproval))
                .extracting(EnergyAggregationGovernanceRepository.CorrectionVersionRow::correctionVersionId)
                .containsExactly("CV_OLD");
        assertThat(repository.correctionsAsOf("B1", "P1", newApproval))
                .extracting(EnergyAggregationGovernanceRepository.CorrectionVersionRow::correctionVersionId)
                .containsExactly("CV_NEW");
    }

    @Test
    void energyCorrectionRejectsDoubleOutsideDecimal3012InsteadOfRounding() {
        openConflict(120.1234567890123d);
        var view = fixture.conflicts.detail(7, List.of("BUILDING_OWNER"), "P1", EVENT_TIME);

        assertThatThrownBy(() -> fixture.transaction(() -> fixture.conflicts.resolve(7,
                List.of("BUILDING_OWNER"), "P1", EVENT_TIME,
                new TelemetryConflictService.ResolveRequest(view.variants().getFirst().evidenceId(),
                        view.revision(), "设备报文核实", "工单1"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能静默舍入");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_resolution", Integer.class))
                .isZero();
        verifyNoInteractions(fixture.energy);
    }

    @Test
    void buildingOwnerCannotActAsEnergyMaintainer() {
        var duties = mock(BackendDutyService.class);
        var authorization = new EnergyAggregationAuthorization(duties, fixture.scope);

        assertThatThrownBy(() -> authorization.requireMaintainer(7L, List.of("BUILDING_OWNER")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(403));
        verifyNoInteractions(duties);
    }

    private TelemetryConflictService.ConflictView submitPendingCorrection(String correctionVersionId) {
        fixture.conflict();
        var view = fixture.conflicts.detail(7, List.of("BUILDING_OWNER"), "P1", EVENT_TIME);
        when(fixture.energy.createCorrection(anyLong(), anyCollection(), any(CreateCorrectionVersionRequest.class)))
                .thenReturn(pendingCorrection(correctionVersionId));
        return fixture.transaction(() -> fixture.conflicts.resolve(7, List.of("BUILDING_OWNER"), "P1",
                EVENT_TIME, new TelemetryConflictService.ResolveRequest(view.variants().getFirst().evidenceId(),
                view.revision(), "设备报文核实", "工单1")));
    }

    private void openConflict(double candidate) {
        var original = fixture.event(100);
        fixture.ledger.reserve(original, () -> null);
        fixture.ledger.storeFrozen(original, () -> null);
        fixture.ledger.reserve(fixture.event(candidate), () -> null);
    }

    private void insertPendingCorrection(String correctionVersionId) {
        insertPendingCorrection(CORRECTION_ID, correctionVersionId, 1, new BigDecimal("120"));
    }

    private void insertPendingCorrection(String correctionId, String correctionVersionId,
                                         int versionNo, BigDecimal correctedValue) {
        fixture.jdbc.update("""
                INSERT INTO biz_energy_activity_correction_version
                (correction_version_id,correction_id,version_no,original_value,corrected_value,
                 correction_reason,status,source_type,evidence_reference,quality_gate_passed,
                 quality_policy_version,config_revision,created_by,created_at)
                VALUES (?,?,?,100,?,'设备报文核实','PENDING_REVIEW','DEVICE','工单1',
                        0,'PENDING',0,7,CURRENT_TIMESTAMP)
                """, correctionVersionId, correctionId, versionNo, correctedValue);
    }

    private void insertCorrectionIdentity(String correctionId, String originalFactIdentity) {
        fixture.jdbc.update("""
                INSERT INTO biz_energy_activity_correction
                (correction_id,building_id,meter_point_id,original_fact_identity,created_by,created_at)
                VALUES (?,'B1','P1',?,7,CURRENT_TIMESTAMP)
                """, correctionId, originalFactIdentity);
    }

    private CorrectionVersionView pendingCorrection(String correctionVersionId) {
        return new CorrectionVersionView(CORRECTION_ID, correctionVersionId, 1, "B1", "P1", identity(),
                new BigDecimal("100"), new BigDecimal("120"), "设备报文核实", "PENDING_REVIEW",
                "DEVICE", "工单1", false, "PENDING", 0, 7,
                LocalDateTime.of(2026, 1, 1, 0, 0), null, null, null);
    }

    private static String identity() {
        return RawTelemetryLedger.identity("P1", EVENT_TIME);
    }
}
