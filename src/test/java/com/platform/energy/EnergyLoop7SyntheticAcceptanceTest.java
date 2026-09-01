package com.platform.energy;

import com.platform.energy.aggregation.EnergyAggregationInputAssembler;
import com.platform.energy.aggregation.EnergyAggregationModels.ActivityFact;
import com.platform.energy.aggregation.EnergyAggregationModels.AggregationInput;
import com.platform.energy.aggregation.EnergyAggregationModels.AggregationQuery;
import com.platform.energy.aggregation.EnergyAggregationModels.DataNature;
import com.platform.energy.aggregation.EnergyAggregationModels.MeasurementContext;
import com.platform.energy.aggregation.EnergyAggregationModels.MeteringAssignmentEvidence;
import com.platform.energy.aggregation.EnergyAggregationModels.ValueSemantics;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.catalog.api.EnergyCatalogContracts.ApproveRequest;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.period.EnergyPeriodLifecycleService;
import com.platform.energy.period.EnergyPeriodValueStore;
import com.platform.energy.period.api.EnergyPeriodContracts.ApproveLockRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.ApproveRecalculationRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.ConversionSelectionRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.RefreshProjectionRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.SubmitLockRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.SubmitRecalculationRequest;
import com.platform.energy.summary.EnergyBoundarySummaryService;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryRequest;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.MeteringAssignmentView;
import com.platform.relation.api.RelationContracts.MeteringAssignmentsView;
import com.platform.relation.api.RelationContracts.QueryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 使用明确标记的模拟事实验收第七闭环跨模块契约，不代表现场或正式结果。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EnergyLoop7SyntheticAcceptanceTest {
    private static final long OPERATOR = 101L;
    private static final long REVIEWER = 202L;
    private static final Set<String> ROLES = Set.of("ENERGY_MANAGER");
    private static final String BUILDING = "BLD001";
    private static final String POINT = "POINT004";
    private static final String RELATION_VERSION = "REL_ACCEPTANCE";
    private static final String BOUNDARY = "BOUNDARY_ACCEPTANCE";
    private static final LocalDate PERIOD_DATE = LocalDate.of(2026, 1, 1);
    private static final Instant CALCULATION_AS_OF = Instant.parse("2026-02-05T00:00:00Z");

    @Autowired private EnergyCatalogService catalogService;
    @Autowired private EnergyConversionService conversionService;
    @Autowired private EnergyPeriodLifecycleService periodService;
    @Autowired private EnergyBoundarySummaryService summaryService;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private EnergyAggregationInputAssembler inputAssembler;
    @MockBean private EnergyPeriodValueStore valueStore;
    @MockBean private RelationGovernanceService relationService;

    private final AtomicReference<BigDecimal> cumulativeReading =
            new AtomicReference<>(new BigDecimal("1000"));

    @BeforeEach
    void prepareVersionedSimulationBaseline() {
        grantBuilding(OPERATOR);
        grantBuilding(REVIEWER);
        assign(OPERATOR, "ENERGY_CALCULATION_RUN", "ACC_DUTY_CALC");
        assign(OPERATOR, "ENERGY_LOCK_SUBMIT", "ACC_DUTY_LOCK_SUBMIT");
        assign(OPERATOR, "ENERGY_RECALC_SUBMIT", "ACC_DUTY_RECALC_SUBMIT");
        assign(REVIEWER, "ENERGY_CATALOG_REVIEW", "ACC_DUTY_CATALOG_REVIEW");
        assign(REVIEWER, "ENERGY_RULE_REVIEW", "ACC_DUTY_RULE_REVIEW");
        assign(REVIEWER, "ENERGY_LOCK_APPROVE", "ACC_DUTY_LOCK_REVIEW");
        assign(REVIEWER, "ENERGY_RECALC_APPROVE", "ACC_DUTY_RECALC_REVIEW");
        approveElectricityRules();
        insertPeriodPolicy();
        insertSummaryPolicy();
        when(inputAssembler.load(eq(OPERATOR), eq(ROLES), any(AggregationQuery.class)))
                .thenAnswer(invocation -> aggregationInput(invocation.getArgument(2)));
        when(relationService.historicalMeteringAssignments(
                OPERATOR, ROLES, BUILDING, RELATION_VERSION, 1, 500))
                .thenReturn(assignments());
    }

    @Test
    void closesAndRecalculatesOneSyntheticMonthBeforeBoundarySummary() {
        var current = periodService.refresh(OPERATOR, ROLES, new RefreshProjectionRequest(
                BUILDING, POINT, "MONTH", PERIOD_DATE, new ConversionSelectionRequest(
                "ENERGY_EQUIVALENT", "CALORIFIC_EQUIVALENT",
                "PURCHASED_ELECTRICITY", "GLOBAL"), CALCULATION_AS_OF));

        assertThat(current.nativeQuantity()).isEqualByComparingTo("1000");
        assertThat(current.tce()).isPositive();
        assertThat(current.resultNature()).isEqualTo("DEVELOPMENT_SIMULATION");
        assertThat(current.evidenceHash()).hasSize(64);

        var lock = periodService.submitLock(OPERATOR, ROLES, new SubmitLockRequest(
                current.projectionId(), current.revision(), "研发模拟月度封账",
                "第七闭环合成验收证据"));
        var firstSnapshot = periodService.approveLock(REVIEWER, ROLES, lock.requestId(),
                new ApproveLockRequest("合成封账验收通过"));

        assertThat(firstSnapshot.nativeQuantity()).isEqualByComparingTo("1000");
        assertThat(firstSnapshot.status()).isEqualTo("LOCKED_COMPLETE");
        assertSummary("1000", firstSnapshot.tce());

        cumulativeReading.set(new BigDecimal("1100"));
        var submitted = periodService.submitRecalculation(OPERATOR, ROLES,
                new SubmitRecalculationRequest(BUILDING, "loop7-acceptance-recalc",
                        "SAME_RULES", "模拟迟到累计量修正", List.of(firstSnapshot.snapshotId())));
        var approved = periodService.approveRecalculation(REVIEWER, ROLES,
                submitted.batchId(), new ApproveRecalculationRequest("合成重算验收通过"));
        var completed = periodService.executeRecalculation(OPERATOR, ROLES, approved.batchId());

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.changedItems()).isEqualTo(1);
        assertThat(completed.failedItems()).isZero();
        var latest = periodService.snapshot(OPERATOR, ROLES, current.projectionId());
        assertThat(latest.nativeQuantity()).isEqualByComparingTo("1100");
        assertThat(latest.snapshotVersion()).isEqualTo(2);
        assertThat(latest.supersedesSnapshotId()).isEqualTo(firstSnapshot.snapshotId());
        assertThat(latest.sourceBatchId()).isEqualTo(completed.batchId());
        assertSummary("1100", latest.tce());
    }

    private void assertSummary(String expectedQuantity, BigDecimal expectedTce) {
        var summary = summaryService.query(OPERATOR, ROLES, new SummaryQueryRequest(
                BUILDING, "MONTH", Instant.parse("2025-12-31T16:00:00Z"),
                Instant.parse("2026-01-31T16:00:00Z"),
                List.of("BUILDING", "METERING_BOUNDARY", "ENERGY_ITEM")));

        assertThat(summary.sourceSnapshotCount()).isEqualTo(1);
        assertThat(summary.groups()).singleElement().satisfies(group -> {
            assertThat(group.originalQuantities().get("KWH"))
                    .isEqualByComparingTo(expectedQuantity);
            assertThat(group.tceByPerspective().get("CALORIFIC_EQUIVALENT"))
                    .isEqualByComparingTo(expectedTce);
            assertThat(group.relationVersionIds()).containsExactly(RELATION_VERSION);
            assertThat(group.summaryPolicyVersionIds()).containsExactly("SUMMARY_POLICY_V1");
            assertThat(group.resultNature()).isEqualTo("DEVELOPMENT_SIMULATION");
        });
    }

    private AggregationInput aggregationInput(AggregationQuery query) {
        MeasurementContext measurement = new MeasurementContext(
                "ELECTRICITY", "BINDING_ACCEPTANCE", "KWH", "EUV_KWH_1",
                ValueSemantics.CUMULATIVE, null, DataNature.SIMULATED, "CONFIRMED",
                query.startInclusive().minusSeconds(3600), null, "合成测点绑定证据");
        MeteringAssignmentEvidence assignment = new MeteringAssignmentEvidence(
                RELATION_VERSION, 7, "ASSIGNMENT_ACCEPTANCE", BOUNDARY,
                "SPACE_ACCEPTANCE", "ASSIGNED", "CONFIRMED");
        List<ActivityFact> facts = List.of(
                fact("ACCEPTANCE_START", BigDecimal.ZERO, query.startInclusive()),
                fact("ACCEPTANCE_END", cumulativeReading.get(), query.endExclusive()));
        return new AggregationInput(query, measurement, assignment,
                query.calculationAsOf(), facts, List.of(), List.of(), null);
    }

    private static ActivityFact fact(String id, BigDecimal value, Instant eventTime) {
        return new ActivityFact(id, value, eventTime, eventTime.plusMillis(100),
                "Q0", "QUALITY_ACCEPTANCE_V1", false,
                null, null, null, null);
    }

    private void approveElectricityRules() {
        ApproveRequest catalogApproval = new ApproveRequest(0, "研发合成专业流程审核");
        catalogService.approveItem(REVIEWER, ROLES, "EIV_ELECTRICITY_1", catalogApproval);
        List.of("EUV_KWH_1", "EUV_MJ_1", "EUV_GJ_1", "EUV_KGCE_1", "EUV_TCE_1")
                .forEach(version -> catalogService.approveUnit(
                        REVIEWER, ROLES, version, catalogApproval));
        var conversionApproval =
                new com.platform.energy.conversion.api.EnergyConversionContracts.ApproveRequest(
                        0, "研发合成专业流程审核");
        conversionService.approveStandardCoal(
                REVIEWER, ROLES, "SCLV_STANDARD_1", conversionApproval);
        conversionService.approveFormula(
                REVIEWER, ROLES, "ECFV_ELECTRICITY_CAL_1", conversionApproval);
        conversionService.approveParameter(
                REVIEWER, ROLES, "ECPV_ELECTRICITY_CAL_1", conversionApproval);
    }

    private void insertPeriodPolicy() {
        jdbc.update("""
                INSERT INTO biz_energy_period_policy
                  (policy_id,building_id,created_by,created_at)
                VALUES ('PERIOD_POLICY_ACC',?,101,CURRENT_TIMESTAMP)
                """, BUILDING);
        jdbc.update("""
                INSERT INTO biz_energy_period_policy_version
                  (version_id,policy_id,version_no,timezone_id,closing_delay_hours,lock_mode,
                   status,source_type,evidence_reference,effective_from,config_revision,
                   created_by,created_at,approved_by,approved_at,review_comment)
                VALUES ('PERIOD_POLICY_V1','PERIOD_POLICY_ACC',1,'Asia/Shanghai',72,
                        'REVIEW_REQUIRED','APPROVED','SIMULATION','第七闭环合成周期口径',
                        '2020-01-01',1,101,CURRENT_TIMESTAMP,202,CURRENT_TIMESTAMP,
                        '研发合成审核')
                """);
    }

    private void insertSummaryPolicy() {
        jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy
                  (policy_id,building_id,metering_boundary_id,energy_item_code,created_by,created_at)
                VALUES ('SUMMARY_POLICY_ACC',?,?, 'ELECTRICITY',101,CURRENT_TIMESTAMP)
                """, BUILDING, BOUNDARY);
        jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy_version
                  (version_id,policy_id,version_no,aggregation_mode,status,source_type,
                   evidence_reference,effective_from,config_revision,created_by,created_at,
                   approved_by,approved_at,review_comment)
                VALUES ('SUMMARY_POLICY_V1','SUMMARY_POLICY_ACC',1,'MAIN_METER_TOTAL',
                        'APPROVED','SIMULATION','第七闭环合成总表口径','2020-01-01',1,
                        101,CURRENT_TIMESTAMP,202,CURRENT_TIMESTAMP,'研发合成审核')
                """);
    }

    private MeteringAssignmentsView assignments() {
        QueryMetadata metadata = new QueryMetadata(BUILDING, RELATION_VERSION, 1,
                LocalDateTime.of(2026, 1, 1, 0, 0), 7, 0, false, 0, 0, 0, 0);
        MeteringAssignmentView assignment = new MeteringAssignmentView(
                "ASSIGNMENT_ACCEPTANCE", "ASSIGNED", null, null, "simulation",
                BOUNDARY, "B-ACC", "合成验收边界", "ELECTRICITY",
                "CONFIRMED", "ACTIVE", "NODE_POINT_ACCEPTANCE", POINT, "P-ACC",
                "合成总表", "MAIN", "INBOUND", "CONFIRMED",
                "NODE_SPACE_ACCEPTANCE", "SPACE", "SPACE_ACCEPTANCE", "S-ACC",
                "合成空间");
        return new MeteringAssignmentsView(metadata, 1, 500, 1, List.of(assignment));
    }

    private void grantBuilding(long userId) {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)",
                userId, BUILDING);
    }

    private void assign(long userId, String duty, String assignmentId) {
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                  (assignment_id,user_id,duty_key,status,effective_at,created_by)
                VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,1)
                """, assignmentId, userId, duty);
    }
}
