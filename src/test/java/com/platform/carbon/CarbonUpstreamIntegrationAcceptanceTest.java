package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.carbon.CarbonModels.CalculationDetail;
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
import com.platform.energy.period.EnergyPeriodGovernanceService;
import com.platform.energy.period.EnergyPeriodLifecycleService;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.period.EnergyPeriodResultRepository;
import com.platform.energy.period.EnergyPeriodValueStore;
import com.platform.energy.period.api.EnergyPeriodContracts.ApproveLockRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.ConversionSelectionRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.CreatePeriodPolicyRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.RefreshProjectionRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.RecalculationBatchView;
import com.platform.energy.period.api.EnergyPeriodContracts.SnapshotView;
import com.platform.energy.period.api.EnergyPeriodContracts.SubmitLockRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.SubmitRecalculationRequest;
import com.platform.energy.summary.EnergyBoundarySummaryService;
import com.platform.energy.summary.api.EnergySummaryContracts.ApproveBoundaryPolicyRequest;
import com.platform.energy.summary.api.EnergySummaryContracts.CreateBoundaryPolicyRequest;
import com.platform.framework.exception.BusinessException;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.MeteringAssignmentView;
import com.platform.relation.api.RelationContracts.MeteringAssignmentsView;
import com.platform.relation.api.RelationContracts.MeteringBoundariesView;
import com.platform.relation.api.RelationContracts.MeteringBoundaryView;
import com.platform.relation.api.RelationContracts.QueryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 专用隔离 MySQL 验证实际能源月封账、年度碳核算与上游修正的闭环。
 * 下层累计读数和时序写入端口只作为明确的 DEVELOPMENT_SIMULATION 输入替身，
 * 不代表 TDengine、真实设备或现场验收。
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/mysql",
        "spring.sql.init.mode=never",
        "database.charset-fix.enabled=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "carbon-management.recalculation-enabled=false",
        "carbon-management.recalculation-merge-window=1ms",
        "carbon-management.recalculation-lease=3s",
        "carbon-management.recalculation-scan-delay=1h"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CARBON_UPSTREAM_IT_URL", matches = ".+")
class CarbonUpstreamIntegrationAcceptanceTest {
    private static final long OPERATOR = 9101L;
    private static final long REVIEWER = 9102L;
    private static final Set<String> ROLES = Set.of("PLATFORM_ADMIN");
    private static final String BUILDING = "CUPSTREAM001";
    private static final String POINT = "CUP_POINT_01";
    private static final String RELATION_VERSION = "CUP_RELATION_V1";
    private static final String BOUNDARY = "CUP_BOUNDARY_V1";
    private static final int YEAR = 2025;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant YEAR_START = LocalDate.of(YEAR, 1, 1)
            .atStartOfDay(ZONE).toInstant();
    private static final Instant YEAR_END = LocalDate.of(YEAR + 1, 1, 1)
            .atStartOfDay(ZONE).toInstant();
    private static final Instant CALCULATION_AS_OF = Instant.parse("2026-02-05T00:00:00Z");
    private static final LocalDate MARCH = LocalDate.of(YEAR, 3, 1);
    private static final LocalDate APRIL = LocalDate.of(YEAR, 4, 1);

    @Autowired private EnergyCatalogService catalogService;
    @Autowired private EnergyConversionService conversionService;
    @Autowired private EnergyPeriodGovernanceService periodGovernanceService;
    @Autowired private EnergyPeriodLifecycleService periodService;
    @Autowired private EnergyBoundarySummaryService summaryService;
    @Autowired private EnergyPeriodResultRepository periodResults;
    @Autowired private CarbonCalculationService carbonCalculationService;
    @Autowired private CarbonRecalculationService carbonRecalculationService;
    @Autowired private CarbonActivitySnapshotChangeRecorder snapshotChangeRecorder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private EnergyAggregationInputAssembler inputAssembler;
    @MockBean private EnergyPeriodValueStore valueStore;
    @MockBean private RelationGovernanceService relationService;

