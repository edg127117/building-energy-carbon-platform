package com.platform.carbon;

import com.platform.config.TdengineProperties;
import com.platform.energy.classification.ClassificationContracts;
import com.platform.energy.classification.ClassificationContracts.Configuration;
import com.platform.energy.classification.ClassificationContracts.ImportRequest;
import com.platform.energy.classification.ClassificationContracts.Reading;
import com.platform.energy.classification.ClassificationContracts.SaveRequest;
import com.platform.energy.classification.ClassificationContracts.TransitionRequest;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.period.EnergyPeriodGovernanceService;
import com.platform.energy.period.EnergyPeriodLifecycleService;
import com.platform.energy.period.ManualPeriodInputService;
import com.platform.energy.period.api.EnergyPeriodContracts.ApproveLockRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.ApproveRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.CreatePeriodPolicyRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.LockRequestView;
import com.platform.energy.period.api.EnergyPeriodContracts.ProjectionView;
import com.platform.energy.period.api.EnergyPeriodContracts.SnapshotView;
import com.platform.energy.summary.EnergyBoundarySummaryService;
import com.platform.energy.summary.api.EnergySummaryContracts.ApproveBoundaryPolicyRequest;
import com.platform.energy.summary.api.EnergySummaryContracts.CreateBoundaryPolicyRequest;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryRequest;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.platform.carbon.ConfigurationDualStoreFixture.BOUNDARY;
import static com.platform.carbon.ConfigurationDualStoreFixture.BUILDING;
import static com.platform.carbon.ConfigurationDualStoreFixture.END;
import static com.platform.carbon.ConfigurationDualStoreFixture.ITEM;
import static com.platform.carbon.ConfigurationDualStoreFixture.GAS;
import static com.platform.carbon.ConfigurationDualStoreFixture.HEAT;
import static com.platform.carbon.ConfigurationDualStoreFixture.MAIN;
import static com.platform.carbon.ConfigurationDualStoreFixture.MONTH;
import static com.platform.carbon.ConfigurationDualStoreFixture.MYSQL_ROLLBACK;
import static com.platform.carbon.ConfigurationDualStoreFixture.OPERATOR;
import static com.platform.carbon.ConfigurationDualStoreFixture.OTHER_BUILDING;
import static com.platform.carbon.ConfigurationDualStoreFixture.PENDING;
import static com.platform.carbon.ConfigurationDualStoreFixture.RELATION_VERSION;
import static com.platform.carbon.ConfigurationDualStoreFixture.REVIEWER;
import static com.platform.carbon.ConfigurationDualStoreFixture.START;
import static com.platform.carbon.ConfigurationDualStoreFixture.SUB_300;
import static com.platform.carbon.ConfigurationDualStoreFixture.SUB_600;
import static com.platform.carbon.ConfigurationDualStoreFixture.TD_FAILURE;
import static com.platform.carbon.ConfigurationDualStoreFixture.UNIT;
import static com.platform.carbon.ConfigurationDualStoreFixture.ZONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 显式 opt-in 的双库软件验收：全部档案和专业规则均为合成夹具事实，
 * 不连接现场设备、不代表真实建筑核算或现场验收。
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/mysql",
        "spring.flyway.target=83",
        "spring.sql.init.mode=never",
        "database.charset-fix.enabled=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "tdengine.initialization-enabled=false",
        "mqtt.enabled=false",
        "spring.task.scheduling.enabled=false",
        "carbon-management.recalculation-enabled=false",
        "carbon-management.recalculation-merge-window=1ms",
        "carbon-management.recalculation-scan-delay=1h",
        "data-retention.cleanup-enabled=false",
        "daikin.device-client.enabled=false",
        "daikin.control.enabled=false",
        "daikin.retention.cleanup-enabled=false",
        "daikin.runtime.enabled=false",
        "device-onboarding.discovery.cleanup-enabled=false",
        "aggregation.recovery-enabled=false",
        "data-quality.enabled=false",
        "data-quality.reconciliation-enabled=false",
        "data-quality.recalculation-enabled=false",
        "formula.recovery-enabled=false",
        "weather.enabled=false",
        "weather.open-meteo-enabled=false",
        "weather.china-weather-enabled=false",
        "weather.energy-enabled=false",
        "security.dependency-credentials-required=false",
        "audit-governance.allow-self-approval=false",
        "relation-governance.deployment-environment=test",
        "relation-governance.self-approval-enabled=false"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CONFIGURATION_DUAL_IT_ISOLATED", matches = "true")
