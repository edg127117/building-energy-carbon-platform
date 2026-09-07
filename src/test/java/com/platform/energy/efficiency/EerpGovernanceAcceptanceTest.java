package com.platform.energy.efficiency;

import com.platform.audit.BackendDuty;
import com.platform.energy.activity.EnergyActivityDataReader;
import com.platform.energy.activity.EnergyActivityDataReader.Cursor;
import com.platform.energy.activity.EnergyActivityDataReader.RawEvent;
import com.platform.energy.activity.EnergyActivityDataReader.RawEventPage;
import com.platform.energy.aggregation.EnergyAggregationGovernanceService;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.ApproveEvidenceRequest;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.CreateMeterEventVersionRequest;
import com.platform.energy.period.EnergyPeriodValueStore;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.calculation.CalculationPointReadService;
import com.platform.iot.qualityusage.governance.QualityUsageGovernanceService;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.ChangeSetCreateRequest;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.PolicyDraftRequest;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.ActivationRequest;
import com.platform.relation.api.RelationContracts.MeterStructureRequest;
import com.platform.relation.api.RelationContracts.MeteringAssignmentRequest;
import com.platform.relation.api.RelationContracts.MeteringBoundaryRequest;
import com.platform.relation.api.RelationContracts.ReviewDecisionRequest;
import com.platform.relation.api.RelationContracts.RevisionReasonRequest;
import com.platform.relation.api.RelationContracts.SemanticRelationRequest;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.platform.energy.efficiency.EerpContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 隔离 H2 联合验证：治理事实使用实际 Spring 服务，原始事件与 TDengine 数值写入使用明确替身。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eerp_governance_acceptance;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EerpGovernanceAcceptanceTest {
    private static final AtomicLong USERS = new AtomicLong(910_000L);
    private static final Set<String> ENERGY_MANAGER = Set.of("ENERGY_MANAGER");
    private static final Set<String> PLATFORM_ADMIN = Set.of("PLATFORM_ADMIN");
    private static final Instant CONFIG_FROM = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant CONFIG_TO = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant PERIOD_START = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant PERIOD_END = PERIOD_START.plusSeconds(3_600);

    @Autowired private EerpService eerpService;
    @Autowired private RelationGovernanceService relationService;
    @Autowired private EnergyAggregationGovernanceService aggregationGovernance;
    @Autowired private QualityUsageGovernanceService qualityGovernance;
    @Autowired private CalculationPointReadService pointReader;
    @Autowired private BuildingScopeService buildingScopeService;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private EnergyActivityDataReader rawReader;
    @MockBean private EnergyPeriodValueStore valueStore;

    private String building;
    private String station;
    private String chiller;
    private String chilledPump;
    private String coolingPump;
    private String tower;
    private String coolingMeter;
    private String electricityMeter;
    private long operator;
    private long reviewer;

    @BeforeEach
    void seedIsolatedGovernanceFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        building = "EERP_G_" + suffix;
        station = "ST_" + suffix;
        chiller = "CH_" + suffix;
        chilledPump = "CP_" + suffix;
        coolingPump = "WP_" + suffix;
        tower = "TW_" + suffix;
        coolingMeter = "CM_" + suffix;
        electricityMeter = "EM_" + suffix;
        operator = USERS.incrementAndGet();
        reviewer = USERS.incrementAndGet();

        ensureIndicatorScenario();
        insertUser(operator, "eerp_operator_" + suffix);
        insertUser(reviewer, "eerp_reviewer_" + suffix);
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)", operator, building);
        grant(operator, BackendDuty.ENERGY_RULE_MAINTAIN);
        grant(operator, BackendDuty.ENERGY_CALCULATION_RUN);
        grant(operator, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grant(reviewer, BackendDuty.ENERGY_RULE_REVIEW);
        grant(reviewer, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        buildingScopeService.evict(operator);
        insertAssets(suffix);
    }

    @Test
    void approvedGovernanceChainUsesRealRelationsDutiesEventAndAuditEvidence() {
        ConfigView active = activateConfiguration();
        var reset = aggregationGovernance.createEvent(operator, ENERGY_MANAGER,
                new CreateMeterEventVersionRequest(null, building, coolingMeter, "RESET",
                        PERIOD_START.plusSeconds(1_800), new BigDecimal("110"), new BigDecimal("5"),
                        null, null, null, null, null, "SIMULATION", "SYNTHETIC_RESET_EVIDENCE", true));
        var approvedReset = aggregationGovernance.approveEvent(reviewer, PLATFORM_ADMIN, reset.eventVersionId(),
                new ApproveEvidenceRequest(0, "隔离审核通过"));
        assertThat(approvedReset.status()).isEqualTo("APPROVED");

        stubRawFacts(List.of(
                event(coolingMeter, 100, PERIOD_START), event(electricityMeter, 10, PERIOD_START),
                event(coolingMeter, 15, PERIOD_END), event(electricityMeter, 20, PERIOD_END)));

        TaskView task = eerpService.createPeriod(operator, ENERGY_MANAGER,
                new PeriodRequest(idempotency("governed-period"), active.versionId(), PERIOD_START, PERIOD_END,
                        PERIOD_END, null));

        assertThat(task.status()).isEqualTo("SUCCEEDED");
        PeriodResult result = (PeriodResult) task.result();
        assertThat(result.complete()).isTrue();
        assertThat(result.coolingKwh()).isEqualByComparingTo("20");
        assertThat(result.electricityKwh()).isEqualByComparingTo("10");
        assertThat(eerpService.trace(operator, ENERGY_MANAGER, task.taskId()).evidenceJson())
                .contains(approvedReset.eventVersionId());
        verify(valueStore, times(2)).write(any());

        assertAudit("ENERGY_AGGREGATION", "CREATE_METER_EVENT_VERSION", "APPROVE_METER_EVENT_VERSION");
        assertAudit("ENERGY_EERP", "CREATE_CONFIG", "SUBMIT_CONFIG", "APPROVE_CONFIG", "ACTIVATE_CONFIG",
                "CREATE_TASK", "CLAIM_TASK", "COMPLETE_TASK");
    }

    @Test
    void approvedQualityPolicyBlocksQ1AtItsActualEffectiveMinute() {
        var changeSet = qualityGovernance.createChangeSet(operator, ENERGY_MANAGER,
                new ChangeSetCreateRequest(building, "EERp 指标质量门禁", "隔离 H2 治理测试",
                        List.of(new PolicyDraftRequest(coolingMeter, "INDICATOR_CALCULATION", List.of("Q0"),
                                null, "仅允许 Q0"))));
        var submitted = qualityGovernance.submit(operator, ENERGY_MANAGER, changeSet.changeSetId(),
                idempotency("quality-submit"), "提交质量门禁");
        qualityGovernance.approve(reviewer, PLATFORM_ADMIN, submitted.requestId(),
                idempotency("quality-approve"), "批准质量门禁");
        var published = qualityGovernance.changeSetDetail(operator, ENERGY_MANAGER, changeSet.changeSetId());
        assertThat(published.status()).isEqualTo("PUBLISHED");
        var publishedPolicy = published.policyVersions().getFirst();
        assertThat(publishedPolicy.status()).isEqualTo("ACTIVE");
        assertThat(publishedPolicy.publishedConfigRevision()).isNotNull();
        long effectiveAt = publishedPolicy.effectiveFromMs();
        Instant effectiveMinute = Instant.ofEpochMilli(effectiveAt);
        stubRawFacts(List.of(event(coolingMeter, 42, effectiveMinute, 1)));

        var snapshot = pointReader.read(operator, ENERGY_MANAGER, building, Set.of(coolingMeter),
                effectiveMinute, effectiveMinute, effectiveMinute);

        assertThat(snapshot.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.qualityLevel()).isEqualTo("Q1");
            assertThat(fact.decision()).isEqualTo("BLOCK");
            assertThat(fact.policySource()).isEqualTo("PUBLISHED_POLICY");
            assertThat(fact.policyVersion()).isEqualTo(publishedPolicy.versionNo());
            assertThat(fact.policyReason()).isEqualTo("QUALITY_NOT_ALLOWED");
        });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_quality_usage_audit_log
                WHERE building_id=? AND action_type='APPROVE'
                """, Integer.class, building)).isEqualTo(1);
    }

    @Test
    void dutyRevocationAndBuildingScopeRevocationFailClosedWithoutReaderAccess() {
        ConfigView active = activateConfiguration();
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE user_id=? AND duty_key=?", operator,
                BackendDuty.ENERGY_CALCULATION_RUN.name());

        assertThatThrownBy(() -> eerpService.createPeriod(operator, ENERGY_MANAGER,
                new PeriodRequest(idempotency("revoked-duty"), active.versionId(), PERIOD_START, PERIOD_END,
                        PERIOD_END, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("BACKOFFICE_DUTY_REQUIRED"));

        grant(operator, BackendDuty.ENERGY_CALCULATION_RUN);
        jdbc.update("DELETE FROM sys_user_building WHERE user_id=? AND building_id=?", operator, building);
        buildingScopeService.evict(operator);

        assertThatThrownBy(() -> eerpService.config(operator, ENERGY_MANAGER, active.versionId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(403));
        verifyNoInteractions(rawReader, valueStore);
    }

    private ConfigView activateConfiguration() {
        var relation = relationService.initialize(operator, ENERGY_MANAGER, building, idempotency("relation-init"),
                "隔离 EERp 治理关系");
        String coolingNode = node("POINT", coolingMeter);
        String electricityNode = node("POINT", electricityMeter);
        String stationNode = node("SYSTEM", station);
        var boundary = relationService.createBoundary(operator, ENERGY_MANAGER, relation.versionId(), idempotency("boundary-create"),
                new MeteringBoundaryRequest("EERP_POWER", "EERp 冷站总电边界", "ELECTRICITY", "CONFIRMED",
                        "SYNTHETIC_BOUNDARY_EVIDENCE", relationRevision(relation.versionId())));
        relationService.createMeterStructure(operator, ENERGY_MANAGER, relation.versionId(), idempotency("meter-structure"),
                new MeterStructureRequest(boundary.boundaryId(), electricityNode, "MAIN", null, "INBOUND", "CONFIRMED",
                        null, null, "SYNTHETIC_METER_EVIDENCE", "冷站总电表", relationRevision(relation.versionId())));
        relationService.createMeteringAssignment(operator, ENERGY_MANAGER, relation.versionId(), idempotency("meter-assignment"),
                new MeteringAssignmentRequest(boundary.boundaryId(), electricityNode, stationNode, "ASSIGNED", null, null,
                        "SYNTHETIC_ASSIGNMENT_EVIDENCE", relationRevision(relation.versionId())));
        relationService.addSemanticRelation(operator, ENERGY_MANAGER, relation.versionId(), idempotency("cooling-measures"),
                new SemanticRelationRequest("MEASURES", coolingNode, stationNode, "MANUAL", "CONFIRMED",
                        "SYNTHETIC_WATER_CIRCUIT_EVIDENCE", "制备端累计冷量表", relationRevision(relation.versionId())));
        var relationReview = relationService.submit(operator, ENERGY_MANAGER, relation.versionId(), idempotency("relation-submit"),
                new RevisionReasonRequest(relationRevision(relation.versionId()), "提交隔离关系版本"));
        relationService.approve(reviewer, PLATFORM_ADMIN, relationReview.requestId(), idempotency("relation-approve"),
                new ReviewDecisionRequest("关系审核通过"));
        var effective = relationService.activate(reviewer, PLATFORM_ADMIN, relation.versionId(), idempotency("relation-activate"),
                new ActivationRequest(relationService.model(reviewer, PLATFORM_ADMIN, building).modelRevision(), "关系生效"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(relationService.audits(operator, ENERGY_MANAGER, building))
                .extracting(audit -> audit.actionType())
                .contains("SUBMIT", "APPROVE", "ACTIVATE");

        ConfigView draft = eerpService.createConfig(operator, ENERGY_MANAGER, configuration(boundary.boundaryId(), effective.versionId()));
        ConfigView submitted = eerpService.reviewConfig(operator, ENERGY_MANAGER, draft.versionId(), "SUBMIT",
                new Review(draft.revision(), "提交 EERp 配置"));
        ConfigView approved = eerpService.reviewConfig(reviewer, PLATFORM_ADMIN, submitted.versionId(), "APPROVE",
                new Review(submitted.revision(), "审核 EERp 配置"));
        ConfigView active = eerpService.reviewConfig(reviewer, PLATFORM_ADMIN, approved.versionId(), "ACTIVATE",
                new Review(approved.revision(), "生效 EERp 配置"));
        assertThat(active.status()).isEqualTo("ACTIVE");
        return active;
    }

    private Configuration configuration(String boundaryId, String relationVersionId) {
        Set<String> allEquipment = Set.of(chiller, chilledPump, coolingPump, tower);
        return new Configuration(building, station, boundaryId, relationVersionId, "UTC", "UTC_TEST_V1",
                CONFIG_FROM, CONFIG_TO,
                List.of(new Equipment(chiller, EquipmentRole.CHILLER),
                        new Equipment(chilledPump, EquipmentRole.CHILLED_WATER_PUMP),
                        new Equipment(coolingPump, EquipmentRole.COOLING_WATER_PUMP),
                        new Equipment(tower, EquipmentRole.TOWER_FAN)),
                List.of(new CoolingSource("cooling-cumulative", SourceMode.METER_CUMULATIVE, Set.of(chiller),
                        "WATER_LOOP_1", "PRODUCTION_OUTLET", null,
                        new Point(coolingMeter, "ACCUMULATE", "kWh"), null, null, null, null,
                        "SYNTHETIC_WATER_CIRCUIT_EVIDENCE")),
                List.of(new ElectricitySource("station-electricity", new Point(electricityMeter, "ACCUMULATE", "kWh"),
                        allEquipment, "SYNTHETIC_OWNERSHIP_EVIDENCE")),
                "SYNTHETIC_PROFESSIONAL_MAPPING", "EERP_GUIDANCE_RULE_V1", "SYNTHETIC_RULE_REFERENCE");
    }

    private long relationRevision(String versionId) {
        return relationService.versionDetail(operator, ENERGY_MANAGER, building, versionId).version().revision();
    }

    private String idempotency(String operation) {
        return operation + "-" + building;
    }

    private String node(String type, String objectId) {
        return jdbc.queryForObject("""
                SELECT node_id FROM biz_relation_node
                WHERE building_id=? AND node_type=? AND business_object_id=?
                """, String.class, building, type, objectId);
    }

    private void stubRawFacts(List<RawEvent> supplied) {
        List<RawEvent> ordered = supplied.stream()
                .sorted(Comparator.comparingLong(RawEvent::eventTime).thenComparing(RawEvent::pointId))
                .toList();
        org.mockito.Mockito.when(rawReader.readRawEvents(any(), any(), anyLong(), anyLong(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    Set<String> points = invocation.getArgument(1);
                    long from = invocation.getArgument(2);
                    long to = invocation.getArgument(3);
                    Cursor after = invocation.getArgument(4);
                    List<RawEvent> page = ordered.stream()
                            .filter(value -> points.contains(value.pointId()))
                            .filter(value -> value.eventTime() >= from && value.eventTime() < to)
                            .filter(value -> after == null || value.eventTime() > after.eventTime()
                                    || value.eventTime() == after.eventTime()
                                    && value.pointId().compareTo(after.pointId()) > 0)
                            .toList();
                    return new RawEventPage(page, false, null);
                });
    }

    private RawEvent event(String pointId, double value, Instant at) {
        return event(pointId, value, at, 0);
    }

    private RawEvent event(String pointId, double value, Instant at, int quality) {
        return new RawEvent(pointId, pointId + "_CODE", building, "SYNTHETIC", pointId + "_SOURCE",
                "SYNTHETIC_DEVICE", value, at.toEpochMilli(), at.toEpochMilli(), quality, false);
    }

    private void assertAudit(String sourceModule, String... actions) {
        List<String> recorded = jdbc.queryForList("""
                SELECT action_type FROM sys_security_audit_event
                WHERE building_id=? AND source_module=?
                """, String.class, building, sourceModule);
        assertThat(recorded).contains(actions);
    }

    private void ensureIndicatorScenario() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_quality_usage_scenario WHERE scenario_code='INDICATOR_CALCULATION'
                """, Integer.class);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO biz_quality_usage_scenario
                    (scenario_id,scenario_code,scenario_name,adapter_type,status,introduced_version)
                    VALUES ('EERP_INDICATOR','INDICATOR_CALCULATION','EERp 指标计算','INDICATOR_INPUT_GATE','ENABLED','TEST_V1')
                    """);
        }
    }

    private void insertUser(long userId, String username) {
        jdbc.update("""
                INSERT INTO sys_user (id,username,password,nickname,status,del_flag)
                VALUES (?,?,?,?,1,0)
                """, userId, username, "not-used", username);
    }

    private void grant(long userId, BackendDuty duty) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID().toString().replace("-", ""), userId, duty.name(),
                Timestamp.valueOf(now), reviewer, Timestamp.valueOf(now));
    }

    private void insertAssets(String suffix) {
        String space = "SP_" + suffix;
        jdbc.update("INSERT INTO building(building_id,building_name,building_code,del_flag) VALUES (?,?,?,0)",
                building, "EERp 隔离建筑", "BG_" + suffix);
        jdbc.update("""
                INSERT INTO biz_space(space_id,building_id,parent_space_id,space_name,space_code,space_type,del_flag)
                VALUES (?,?,NULL,? ,?,'ROOM',0)
                """, space, building, "EERp 冷站机房", "SP_" + suffix);
        jdbc.update("""
                INSERT INTO biz_system_group(system_group_id,system_group_code,building_id,system_type,system_group_name,del_flag)
                VALUES (?,?,?,'HVAC',?,0)
                """, station, "SG_" + suffix, building, "EERp 水冷冷站");
        insertEquipment(chiller, "WCR", "CHILLER", station, space, "CH_" + suffix);
        insertEquipment(chilledPump, "WCP", "PUMP", station, space, "CP_" + suffix);
        insertEquipment(coolingPump, "WCP", "PUMP", station, space, "WP_" + suffix);
        insertEquipment(tower, "WCT", "TOWER", station, space, "TW_" + suffix);
        insertPoint(coolingMeter, "CM_" + suffix, chiller, "COOLING", suffix);
        insertPoint(electricityMeter, "EM_" + suffix, chiller, "ELECTRIC", suffix);
    }

    private void insertEquipment(String id, String type, String category, String system, String space, String code) {
        jdbc.update("""
                INSERT INTO biz_equipment
                (equip_id,equip_code,equip_name,type_code,equip_category,system_group_id,building_id,space_id,del_flag)
                VALUES (?,?,?,?,?,?,?,?,0)
                """, id, code, code, type, category, system, building, space);
    }

    private void insertPoint(String id, String code, String equipment, String suffixCode, String suffix) {
        jdbc.update("""
                INSERT INTO biz_data_point
                (point_id,point_code,point_name,building_id,system_group_id,equip_id,naming_rule_id,
                 family_code,component_code,suffix_code,data_type,unit,is_for_calc,status,del_flag)
                VALUES (?,?,?,?,?,?,?,'WCR','MAIN',?,'ACCUMULATE','kWh',1,'ONLINE',0)
                """, id, code, code, building, station, equipment, "RULE_WCR_MAIN", suffixCode + suffix);
    }
}