    private final Map<LocalDate, BigDecimal> monthlyReadings = new ConcurrentHashMap<>();
    private String summaryPolicyVersionId;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        assertIsolatedMySql();
        registry.add("spring.datasource.url", () -> System.getenv("CARBON_UPSTREAM_IT_URL"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("CARBON_UPSTREAM_IT_USER", "root"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CARBON_UPSTREAM_IT_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void prepareIsolatedDevelopmentSimulation() {
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success=1 ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("42");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM building WHERE building_id=?", Integer.class, BUILDING))
                .as("本用例要求新建一次性 MySQL，不能复用旧验收数据")
                .isZero();

        insertBuilding();
        grantRequiredDuties();
        configureRelationInput();
        approveElectricityRules();
        approveEnergyPolicies();
        insertDevelopmentReferenceFactor();
        monthlyReadings.clear();
        for (int month = 1; month <= 12; month++) {
            monthlyReadings.put(LocalDate.of(YEAR, month, 1), new BigDecimal("100"));
        }
        when(inputAssembler.load(eq(OPERATOR), eq(ROLES), any(AggregationQuery.class)))
                .thenAnswer(invocation -> aggregationInput(invocation.getArgument(2)));
    }

    @Test
    void closesMonthlyEnergyThenRecalculatesAnnualCarbonWithCapturedUpstreamEvidence()
            throws InterruptedException {
        Map<LocalDate, SnapshotView> lockedMonths = closeTwelveActualMonthlySnapshots();

        CalculationDetail baseline = carbonCalculationService.run(OPERATOR, ROLES,
                new com.platform.carbon.api.CarbonContracts.RunCalculationRequest(
                        BUILDING, "YEAR", YEAR_START, YEAR_END, ZONE.getId(),
                        "DEVELOPMENT_SIMULATION", "cupstream-baseline"));

        assertThat(baseline.batch().status()).isEqualTo("COMPLETED_COMPLETE");
        assertThat(baseline.batch().publicationStatus()).isEqualTo("DIRECT");
        assertThat(baseline.batch().resultNature().name()).isEqualTo("DEVELOPMENT_SIMULATION");
        assertThat(baseline.items()).hasSize(12);
        assertThat(activityTotal(baseline)).isEqualByComparingTo("1200");
        assertThat(totalEmission(baseline)).isEqualByComparingTo("0.6");
        assertCapturedUpstreamEvidence(baseline, lockedMonths);

        SnapshotView initialMarch = lockedMonths.get(MARCH);
        monthlyReadings.put(MARCH, new BigDecimal("120"));
        RecalculationBatchView changedEnergyBatch = approvedRecalculation(initialMarch,
                "cupstream-march-correction", "研发模拟累计量修正");
        RecalculationBatchView completedEnergyBatch = periodService.executeRecalculation(
                OPERATOR, ROLES, changedEnergyBatch.batchId());
        assertThat(completedEnergyBatch.status()).isEqualTo("COMPLETED");
        assertThat(completedEnergyBatch.changedItems()).isEqualTo(1);
        assertThat(completedEnergyBatch.failedItems()).isZero();

        SnapshotView correctedMarch = periodService.snapshot(
                OPERATOR, ROLES, initialMarch.projectionId());
        assertThat(correctedMarch.nativeQuantity()).isEqualByComparingTo("120");
        assertThat(correctedMarch.snapshotVersion()).isEqualTo(2);
        assertThat(correctedMarch.supersedesSnapshotId()).isEqualTo(initialMarch.snapshotId());
        assertThat(dependencyCount(initialMarch.snapshotId(), correctedMarch.snapshotId()))
                .as("实际生命周期在整批快照可见后持久化碳影响变化")
                .isEqualTo(1);

        PeriodSnapshot oldMarch = periodResults.findSnapshot(initialMarch.snapshotId());
        PeriodSnapshot newMarch = periodResults.findSnapshot(correctedMarch.snapshotId());
        assertThat(oldMarch).isNotNull();
        assertThat(newMarch).isNotNull();
        snapshotChangeRecorder.published(oldMarch, newMarch);
        assertThat(dependencyCount(initialMarch.snapshotId(), correctedMarch.snapshotId()))
                .as("相同上游有效替代重复投递不得重复入队")
                .isEqualTo(1);

        assertThat(carbonRecalculationService.analyzeOne()).isTrue();
        Thread.sleep(30);
        carbonRecalculationService.executeOne();

        String recalculationBatchId = jdbc.queryForObject("""
                SELECT b.recalculation_batch_id
                FROM biz_carbon_recalculation_batch b
                JOIN biz_carbon_recalculation_item i
                  ON i.recalculation_batch_id=b.recalculation_batch_id
                WHERE i.building_id=?
                ORDER BY b.created_at DESC, b.recalculation_batch_id DESC
                LIMIT 1
                """, String.class, BUILDING);
        var recalculation = carbonRecalculationService.detail(
                OPERATOR, ROLES, recalculationBatchId);
        var recalculationItems = carbonRecalculationService.items(
                OPERATOR, ROLES, recalculationBatchId);
        assertThat(recalculation.status()).isEqualTo("COMPLETED");
        assertThat(recalculationItems).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("SUCCEEDED");
            assertThat(item.oldCalculationBatchId()).isEqualTo(baseline.batch().batchId());
            assertThat(item.candidateCalculationBatchId()).isNotBlank();
        });

        String candidateBatchId = recalculationItems.getFirst().candidateCalculationBatchId();
        CalculationDetail candidate = carbonCalculationService.detail(
                OPERATOR, ROLES, candidateBatchId);
        assertThat(candidate.batch().status()).isEqualTo("COMPLETED_COMPLETE");
        assertThat(candidate.batch().publicationStatus()).isEqualTo("DIRECT");
        assertThat(candidate.batch().supersedesBatchId()).isEqualTo(baseline.batch().batchId());
        assertThat(candidate.items()).hasSize(12);
        assertThat(activityTotal(candidate)).isEqualByComparingTo("1220");
        assertThat(totalEmission(candidate)).isEqualByComparingTo("0.61");
        assertThatThrownBy(() -> carbonCalculationService.evidence(OPERATOR, ROLES,
                candidate.batch().batchId(), baseline.items().getFirst().calculationItemId()))
                .as("明细证据必须同时归属请求批次，不能跨批次读取")
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(404));

        Map<LocalDate, SnapshotView> currentMonths = new LinkedHashMap<>(lockedMonths);
        currentMonths.put(MARCH, correctedMarch);
        assertCapturedUpstreamEvidence(candidate, currentMonths);

        CalculationDetail retainedBaseline = carbonCalculationService.detail(
                OPERATOR, ROLES, baseline.batch().batchId());
        assertThat(retainedBaseline.batch().status()).isEqualTo("COMPLETED_COMPLETE");
        assertThat(retainedBaseline.batch().publicationStatus()).isEqualTo("DIRECT");
        assertThat(activityTotal(retainedBaseline)).isEqualByComparingTo("1200");
        assertThat(upstreamSource(retainedBaseline, initialMarch.snapshotId())).isNotNull();
        assertThat(upstreamSource(candidate, correctedMarch.snapshotId())).isNotNull();

        assertRollbackDoesNotPersistCarbonChange(currentMonths.get(APRIL));
    }