class ConfigurationDualStoreAcceptanceTest {
    private static final Collection<String> ROLES = Set.of("PLATFORM_ADMIN");

    @Autowired private JdbcTemplate mysql;
    @Autowired @Qualifier("taosJdbcTemplate") private JdbcTemplate taos;
    @Autowired private TdengineProperties tdengine;
    @Autowired private EnergyPeriodGovernanceService periodGovernance;
    @Autowired private EnergyPeriodLifecycleService periods;
    @Autowired private ClassificationService classifications;
    @Autowired private EnergyBoundarySummaryService summaries;
    @Autowired private CarbonProjectService projects;
    @Autowired private CarbonCalculationService carbon;
    @Autowired private CarbonClassificationReportService reports;
    @Autowired private CarbonRecalculationService recalculations;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        ConfigurationDualStoreFixture.requireEnvironment();
        registry.add("spring.datasource.url", ConfigurationDualStoreFixture::mysqlUrl);
        registry.add("spring.datasource.username", ConfigurationDualStoreFixture::mysqlUser);
        registry.add("spring.datasource.password", ConfigurationDualStoreFixture::mysqlPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.url", ConfigurationDualStoreFixture::mysqlUrl);
        registry.add("spring.flyway.user", ConfigurationDualStoreFixture::mysqlUser);
        registry.add("spring.flyway.password", ConfigurationDualStoreFixture::mysqlPassword);
        registry.add("tdengine.url", ConfigurationDualStoreFixture::tdUrl);
        registry.add("tdengine.username", ConfigurationDualStoreFixture::tdUser);
        registry.add("tdengine.password", ConfigurationDualStoreFixture::tdPassword);
        registry.add("tdengine.database", ConfigurationDualStoreFixture::tdDatabase);
    }

