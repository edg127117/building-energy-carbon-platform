package com.platform.energy.aggregation;

import com.platform.energy.activity.EnergyActivityDataContracts.AggregationActivitySnapshot;
import com.platform.energy.activity.EnergyActivityDataContracts.RawActivityDataView;
import com.platform.energy.activity.EnergyActivityDataService;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EnergyAggregationGovernanceServiceIntegrationTest {
    private static final long USER = 101L;
    private static final Set<String> ROLES = Set.of("ENERGY_MANAGER");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired private EnergyAggregationGovernanceService service;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private EnergyActivityDataService activityDataService;

    @BeforeEach
    void prepareAuthority() {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)", USER, "BLD001");
        assign("ENERGY_RULE_MAINTAIN", "DUTY_AGGREGATION_MAINTAIN");
        assign("ENERGY_RULE_REVIEW", "DUTY_AGGREGATION_REVIEW");
        assign("ENERGY_CALCULATION_RUN", "DUTY_AGGREGATION_RUN");
    }

    @Test
    void appendsMeterEventVersionsAndApprovesWithoutOverwritingPriorEvidence() {
        var first = service.createEvent(USER, ROLES, reset(null, "10", "0"));
        var second = service.createEvent(USER, ROLES, reset(first.eventId(), "11", "1"));

        var approved = service.approveEvent(USER, ROLES, first.eventVersionId(), approve());

        assertThat(first.versionNo()).isEqualTo(1);
        assertThat(second.versionNo()).isEqualTo(2);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(service.listEvents(USER, ROLES, "BLD001", "POINT001"))
                .extracting(MeterEventVersionView::status)
                .containsExactly("APPROVED", "PENDING_REVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE source_module='ENERGY_AGGREGATION'
                  AND action_type IN ('CREATE_METER_EVENT_VERSION','APPROVE_METER_EVENT_VERSION')
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void approvesCorrectionOnlyAfterOriginalFactPassesAggregationQualityGate() {
        long eventTime = 1_800L;
        when(activityDataService.aggregationSnapshot(eq(USER), eq(ROLES), eq("BLD001"),
                eq("POINT001"), eq(eventTime), eq(eventTime + 1), anyLong()))
                .thenReturn(snapshot(eventTime));
        var pending = service.createCorrection(USER, ROLES, new CreateCorrectionVersionRequest(
                null, "BLD001", "POINT001", "POINT001@1800", new BigDecimal("12.3"),
                new BigDecimal("13.0"), "研发模拟错误修正", "SIMULATION", "模拟修正证据"));

        var approved = service.approveCorrection(USER, ROLES, pending.correctionVersionId(), approve());

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.qualityGatePassed()).isTrue();
        assertThat(approved.qualityPolicyVersion()).isEqualTo("SYSTEM_DEFAULT_Q0_ONLY:null:7");

        var revised = service.createCorrection(USER, ROLES, new CreateCorrectionVersionRequest(
                pending.correctionId(), "BLD001", "POINT001", "POINT001@1800",
                new BigDecimal("12.3"), new BigDecimal("14.0"), "追加修正版本",
                "SIMULATION", "另一份模拟修正证据"));
        service.approveCorrection(USER, ROLES, revised.correctionVersionId(), approve());

        assertThat(service.listCorrections(USER, ROLES, "BLD001", "POINT001"))
                .extracting(CorrectionVersionView::status)
                .containsExactly("DISABLED", "APPROVED");
    }

    @Test
    void versionsAndClosesIntegrationPoliciesAtApprovalBoundary() {
        var first = service.createPolicy(USER, ROLES, policy(AT));
        service.approvePolicy(USER, ROLES, first.policyVersionId(), approve());
        var second = service.createPolicy(USER, ROLES, policy(AT.plusMonths(1)));

        service.approvePolicy(USER, ROLES, second.policyVersionId(), approve());

        var versions = service.listPolicies(USER, ROLES, "BLD001", "POINT001");
        assertThat(versions).hasSize(2);
        assertThat(versions).filteredOn(value -> value.versionNo() == 1).singleElement()
                .satisfies(value -> assertThat(value.effectiveTo()).isEqualTo(AT.plusMonths(1)));
        assertThat(versions).filteredOn(value -> value.versionNo() == 2).singleElement()
                .satisfies(value -> assertThat(value.status()).isEqualTo("APPROVED"));
    }

    private CreateMeterEventVersionRequest reset(String eventId, String before, String after) {
        return new CreateMeterEventVersionRequest(eventId, "BLD001", "POINT001", "RESET",
                AT.toInstant(java.time.ZoneOffset.UTC), new BigDecimal(before),
                new BigDecimal(after), null, null, null, null, null,
                "SIMULATION", "研发模拟复位证据", true);
    }

    private CreateIntegrationPolicyVersionRequest policy(LocalDateTime effectiveFrom) {
        return new CreateIntegrationPolicyVersionRequest("BLD001", "POINT001", "TRAPEZOIDAL",
                900L, new BigDecimal("0.95"), "REQUIRE_BOUNDARY_READINGS", "SIMULATION",
                "研发模拟积分策略", effectiveFrom, null);
    }

    private AggregationActivitySnapshot snapshot(long eventTime) {
        var fact = new RawActivityDataView("POINT001", "POINT001_CODE", "kWh", "ELECTRICITY",
                "GRID_PURCHASED", "CUMULATIVE", "CONFIRMED", 3, "SIMULATION_V1",
                "POINT001_SOURCE", "SIM_DEVICE", 12.3, eventTime, eventTime + 1,
                "Q0", false, "SYSTEM_DEFAULT_Q0_ONLY", null, 7);
        return new AggregationActivitySnapshot("BLD001", "POINT001", "kWh", "CUMULATIVE",
                "CONFIRMED", 3, eventTime, eventTime + 1, eventTime + 100,
                eventTime + 100, "POINT_ID_EVENT_TIME", "EXTERNAL_APPEND_ONLY", List.of(fact));
    }

    private ApproveEvidenceRequest approve() {
        return new ApproveEvidenceRequest(0, "研发流程模拟审核");
    }

    private void assign(String duty, String id) {
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by)
                VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,1)
                """, id, USER, duty);
    }
}