    private Map<LocalDate, SnapshotView> closeTwelveActualMonthlySnapshots() {
        Map<LocalDate, SnapshotView> snapshots = new LinkedHashMap<>();
        for (int month = 1; month <= 12; month++) {
            LocalDate date = LocalDate.of(YEAR, month, 1);
            var current = periodService.refresh(OPERATOR, ROLES, new RefreshProjectionRequest(
                    BUILDING, POINT, "MONTH", date, conversionSelection(), CALCULATION_AS_OF));
            var lock = periodService.submitLock(OPERATOR, ROLES, new SubmitLockRequest(
                    current.projectionId(), current.revision(), "研发模拟月度封账",
                    "碳上游闭环隔离验收"));
            SnapshotView snapshot = periodService.approveLock(REVIEWER, ROLES, lock.requestId(),
                    new ApproveLockRequest("研发模拟月度封账审核通过"));
            assertThat(snapshot.status()).isEqualTo("LOCKED_COMPLETE");
            assertThat(snapshot.resultNature()).isEqualTo("DEVELOPMENT_SIMULATION");
            snapshots.put(date, snapshot);
        }
        return snapshots;
    }

    private RecalculationBatchView approvedRecalculation(
            SnapshotView source, String idempotencyKey, String reason) {
        var submitted = periodService.submitRecalculation(OPERATOR, ROLES,
                new SubmitRecalculationRequest(BUILDING, idempotencyKey, "SAME_RULES",
                        reason, List.of(source.snapshotId())));
        return periodService.approveRecalculation(REVIEWER, ROLES, submitted.batchId(),
                new com.platform.energy.period.api.EnergyPeriodContracts.ApproveRecalculationRequest(
                        "研发模拟能源修正审核通过"));
    }