    @Test
    void accepts_real_formal_native_month_and_preserves_dual_store_failure_boundaries()
            throws InterruptedException {
        ConfigurationDualStoreFixture fixture = new ConfigurationDualStoreFixture(mysql, taos, tdengine);
        fixture.assertMigratedTo83();
        fixture.initializeTdEnergyStore();
        fixture.seedSyntheticBase();

        String policy = approveFormalPeriodPolicy();
        approveFormalBoundarySummaryPolicy();
        Map<String, String> confirmedFacts = importApproved("confirmed-month", List.of(
                fixture.reading(MAIN, "1000", "CONFIRMED", null),
                fixture.reading(SUB_600, "600", "CONFIRMED", null),
                fixture.reading(SUB_300, "300", "CONFIRMED", null),
                fixture.reading(GAS, "12", "CONFIRMED", null),
                fixture.reading(HEAT, "2125.678901234567", "CONFIRMED", null),
                fixture.reading(TD_FAILURE, "10", "CONFIRMED", null),
                fixture.reading(MYSQL_ROLLBACK, "20", "CONFIRMED", null)));
        String pendingFact = importApproved("pending-month", List.of(
                fixture.reading(PENDING, "1", "PENDING_EXPERT", null))).get(PENDING);

        assertPendingAndCrossBuildingSourcesAreRejected(policy, confirmedFacts.get(MAIN), pendingFact);
        assertTdFailureDoesNotPublishMysqlCurrent(fixture, policy, confirmedFacts.get(TD_FAILURE));
        assertMysqlRollbackLeavesNoVisibleCurrentAndRetryDoesNotDuplicateTd(
                fixture, policy, confirmedFacts.get(MYSQL_ROLLBACK));

        SnapshotView main = closeFormalMonth(policy, MAIN, confirmedFacts.get(MAIN), 0L);
        SnapshotView sub600 = closeFormalMonth(policy, SUB_600, confirmedFacts.get(SUB_600), 0L);
        SnapshotView sub300 = closeFormalMonth(policy, SUB_300, confirmedFacts.get(SUB_300), 0L);
        SnapshotView gas = closeFormalMonth(policy, GAS, confirmedFacts.get(GAS), 0L);
        SnapshotView heat = closeFormalMonth(policy, HEAT, confirmedFacts.get(HEAT), 0L);
        assertThat(List.of(main, sub600, sub300, gas, heat)).allSatisfy(snapshot -> {
            assertThat(snapshot.resultNature()).isEqualTo("FORMAL");
            assertThat(snapshot.status()).isEqualTo("LOCKED_NATIVE_COMPLETE");
            assertThat(snapshot.tce()).isNull();
            assertThat(snapshot.nativeQuantityStatus()).isEqualTo("COMPLETE");
            assertThat(snapshot.tceStatus()).isEqualTo("NOT_CALCULATED");
        });
        assertThat(new BigDecimal(fixture.tdEvidence(main.resultKey()).nativeDecimal()))
                .isEqualByComparingTo("1000");
        assertThat(fixture.tdEvidence(main.resultKey()).tceDecimal()).isNull();
        assertThat(new BigDecimal(fixture.tdEvidence(heat.resultKey()).nativeDecimal()))
                .isEqualByComparingTo("2125.678901234567");

        String classification = approveClassification();
        assertManualFactsAreSealedOnce(classification);
        assertFormalBoundarySummaryIsNativeComplete();
        activateProjectConfiguration(classification);
        // 项目配置变更发生在首份报告之前，仅归档为无影响触发，避免污染后续快照替代断言。
        assertThat(recalculations.analyzeOne()).isTrue();
        assertThat(recalculations.analyzeOne()).isFalse();

        CarbonModels.CalculationDetail baseline = carbon.run(OPERATOR, ROLES,
                new com.platform.carbon.api.CarbonContracts.RunCalculationRequest(
                        BUILDING, "MONTH", START, END, ZONE.getId(), "FORMAL", "dual-baseline"));
        assertThat(baseline.batch().status()).isEqualTo("COMPLETED_COMPLETE");
        assertThat(baseline.batch().publicationStatus()).isEqualTo("DIRECT");
        assertThat(activityTotal(baseline)).isEqualByComparingTo("400");
        assertThat(baseline.items()).hasSize(4);
        assertThat(baseline.failures()).isEmpty();
        var frozenReport = reports.report(OPERATOR, ROLES, baseline.batch().batchId());
        assertThat(frozenReport.classificationComplete()).isTrue();
        assertThat(frozenReport.totalKgCo2e()).isEqualByComparingTo("212811.8901234567");
        assertThat(frozenReport.excludedSources()).hasSize(1);
        assertThat(frozenReport.nodes()).hasSize(3);
        for (var detail : baseline.items()) {
            var evidence = carbon.evidence(OPERATOR, ROLES, baseline.batch().batchId(),
                    detail.calculationItemId());
            assertThat(evidence.path("upstream").path("compositionSnapshots").isArray()).isTrue();
            assertThat(evidence.path("upstream").path("projectVersion").path("versionId").asText())
                    .isNotBlank();
        }
        assertThat(baseline.items().stream().filter(value -> "DUAL_GAS".equals(value.energyItemCode())))
                .singleElement().satisfies(value -> {
                    assertThat(value.scopeType()).isEqualTo(CarbonModels.ScopeType.SCOPE_1);
                    assertThat(value.emissionKgCo2e()).isEqualByComparingTo("44");
                    assertThat(value.gwpVersionId()).isEqualTo("CGWP_CO2_V1");
                });
        assertThat(baseline.items().stream().filter(value -> "DUAL_HEAT".equals(value.energyItemCode())))
                .singleElement().satisfies(value -> {
                    assertThat(value.scopeType()).isEqualTo(CarbonModels.ScopeType.SCOPE_2);
                    assertThat(value.emissionKgCo2e()).isEqualByComparingTo("212567.8901234567");
                    assertThat(value.gwpVersionId()).isNull();
                });
        CarbonModels.CalculationDetail replay = carbon.run(OPERATOR, ROLES,
                new com.platform.carbon.api.CarbonContracts.RunCalculationRequest(
                        BUILDING, "MONTH", START, END, ZONE.getId(), "FORMAL", "dual-baseline"));
        assertThat(replay.batch().batchId()).isEqualTo(baseline.batch().batchId());
        assertThat(mysql.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_calculation_batch
                WHERE building_id=? AND idempotency_key='dual-baseline'
                """, Integer.class, BUILDING)).isEqualTo(1);

        String correctedMainFact = importApproved("main-revision", List.of(
                fixture.reading(MAIN, "1100", "CONFIRMED", confirmedFacts.get(MAIN)))).get(MAIN);
        SnapshotView correctedMain = closeFormalMonth(policy, MAIN, correctedMainFact, 1L);
        assertThat(correctedMain.snapshotVersion()).isEqualTo(2);
        assertThat(correctedMain.supersedesSnapshotId()).isEqualTo(main.snapshotId());
        assertThat(correctedMain.status()).isEqualTo("LOCKED_NATIVE_COMPLETE");
        String currentKey = mysql.queryForObject("""
                SELECT result_key FROM biz_energy_period_result_current WHERE projection_id=?
                """, String.class, correctedMain.projectionId());
        assertThat(new BigDecimal(fixture.tdEvidence(currentKey).nativeDecimal()))
                .as("修订后的当前投影在真实 TDengine 中必须更新")
                .isEqualByComparingTo("1100");
        assertThat(new BigDecimal(fixture.tdEvidence(main.resultKey()).nativeDecimal()))
                .as("旧快照的时序副本不可被修订覆盖").isEqualByComparingTo("1000");
        assertThat(mysql.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_dependency_change
                WHERE change_type='ACTIVITY_SNAPSHOT' AND old_version_id=? AND new_version_id=?
                """, Integer.class, main.snapshotId(), correctedMain.snapshotId())).isEqualTo(1);