    private void assertRollbackDoesNotPersistCarbonChange(SnapshotView aprilSnapshot) {
        monthlyReadings.put(APRIL, new BigDecimal("130"));
        RecalculationBatchView approved = approvedRecalculation(aprilSnapshot,
                "cupstream-rollback-correction", "验证外层事务回滚");
        long dependencyBefore = dependencyCountForBuilding();
        int snapshotsBefore = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_energy_period_result_snapshot
                WHERE source_batch_id=?
                """, Integer.class, approved.batchId());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            RecalculationBatchView completed = periodService.executeRecalculation(
                    OPERATOR, ROLES, approved.batchId());
            assertThat(completed.status()).isEqualTo("COMPLETED");
            throw new IllegalStateException("验证能源-碳同一事务回滚");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("验证能源-碳同一事务回滚");

        SnapshotView visible = periodService.snapshot(
                OPERATOR, ROLES, aprilSnapshot.projectionId());
        assertThat(visible.snapshotId()).isEqualTo(aprilSnapshot.snapshotId());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_energy_period_result_snapshot
                WHERE source_batch_id=?
                """, Integer.class, approved.batchId())).isEqualTo(snapshotsBefore);
        assertThat(dependencyCountForBuilding())
                .as("被回滚的实际能源重算不能留下碳 dependency change")
                .isEqualTo(dependencyBefore);
        assertThat(periodService.batch(OPERATOR, ROLES, approved.batchId()).status())
                .isEqualTo("VALIDATING");
    }

    private void assertCapturedUpstreamEvidence(
            CalculationDetail calculation, Map<LocalDate, SnapshotView> expectedSnapshots) {
        JsonNode evidence = evidence(calculation, calculation.items().getFirst().calculationItemId());
        assertThat(evidence.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(evidence.path("traceStatus").asText()).isEqualTo("CAPTURED");
        for (String field : List.of("factor", "matching", "gwp", "factorSources", "formula",
                "roundingPolicy", "areaDenominator", "populationDenominator", "calculation")) {
            assertThat(evidence.has(field)).as("完整追溯包含 %s", field).isTrue();
        }
        JsonNode upstream = evidence.path("upstream");
        assertThat(upstream.path("contractVersion").asText())
                .isEqualTo("ENERGY_SUMMARY_CARBON_V1");
        assertThat(texts(upstream.path("relationVersionIds"))).containsExactly(RELATION_VERSION);
        assertThat(texts(upstream.path("summaryPolicyVersionIds")))
                .containsExactly(summaryPolicyVersionId);
        assertThat(upstream.path("summary").isObject()).isTrue();

        for (SnapshotView expected : expectedSnapshots.values()) {
            JsonNode source = upstreamSource(calculation, expected.snapshotId());
            assertThat(source.path("snapshotId").asText()).isEqualTo(expected.snapshotId());
            assertThat(source.path("pointId").asText()).isEqualTo(POINT);
            assertThat(source.has("activityWatermark")).isTrue();
            assertThat(source.has("exceptionPolicyVersions")).isTrue();
            assertThat(source.path("evidence").isObject()).isTrue();
            assertThat(new BigDecimal(source.path("coverageRatio").asText()))
                    .as("高精度小数以 canonical 十进制字符串还原")
                    .isEqualByComparingTo(BigDecimal.ONE);
        }
    }

    private JsonNode evidence(CalculationDetail calculation, String itemId) {
        return carbonCalculationService.evidence(
                OPERATOR, ROLES, calculation.batch().batchId(), itemId);
    }

    private JsonNode upstreamSource(CalculationDetail calculation, String snapshotId) {
        for (var item : calculation.items()) {
            JsonNode sources = evidence(calculation, item.calculationItemId())
                    .path("upstream").path("sources");
            for (JsonNode source : sources) {
                if (snapshotId.equals(source.path("snapshotId").asText())) {
                    return source;
                }
            }
        }
        throw new AssertionError("未找到实际能源快照的碳追溯证据: " + snapshotId);
    }

    private static List<String> texts(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static BigDecimal activityTotal(CalculationDetail calculation) {
        return calculation.items().stream().map(item -> item.activityQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal totalEmission(CalculationDetail calculation) {
        return calculation.summaries().stream()
                .filter(summary -> "TOTAL_EMISSION".equals(summary.metricCode()))
                .filter(summary -> "TOTAL".equals(summary.dimensionCode()))
                .map(summary -> summary.finalValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少年度总排放汇总"));
    }

    private long dependencyCount(String oldSnapshotId, String newSnapshotId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_dependency_change
                WHERE source_object_type='ENERGY_PERIOD_SNAPSHOT'
                  AND old_version_id=? AND new_version_id=?
                """, Long.class, oldSnapshotId, newSnapshotId);
    }

    private long dependencyCountForBuilding() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_dependency_change
                WHERE source_object_type='ENERGY_PERIOD_SNAPSHOT' AND building_id=?
                """, Long.class, BUILDING);
    }

    private void configureRelationInput() {
        QueryMetadata metadata = new QueryMetadata(BUILDING, RELATION_VERSION, 1,
                LocalDateTime.of(YEAR, 1, 1, 0, 0), 7, 0, false, 0, 0, 0, 0);
        MeteringAssignmentView assignment = new MeteringAssignmentView(
                "CUP_ASSIGNMENT_V1", "ASSIGNED", null, null, "development simulation",
                BOUNDARY, "CUP-B1", "研发模拟计量边界", "ELECTRICITY",
                "CONFIRMED", "ACTIVE", "CUP_POINT_NODE", POINT, "CUP-POINT",
                "研发模拟总表", "MAIN", "INBOUND", "CONFIRMED",
                "CUP_SPACE_NODE", "SPACE", "CUP_SPACE", "CUP-SPACE", "研发模拟空间");
        MeteringAssignmentsView assignments = new MeteringAssignmentsView(
                metadata, 1, 500, 1, List.of(assignment));
        MeteringBoundariesView boundaries = new MeteringBoundariesView(metadata, 1, 500, 1,
                List.of(new MeteringBoundaryView(BOUNDARY, "CUP-B1", "研发模拟计量边界",
                        "ELECTRICITY", "CONFIRMED", "ACTIVE")));
        when(relationService.effectiveBoundaries(
                anyLong(), any(), eq(BUILDING), anyInt(), anyInt())).thenReturn(boundaries);
        when(relationService.historicalMeteringAssignments(
                anyLong(), any(), eq(BUILDING), eq(RELATION_VERSION), anyInt(), anyInt()))
                .thenReturn(assignments);
    }

    private AggregationInput aggregationInput(AggregationQuery query) {
        LocalDate month = query.startInclusive().atZone(ZONE).toLocalDate();
        BigDecimal endReading = monthlyReadings.get(month);
        if (endReading == null) {
            throw new AssertionError("隔离输入缺少月度累计读数: " + month);
        }
        MeasurementContext measurement = new MeasurementContext(
                "ELECTRICITY", "CUP_BINDING_V1", "KWH", "EUV_KWH_1",
                ValueSemantics.CUMULATIVE, null, DataNature.SIMULATED, "CONFIRMED",
                query.startInclusive().minusSeconds(60), null, "研发模拟测点绑定证据");
        MeteringAssignmentEvidence assignment = new MeteringAssignmentEvidence(
                RELATION_VERSION, 7, "CUP_ASSIGNMENT_V1", BOUNDARY,
                "CUP_SPACE", "ASSIGNED", "CONFIRMED");
        return new AggregationInput(query, measurement, assignment, query.calculationAsOf(),
                List.of(fact("CUP_START_" + month, BigDecimal.ZERO, query.startInclusive()),
                        fact("CUP_END_" + month, endReading, query.endExclusive())),
                List.of(), List.of(), null);
    }

    private static ActivityFact fact(String id, BigDecimal value, Instant eventTime) {
        return new ActivityFact(id, value, eventTime, eventTime.plusMillis(100),
                "Q0", "CUP_QUALITY_V1", false, null, null, null, null);
    }

    private static ConversionSelectionRequest conversionSelection() {
        return new ConversionSelectionRequest("ENERGY_EQUIVALENT", "CALORIFIC_EQUIVALENT",
                "PURCHASED_ELECTRICITY", "GLOBAL");
    }

    private void approveEnergyPolicies() {
        var period = periodGovernanceService.createPeriodPolicy(OPERATOR, ROLES,
                new CreatePeriodPolicyRequest(BUILDING, ZONE.getId(), 72, "REVIEW_REQUIRED",
                        "SIMULATION", "碳上游闭环隔离周期口径",
                        LocalDateTime.of(YEAR, 1, 1, 0, 0), null));
        periodGovernanceService.approvePeriodPolicy(REVIEWER, ROLES, period.versionId(),
                new com.platform.energy.period.api.EnergyPeriodContracts.ApproveRequest(
                        period.configRevision(), "研发模拟周期口径审核"));

        var summary = summaryService.createPolicy(OPERATOR, ROLES,
                new CreateBoundaryPolicyRequest(BUILDING, BOUNDARY, "ELECTRICITY",
                        "MAIN_METER_TOTAL", "SIMULATION", "碳上游闭环隔离计量边界口径",
                        LocalDateTime.of(YEAR, 1, 1, 0, 0), null));
        var approved = summaryService.approvePolicy(REVIEWER, ROLES, summary.versionId(),
                new ApproveBoundaryPolicyRequest(summary.configRevision(), "研发模拟边界口径审核"));
        summaryPolicyVersionId = approved.versionId();
    }

    private void approveElectricityRules() {
        ApproveRequest catalogApproval = new ApproveRequest(0, "研发模拟专业流程审核");
        catalogService.approveItem(REVIEWER, ROLES, "EIV_ELECTRICITY_1", catalogApproval);
        List.of("EUV_KWH_1", "EUV_MJ_1", "EUV_GJ_1", "EUV_KGCE_1", "EUV_TCE_1")
                .forEach(version -> catalogService.approveUnit(
                        REVIEWER, ROLES, version, catalogApproval));
        var conversionApproval =
                new com.platform.energy.conversion.api.EnergyConversionContracts.ApproveRequest(
                        0, "研发模拟专业流程审核");
        conversionService.approveStandardCoal(
                REVIEWER, ROLES, "SCLV_STANDARD_1", conversionApproval);
        conversionService.approveFormula(
                REVIEWER, ROLES, "ECFV_ELECTRICITY_CAL_1", conversionApproval);
        conversionService.approveParameter(
                REVIEWER, ROLES, "ECPV_ELECTRICITY_CAL_1", conversionApproval);
    }

    private void insertDevelopmentReferenceFactor() {
        String source = id();
        String sourceVersion = id();
        String factor = id();
        String factorVersion = id();
        jdbc.update("""
                INSERT INTO biz_carbon_factor_source(source_id,source_code,created_by)
                VALUES (?,?,?)
                """, source, source, OPERATOR);
        jdbc.update("""
                INSERT INTO biz_carbon_factor_source_version
                (source_version_id,source_id,version_no,source_name,publisher,document_reference,
                 applicability_note,evidence_reference,usage_nature,created_by)
                VALUES (?,?,1,'SYNTHETIC ONLY','carbon upstream acceptance',
                        'not a business factor','isolated software testing','synthetic fixture',
                        'DEVELOPMENT_REFERENCE',?)
                """, sourceVersion, source, OPERATOR);
        jdbc.update("""
                INSERT INTO biz_carbon_factor
                (factor_id,factor_code,scope_type,energy_item_code,factor_category,result_basis,
                 gas_code,gas_coverage,created_by)
                VALUES (?,?,'SCOPE_2','ELECTRICITY','PURCHASED_ELECTRICITY_LOCATION',
                        'CO2E_DIRECT','CO2e','TEST_CO2E',?)
                """, factor, factor, OPERATOR);
        jdbc.update("""
                INSERT INTO biz_carbon_factor_version
                (factor_version_id,factor_id,version_no,source_version_id,applicability_level,
                 input_unit_code,usage_nature,status,effective_from,formula_version_id,
                 rounding_policy_version_id,created_by,reviewed_by,activated_by)
                VALUES (?,?,1,?,'NATIONAL','KWH','DEVELOPMENT_REFERENCE','ACTIVE','2000-01-01',
                        'CFV_ELECTRICITY_CO2E_V1','CRP_DECIMAL128_V1',?,?,?)
                """, factorVersion, factor, sourceVersion, OPERATOR, REVIEWER, REVIEWER);
        jdbc.update("""
                INSERT INTO biz_carbon_factor_component
                (component_id,factor_version_id,component_type,component_value,component_unit,
                 source_version_id,evidence_reference)
                VALUES (?,?,'DIRECT_EMISSION_FACTOR','0.5','KG_CO2E/KWH',?,
                        'synthetic fixture')
                """, id(), factorVersion, sourceVersion);
    }

    private void insertBuilding() {
        jdbc.update("""
                INSERT INTO building
                (building_id,building_name,building_type,total_gfa,climate_zone,region_code)
                VALUES (?,'碳上游隔离验收建筑','办公',1,'夏热冬冷','330100')
                """, BUILDING);
    }

    private void grantRequiredDuties() {
        List<String> operatorDuties = List.of(
                "ENERGY_RULE_MAINTAIN", "ENERGY_CALCULATION_RUN", "ENERGY_LOCK_SUBMIT",
                "ENERGY_RECALC_SUBMIT", "CARBON_CALCULATION_RUN");
        List<String> reviewerDuties = List.of(
                "ENERGY_CATALOG_REVIEW", "ENERGY_RULE_REVIEW", "ENERGY_LOCK_APPROVE",
                "ENERGY_RECALC_APPROVE");
        operatorDuties.forEach(duty -> assign(OPERATOR, duty));
        reviewerDuties.forEach(duty -> assign(REVIEWER, duty));
    }

    private void assign(long userId, String duty) {
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by)
                VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,?)
                """, id(), userId, duty, OPERATOR);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void assertIsolatedMySql() {
        assertThat(System.getenv("CARBON_UPSTREAM_IT_ISOLATED"))
                .as("集成测试必须由调用方显式确认使用一次性隔离 MySQL")
                .isEqualTo("true");
        assertThat(System.getenv("CARBON_UPSTREAM_IT_URL"))
                .as("隔离集成测试只允许本机 iot_platform")
                .matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/iot_platform(?:\\?.*)?");
    }
}