        assertThat(recalculations.analyzeOne()).isTrue();
        Thread.sleep(20L);
        recalculations.executeOne();
        String recalculationBatch = mysql.queryForObject("""
                SELECT recalculation_batch_id FROM biz_carbon_recalculation_batch
                ORDER BY created_at DESC,recalculation_batch_id DESC LIMIT 1
                """, String.class);
        CarbonModels.RecalculationBatch pendingApproval = recalculations.detail(
                OPERATOR, ROLES, recalculationBatch);
        assertThat(pendingApproval.status()).isEqualTo("PENDING_APPROVAL");
        CarbonModels.RecalculationItem item = recalculations.items(OPERATOR, ROLES, recalculationBatch)
                .stream().collect(Collectors.collectingAndThen(Collectors.toList(), values -> {
                    assertThat(values).hasSize(1);
                    return values.getFirst();
                }));
        CarbonModels.CalculationDetail candidate = carbon.detail(OPERATOR, ROLES,
                item.candidateCalculationBatchId());
        assertThat(candidate.batch().supersedesBatchId()).isEqualTo(baseline.batch().batchId());
        assertThat(activityTotal(candidate)).isEqualByComparingTo("500");
        assertThat(activityTotal(carbon.detail(OPERATOR, ROLES, baseline.batch().batchId())))
                .as("旧冻结报告在独立审批前后均保持原始活动量")
                .isEqualByComparingTo("400");
        CarbonModels.RecalculationBatch published = recalculations.approve(REVIEWER, ROLES,
                recalculationBatch,
                new com.platform.carbon.api.CarbonContracts.ApproveRecalculationRequest(
                        "合成夹具独立审核碳替代报告"));
        assertThat(published.status()).isEqualTo("COMPLETED");
        assertThat(carbon.detail(OPERATOR, ROLES, candidate.batch().batchId()).batch().publicationStatus())
                .isEqualTo("PUBLISHED");
        assertThat(carbon.detail(OPERATOR, ROLES, baseline.batch().batchId()).batch().publicationStatus())
                .isEqualTo("SUPERSEDED");
        assertThat(activityTotal(carbon.detail(OPERATOR, ROLES, baseline.batch().batchId())))
                .isEqualByComparingTo("400");
        assertThat(reports.report(OPERATOR, ROLES, baseline.batch().batchId())).isEqualTo(frozenReport);
        assertThat(reports.report(OPERATOR, ROLES, candidate.batch().batchId()).totalKgCo2e())
                .isEqualByComparingTo("212861.8901234567");
    }

    private String approveFormalPeriodPolicy() {
        var pending = periodGovernance.createPeriodPolicy(OPERATOR, ROLES,
                new CreatePeriodPolicyRequest(BUILDING, ZONE.getId(), 0, "REVIEW_REQUIRED",
                        "FORMAL", "合成夹具独立审核正式周期口径",
                        LocalDateTime.of(2024, 1, 1, 0, 0), null));
        return periodGovernance.approvePeriodPolicy(REVIEWER, ROLES, pending.versionId(),
                new ApproveRequest(pending.configRevision(), "合成夹具独立审核周期口径")).versionId();
    }

    private void approveFormalBoundarySummaryPolicy() {
        approveFormalBoundarySummaryPolicy(BOUNDARY, ITEM);
        approveFormalBoundarySummaryPolicy(GAS + "_BOUNDARY", "DUAL_GAS");
        approveFormalBoundarySummaryPolicy(HEAT + "_BOUNDARY", "DUAL_HEAT");
    }

    private void approveFormalBoundarySummaryPolicy(String boundary, String energy) {
        var pending = summaries.createPolicy(OPERATOR, ROLES,
                new CreateBoundaryPolicyRequest(BUILDING, boundary, energy,
                        "MAIN_WITH_SUBMETER_BREAKDOWN", "FORMAL",
                        "合成夹具独立审核正式边界汇总口径",
                        LocalDateTime.of(2024, 1, 1, 0, 0), null));
        assertThat(summaries.approvePolicy(REVIEWER, ROLES, pending.versionId(),
                new ApproveBoundaryPolicyRequest(pending.configRevision(),
                        "合成夹具独立审核边界汇总口径")).sourceType()).isEqualTo("FORMAL");
    }

    private Map<String, String> importApproved(String idempotencyKey, List<Reading> readings) {
        var imported = classifications.importReadings(OPERATOR, ROLES, BUILDING,
                new ImportRequest(idempotencyKey, readings, "合成夹具人工读数导入"));
        assertThat(imported.status()).isEqualTo("PENDING_REVIEW");
        assertThat(classifications.approveReadings(REVIEWER, ROLES, BUILDING, imported.batchId(),
                new TransitionRequest(0L, "合成夹具独立审核人工来源", null)).status()).isEqualTo("APPROVED");
        return classifications.readings(OPERATOR, ROLES, BUILDING, START, END).stream()
                .filter(value -> imported.batchId().equals(value.batchId()))
                .collect(Collectors.toMap(value -> value.reading().pointId(),
                        ClassificationContracts.ReadingView::factId));
    }

    private void assertPendingAndCrossBuildingSourcesAreRejected(
            String policy, String mainFact, String pendingFact) {
        assertThatThrownBy(() -> periods.refreshManual(OPERATOR, ROLES, BUILDING,
                request(PENDING, policy, pendingFact, 0L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> periods.refreshManual(OPERATOR, ROLES, OTHER_BUILDING,
                request(MAIN, policy, mainFact, 0L)))
                .isInstanceOf(BusinessException.class);
    }

    private void assertTdFailureDoesNotPublishMysqlCurrent(
            ConfigurationDualStoreFixture fixture, String policy, String factId) {
        fixture.dropTdEnergyStore();
        try {
            assertThatThrownBy(() -> periods.refreshManual(OPERATOR, ROLES, BUILDING,
                    request(TD_FAILURE, policy, factId, 0L))).isInstanceOf(RuntimeException.class);
        } finally {
            fixture.recreateTdEnergyStore();
        }
        assertThat(mysql.queryForObject("""
                SELECT COUNT(*) FROM biz_energy_period_result_current
                WHERE building_id=? AND point_id=? AND period_type='MONTH'
                """, Integer.class, BUILDING, TD_FAILURE)).isZero();
    }

    private void assertMysqlRollbackLeavesNoVisibleCurrentAndRetryDoesNotDuplicateTd(
            ConfigurationDualStoreFixture fixture, String policy, String factId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            periods.refreshManual(OPERATOR, ROLES, BUILDING,
                    request(MYSQL_ROLLBACK, policy, factId, 0L));
            throw new IllegalStateException("合成夹具强制外层 MySQL 回滚");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(mysql.queryForObject("""
                SELECT COUNT(*) FROM biz_energy_period_result_current
                WHERE building_id=? AND point_id=? AND period_type='MONTH'
                """, Integer.class, BUILDING, MYSQL_ROLLBACK)).isZero();
        assertThat(fixture.tdRowsForPoint(MYSQL_ROLLBACK)).isEqualTo(1L);

        periods.refreshManual(OPERATOR, ROLES, BUILDING,
                request(MYSQL_ROLLBACK, policy, factId, 0L));
        assertThat(mysql.queryForObject("""
                SELECT COUNT(*) FROM biz_energy_period_result_current
                WHERE building_id=? AND point_id=? AND period_type='MONTH'
                """, Integer.class, BUILDING, MYSQL_ROLLBACK)).isEqualTo(1);
        assertThat(fixture.tdRowsForPoint(MYSQL_ROLLBACK))
                .as("相同结果键和周期起点重试不得追加 TD 数值副本")
                .isEqualTo(1L);
    }

    private SnapshotView closeFormalMonth(String policy, String point, String factId,
                                           long expectedProjectionRevision) {
        ProjectionView current = periods.refreshManual(OPERATOR, ROLES, BUILDING,
                request(point, policy, factId, expectedProjectionRevision));
        LockRequestView lock = periods.submitLock(OPERATOR, ROLES,
                new com.platform.energy.period.api.EnergyPeriodContracts.SubmitLockRequest(
                        current.projectionId(), current.revision(), "合成夹具正式月封账",
                        "合成夹具独立封账审核证据"));
        return periods.approveLock(REVIEWER, ROLES, lock.requestId(),
                new ApproveLockRequest("合成夹具独立审核封账"));
    }

    private ManualPeriodInputService.Request request(String point, String policy, String fact,
                                                      long expectedRevision) {
        return new ManualPeriodInputService.Request(point, MONTH, policy, RELATION_VERSION,
                List.of(fact), expectedRevision, "合成夹具形成正式人工月量候选");
    }

    private String approveClassification() {
        var created = classifications.create(OPERATOR, ROLES, BUILDING,
                new ClassificationContracts.CreateRequest("合成夹具分类配置", null));
        Configuration configuration = new Configuration(RELATION_VERSION,
                List.of(new ClassificationContracts.Scheme("USE", "合成用途", "USAGE", "ADDITIVE")),
                List.of(new ClassificationContracts.Node("USE", "ALL", "合成全部", null)),
                List.of(MAIN, SUB_600, SUB_300, GAS, HEAT).stream().map(point ->
                        new ClassificationContracts.Binding("USE", "ALL", "POINT", point,
                                null, "METERING", "CONFIRMED", "合成夹具已确认计量绑定"))
                        .toList(),
                List.of(new ClassificationContracts.BoundaryRule(BOUNDARY,
                        "MAIN_WITH_SUBMETER_BREAKDOWN", "CONFIRMED",
                        "合成夹具已确认计量口径", ITEM, UNIT, true),
                        new ClassificationContracts.BoundaryRule(GAS + "_BOUNDARY",
                                "MAIN_WITH_SUBMETER_BREAKDOWN", "CONFIRMED", "合成夹具燃气边界",
                                "DUAL_GAS", "M3", true),
                        new ClassificationContracts.BoundaryRule(HEAT + "_BOUNDARY",
                                "MAIN_WITH_SUBMETER_BREAKDOWN", "CONFIRMED", "合成夹具热力边界",
                                "DUAL_HEAT", "GJ", true)));
        var saved = classifications.save(OPERATOR, ROLES, BUILDING, created.versionId(),
                new SaveRequest(created.revision(), configuration, "保存合成分类配置"));
        var submitted = classifications.submit(OPERATOR, ROLES, BUILDING, saved.versionId(),
                new TransitionRequest(saved.revision(), "提交合成分类配置", null));
        return classifications.approve(REVIEWER, ROLES, BUILDING, submitted.versionId(),
                new TransitionRequest(submitted.revision(), "独立审核合成分类配置", START)).versionId();
    }

    private void assertManualFactsAreSealedOnce(String classification) {
        var result = classifications.query(OPERATOR, ROLES, BUILDING,
                new ClassificationContracts.QueryRequest(START, END, "MONTH", ITEM, UNIT,
                        "USE", classification, List.of()));
        assertThat(result.total()).as("封账人工事实不得再从人工批次重复计入分类").isEqualByComparingTo("1000");
        assertThat(result.resultNature()).isEqualTo("SOURCE_LABELLED_STATISTICS");
    }

    private void assertFormalBoundarySummaryIsNativeComplete() {
        var summary = summaries.query(OPERATOR, ROLES,
                new SummaryQueryRequest(BUILDING, "MONTH", START, END,
                        List.of("ENERGY_ITEM", "PERIOD")));
        assertThat(summary.sourceSnapshotCount()).isEqualTo(5);
        assertThat(summary.groups()).hasSize(3);
        assertThat(summary.groups().stream().filter(group -> group.grossInboundQuantities().containsKey(UNIT)))
                .singleElement().satisfies(group -> {
            assertThat(group.grossInboundQuantities().get(UNIT)).isEqualByComparingTo("1000");
            assertThat(group.resultNature()).isEqualTo("FORMAL");
            assertThat(group.resultCompleteness()).isEqualTo("COMPLETE_NATIVE_ONLY");
            assertThat(group.tceByPerspective()).isEmpty();
        });
    }

    private void activateProjectConfiguration(String classification) {
        var created = projects.create(OPERATOR, ROLES, BUILDING,
                new CarbonProjectContracts.Create("创建合成项目能碳配置", null));
        CarbonProjectContracts.Configuration configuration = new CarbonProjectContracts.Configuration(
                classification,
                List.of(new CarbonProjectContracts.Energy(ITEM,
                        "PURCHASED_ELECTRICITY", "ENABLED"),
                        new CarbonProjectContracts.Energy("DUAL_GAS", "STATIONARY_COMBUSTION", "ENABLED"),
                        new CarbonProjectContracts.Energy("DUAL_HEAT", "PURCHASED_HEAT", "ENABLED")),
                List.of(new CarbonProjectContracts.Exclusion(ITEM, List.of(SUB_600),
                        "合成夹具排除600分表", "合成夹具已确认排除规则，非现场边界结论")));
        var saved = projects.save(OPERATOR, ROLES, BUILDING, created.versionId(),
                new CarbonProjectContracts.Save(created.revision(), configuration,
                        "保存合成项目能碳配置"));
        var submitted = projects.transition(OPERATOR, ROLES, BUILDING, saved.versionId(), "submit",
                new CarbonProjectContracts.Transition(saved.revision(), "提交合成项目配置", null));
        var approved = projects.transition(REVIEWER, ROLES, BUILDING, submitted.versionId(), "approve",
                new CarbonProjectContracts.Transition(submitted.revision(), "独立审核合成项目配置", null));
        assertThat(projects.activate(OPERATOR, ROLES, BUILDING, approved.versionId(),
                new CarbonProjectContracts.Transition(approved.revision(), "激活合成项目配置", START)).status())
                .isEqualTo("ACTIVE");
    }

    private static BigDecimal activityTotal(CarbonModels.CalculationDetail detail) {
        return detail.items().stream().filter(value -> ITEM.equals(value.energyItemCode()))
                .map(CarbonModels.StoredCalculationItem::activityQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
