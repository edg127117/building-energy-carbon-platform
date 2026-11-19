package com.platform.hvac.asset.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.asset.service.AssetErrors;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.PolicySource;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.LatestRawReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.platform.hvac.asset.ledger.LedgerContracts.*;
import static com.platform.iot.qualityusage.QualityUsageModels.POINT_REALTIME_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 隔离 H2 档案与显式 TDengine/质量策略替身验证台账，不连接现场设备或外部资源。 */
class LedgerServiceTest {
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");

    private JdbcTemplate jdbc;
    private HvacRawEventRepository rawEvents;
    private QualityUsagePolicyResolver quality;
    private ResolutionContext context;
    private LedgerService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:ledger_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createArchiveTables();
        new ResourceDatabasePopulator(new ClassPathResource("ledger-display-test.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("device-communication-test.sql")).execute(dataSource);
        rawEvents = mock(HvacRawEventRepository.class);
        quality = mock(QualityUsagePolicyResolver.class);
        context = mock(ResolutionContext.class);
        service = new LedgerService(jdbc, rawEvents, quality, new ObjectMapper(),
                new com.platform.iot.communication.DeviceCommunicationService(jdbc,
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),
                        new com.platform.iot.communication.CommunicationProperties(), java.time.Clock.systemUTC(),60),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.platform.iot.roomsensor.RoomSensorReadings.class));
        when(rawEvents.findLatestByEquipmentPoints(anyString(), anyString(), anyCollection()))
                .thenReturn(List.of());
        when(quality.runtimeContext()).thenReturn(context);
    }

    @Test
    void mapsTypesThenEquipmentOverrideAndRevocationWithoutLosingUnclassifiedOrScopeCounts() {
        type("T_HVAC", 1);
        type("T_POWER", 1);
        type("T_UNMAPPED", 1);
        rule("T_HVAC", "HVAC", true, null, null, 1);
        rule("T_POWER", "POWER", false, null, null, 1);
        equipment("E_HVAC", "B1", "T_HVAC", "S1", "G1", "一号空调");
        equipment("E_POWER", "B1", "T_POWER", "S1", "G1", "一号电表");
        equipment("E_UNMAPPED", "B1", "T_UNMAPPED", "S1", "G1", "待确认设备");
        equipment("E_SCOPE", "B1", "T_HVAC", "S2", "G2", "范围内空调");
        equipment("E_OTHER", "B2", "T_HVAC", "S1", "G1", "其他建筑空调");

        var override = service.saveClassification("E_HVAC",
                new ClassificationRequest("WATER", "设备专业确认", 0L), 7L, ADMIN);
        assertThat(override.revision()).isEqualTo(1);

        LedgerPage scoped = service.query(1, 20, "B1", "S1", "G1", null, null, "ALL", ADMIN);
        assertThat(scoped.total()).isEqualTo(3);
        assertThat(scoped.groupCounts()).containsEntry("ALL", 3L)
                .containsEntry("WATER", 1L).containsEntry("POWER", 1L)
                .containsEntry("HVAC", 0L).containsEntry("UNCLASSIFIED", 1L);
        assertThat(item(scoped, "E_HVAC").professionalGroup()).isEqualTo("WATER");
        assertThat(item(scoped, "E_HVAC").classificationSource()).isEqualTo("EQUIPMENT");
        assertThat(item(scoped, "E_HVAC").classificationRevision()).isEqualTo(1);
        assertThat(item(scoped, "E_UNMAPPED").professionalGroup()).isEqualTo("UNCLASSIFIED");
        assertThat(item(scoped, "E_UNMAPPED").classificationSource()).isEqualTo("UNMAPPED");

        LedgerPage onlyWater = service.query(1, 20, "B1", "S1", "G1", null, null, "WATER", ADMIN);
        assertThat(onlyWater.total()).isEqualTo(1);
        assertThat(onlyWater.items()).extracting(value -> value.equipment().equipmentId())
                .containsExactly("E_HVAC");
        LedgerPage otherScope = service.query(1, 20, "B1", "S2", "G2", "T_HVAC", "范围", "HVAC", ADMIN);
        assertThat(otherScope.total()).isEqualTo(1);
        assertThat(otherScope.items()).extracting(value -> value.equipment().equipmentId())
                .containsExactly("E_SCOPE");

        var revoked = service.saveClassification("E_HVAC",
                new ClassificationRequest(null, "撤销设备覆盖，回退类型规则", override.revision()), 8L, ADMIN);
        assertThat(revoked.professionalGroup()).isNull();
        LedgerPage afterRevoke = service.query(1, 20, "B1", "S1", "G1", null, null, "ALL", ADMIN);
        assertThat(item(afterRevoke, "E_HVAC").professionalGroup()).isEqualTo("HVAC");
        assertThat(item(afterRevoke, "E_HVAC").classificationSource()).isEqualTo("TYPE_RULE");
        assertThat(item(afterRevoke, "E_HVAC").classificationRevision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_ledger_config_audit "
                + "WHERE config_kind='EQUIPMENT_CLASSIFICATION' AND target_id='E_HVAC'", Long.class)).isEqualTo(2L);
    }

    @Test
    void aggregatesSpaceSubtreeBeforePaginationAndKeepsWholeBuildingAndRoomDistinct() {
        type("T_HVAC", 1);
        type("T_POWER", 1);
        rule("T_HVAC", "HVAC", true, null, null, 1);
        rule("T_POWER", "POWER", false, null, null, 1);
        equipment("E_FLOOR", "B1", "T_POWER", "F3", "G1", "楼层电表");
        equipment("E_ROOM", "B1", "T_HVAC", "R303", "G1", "房间空调");
        equipment("E_CABINET", "B1", "T_POWER", "CAB", "G1", "房间下级空间电表");
        equipment("E_OTHER_FLOOR", "B1", "T_HVAC", "F5", "G1", "另一楼层空调");
        equipment("E_UNASSIGNED", "B1", "T_HVAC", null, "G1", "未关联空间空调");
        equipment("E_CROSS", "B2", "T_HVAC", "B2_ROOM", "G1", "另一建筑空调");
        jdbc.update("UPDATE biz_space SET parent_space_id='F3' WHERE space_id IN ('R303','B2_ROOM')");
        jdbc.update("UPDATE biz_space SET parent_space_id='R303' WHERE space_id='CAB'");
        point("P_FLOOR", "E_FLOOR", "B1", "MAIN", "P", "ONLINE");
        point("P_ROOM", "E_ROOM", "B1", "MAIN", "roomTemp", "ONLINE");

        var wholeBuilding = service.query(1, 20, "B1", null, null, null, null, "ALL", ADMIN);
        assertThat(wholeBuilding.total()).isEqualTo(5);
        assertThat(item(wholeBuilding, "E_UNASSIGNED").equipment().spaceId()).isNull();

        var floor = service.query(1, 1, "B1", "F3", null, null, null, "ALL", ADMIN);
        assertThat(floor.total()).isEqualTo(3);
        assertThat(floor.groupCounts()).containsEntry("ALL", 3L).containsEntry("POWER", 2L).containsEntry("HVAC", 1L);
        assertThat(floor.summary()).extracting(LedgerSummary::assets, LedgerSummary::points, LedgerSummary::notMonitored)
                .containsExactly(3L, 2L, 3L);
        assertThat(floor.items()).hasSize(1);

        var filtered = service.query(1, 20, "B1", "F3", null, null, null, "HVAC", ADMIN);
        assertThat(filtered.groupCounts()).isEqualTo(floor.groupCounts());
        assertThat(filtered.summary().assets()).isEqualTo(1);
        assertThat(filtered.items()).extracting(row -> row.equipment().equipmentId()).containsExactly("E_ROOM");

        var room = service.query(1, 20, null, "R303", null, null, null, "ALL", ADMIN);
        assertThat(room.items()).extracting(row -> row.equipment().equipmentId())
                .containsExactlyInAnyOrder("E_ROOM", "E_CABINET");
        assertThat(service.query(1, 20, "B2", "F3", null, null, null, "ALL", ADMIN).total()).isZero();
        assertThat(service.query(1, 20, "B1", "MISSING", null, null, null, "ALL", ADMIN).total()).isZero();

        jdbc.update("UPDATE biz_space SET del_flag=1 WHERE space_id='R303'");
        var afterRemoval = service.query(1, 20, "B1", "F3", null, null, null, "ALL", ADMIN);
        assertThat(afterRemoval.items()).extracting(row -> row.equipment().equipmentId()).containsExactly("E_FLOOR");
    }

    @Test
    void retainsTotalsPastOneThousandAndAppliesDatabasePaginationWithoutFrontendCap() {
        type("T_BULK", 1);
        rule("T_BULK", "HVAC", true, null, null, 1);
        for (int index = 0; index < 1001; index++) {
            equipment("E_BULK_" + index, "B1", "T_BULK", "S1", "G1", "批量设备" + index);
        }

        LedgerPage tail = service.query(11, 100, "B1", null, null, null, null, "ALL", ADMIN);
        assertThat(tail.total()).isEqualTo(1001);
        assertThat(tail.groupCounts()).containsEntry("ALL", 1001L).containsEntry("HVAC", 1001L);
        assertThat(tail.items()).hasSize(1);
        assertThatThrownBy(() -> service.query(1, 101, "B1", null, null, null, null, "ALL", ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getErrorCode()).isEqualTo(AssetErrors.VALIDATION_FAILED);
                });
    }

    @Test
    void validatesClassificationAndTypeRulesWithOptimisticRevisionAndAuditsSuccessfulWrites() {
        type("T_RULE", 1);
        equipment("E_CLASS", "B1", "T_RULE", "S1", "G1", "分类设备");

        assertThatThrownBy(() -> service.query(1, 20, null, null, null, null, null, "ALL", Set.of("BUILDING_OWNER")))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(403);
                    assertThat(error.getErrorCode()).isEqualTo("ASSET_FORBIDDEN");
                });
        assertThatThrownBy(() -> service.saveClassification("MISSING",
                new ClassificationRequest("HVAC", "不存在设备", 0L), 1L, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        assertThatThrownBy(() -> service.saveClassification("E_CLASS",
                new ClassificationRequest("GUESS", "不允许猜测专业", 0L), 1L, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));

        var classification = service.saveClassification("E_CLASS",
                new ClassificationRequest("WATER", "设备专业确认", 0L), 1L, ADMIN);
        assertThat(classification.revision()).isEqualTo(1);
        assertThatThrownBy(() -> service.saveClassification("E_CLASS",
                new ClassificationRequest("HVAC", "陈旧修订", 0L), 1L, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo(AssetErrors.STATE_CONFLICT);
                });
        assertThatThrownBy(() -> service.saveRule("T_RULE",
                new TypeRuleRequest("HVAC", true, "MAIN", null, "半条展示规则", 0L), 1L, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));

        var saved = service.saveRule("T_RULE",
                new TypeRuleRequest("HVAC", true, "MAIN", "roomTemp", "专业确认展示点", 0L), 2L, ADMIN);
        assertThat(saved.revision()).isEqualTo(1);
        assertThatThrownBy(() -> service.saveRule("T_RULE",
                new TypeRuleRequest("POWER", false, "MAIN", "P", "陈旧规则", 0L), 2L, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo(AssetErrors.STATE_CONFLICT);
                });
        type("ELECTRIC_METER_1P", 1);
        assertThatThrownBy(() -> service.saveRule("ELECTRIC_METER_1P",
                new TypeRuleRequest("POWER", true, "MAIN", "P", "电表不能伪装运行状态", 0L), 2L, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        assertThat(service.rules(ADMIN)).extracting(TypeRule::typeCode).containsExactly("T_RULE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_ledger_config_audit", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT after_json FROM biz_ledger_config_audit "
                + "WHERE config_kind='TYPE_RULE'", String.class)).contains("T_RULE");
    }

    @Test
    void usesOnlyExactDisplayPointAndKeepsZeroQualityAndFailureStatesExplicit() {
        type("T_POINT", 1);
        type("T_NONE", 1);
        rule("T_POINT", "HVAC", true, "MAIN", "roomTemp", 1);
        equipment("E_ZERO", "B1", "T_POINT", "S1", "G1", "零值设备");
        point("P_ZERO_WRONG_COMPONENT", "E_ZERO", "B1", "OTHER", "roomTemp", "ONLINE");
        point("P_ZERO_WRONG_SUFFIX", "E_ZERO", "B1", "MAIN", "humidity", "ONLINE");
        point("P_ZERO", "E_ZERO", "B1", "MAIN", "roomTemp", "ONLINE");
        equipment("E_Q1_ALLOW", "B1", "T_POINT", "S1", "G1", "Q1允许");
        point("P_Q1_ALLOW", "E_Q1_ALLOW", "B1", "MAIN", "roomTemp", "ONLINE");
        equipment("E_Q1_BLOCK", "B1", "T_POINT", "S1", "G1", "Q1拒绝");
        point("P_Q1_BLOCK", "E_Q1_BLOCK", "B1", "MAIN", "roomTemp", "ONLINE");
        equipment("E_RAW_FAILURE", "B1", "T_POINT", "S1", "G1", "原始值故障");
        point("P_RAW_FAILURE", "E_RAW_FAILURE", "B1", "MAIN", "roomTemp", "ONLINE");
        equipment("E_POLICY_FAILURE", "B1", "T_POINT", "S1", "G1", "策略故障");
        point("P_POLICY_FAILURE", "E_POLICY_FAILURE", "B1", "MAIN", "roomTemp", "ONLINE");
        equipment("E_NO_RULE", "B1", "T_NONE", "S1", "G1", "未配置规则");
        equipment("E_NO_POINT", "B1", "T_POINT", "S1", "G1", "未配置点位");
        equipment("E_AMBIGUOUS", "B1", "T_POINT", "S1", "G1", "歧义点位");
        point("P_AMBIGUOUS_1", "E_AMBIGUOUS", "B1", "MAIN", "roomTemp", "ONLINE");
        point("P_AMBIGUOUS_2", "E_AMBIGUOUS", "B1", "MAIN", "roomTemp", "ONLINE");
        equipment("E_DISABLED", "B1", "T_POINT", "S1", "G1", "停用点位");
        point("P_DISABLED", "E_DISABLED", "B1", "MAIN", "roomTemp", "OFFLINE");

        long eventTime = 1_800_000_000_123L;
        when(rawEvents.findLatestByEquipmentPoints(anyString(), anyString(), anyCollection())).thenAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(2);
            String pointId = ids.iterator().next();
            return switch (pointId) {
                case "P_ZERO" -> List.of(new LatestRawReading(pointId, 0.0, eventTime, eventTime + 10, 0));
                case "P_Q1_ALLOW", "P_Q1_BLOCK" -> List.of(new LatestRawReading(pointId, 12.5, eventTime, eventTime + 10, 1));
                case "P_RAW_FAILURE" -> throw new IllegalStateException("TDENGINE_UNAVAILABLE");
                case "P_POLICY_FAILURE" -> List.of(new LatestRawReading(pointId, 9.0, eventTime, eventTime + 10, 0));
                default -> List.of();
            };
        });
        when(quality.resolve(eq(context), anyString(), eq(POINT_REALTIME_VIEW), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    String pointId = invocation.getArgument(1);
                    int dataQuality = invocation.getArgument(4);
                    if ("P_POLICY_FAILURE".equals(pointId)) {
                        throw new IllegalStateException("POLICY_SNAPSHOT_UNAVAILABLE");
                    }
                    if ("P_Q1_BLOCK".equals(pointId)) {
                        return resolution(Decision.BLOCK, dataQuality, "Q1_DENIED");
                    }
                    return resolution(Decision.ALLOW, dataQuality, "QUALITY_ALLOWED");
                });

        LedgerPage page = service.query(1, 20, "B1", null, null, null, null, "ALL", ADMIN);
        LedgerDisplayPoint zero = item(page, "E_ZERO").displayPoint();
        assertThat(zero.status()).isEqualTo("AVAILABLE");
        assertThat(zero.pointId()).isEqualTo("P_ZERO");
        assertThat(zero.value()).isZero();
        assertThat(zero.eventTime()).isEqualTo(eventTime);
        LedgerDisplayPoint allowed = item(page, "E_Q1_ALLOW").displayPoint();
        assertThat(allowed.status()).isEqualTo("AVAILABLE");
        assertThat(allowed.dataQuality()).isEqualTo(1);
        assertThat(allowed.usageStatus()).isEqualTo("AVAILABLE");
        LedgerDisplayPoint blocked = item(page, "E_Q1_BLOCK").displayPoint();
        assertThat(blocked.status()).isEqualTo("QUALITY_BLOCKED");
        assertThat(blocked.value()).isNull();
        assertThat(blocked.reason()).isEqualTo("Q1_DENIED");
        assertThat(blocked.usageStatus()).isEqualTo("QUALITY_BLOCKED");
        assertUnavailable(item(page, "E_RAW_FAILURE").displayPoint());
        assertUnavailable(item(page, "E_POLICY_FAILURE").displayPoint());
        assertThat(item(page, "E_NO_RULE").displayPoint().status()).isEqualTo("UNCONFIGURED");
        assertThat(item(page, "E_NO_POINT").displayPoint().status()).isEqualTo("UNMATCHED");
        assertThat(item(page, "E_AMBIGUOUS").displayPoint().status()).isEqualTo("AMBIGUOUS");
        assertThat(item(page, "E_DISABLED").displayPoint())
                .extracting(LedgerDisplayPoint::status, LedgerDisplayPoint::reason)
                .containsExactly("DISABLED", "POINT_DISABLED");
        verify(rawEvents).findLatestByEquipmentPoints("B1", "E_ZERO", List.of("P_ZERO"));
    }

    @Test
    void rejectsPointFromAnotherBuildingWhenArchiveIdentityCollides() {
        type("T_BOUNDARY", 1);
        rule("T_BOUNDARY", "HVAC", true, "MAIN", "roomTemp", 1);
        equipment("E_SHARED", "B1", "T_BOUNDARY", "S1", "G1", "本楼设备");
        equipment("E_SHARED", "B2", "T_BOUNDARY", "S1", "G1", "他楼同标识设备");
        point("P_FOREIGN", "E_SHARED", "B2", "MAIN", "roomTemp", "ONLINE");

        LedgerPage page = service.query(1, 20, "B1", null, null, null, null, "ALL", ADMIN);
        assertThat(item(page, "E_SHARED").displayPoint())
                .extracting(LedgerDisplayPoint::status, LedgerDisplayPoint::reason)
                .containsExactly("UNMATCHED", "DISPLAY_POINT_NOT_FOUND");
        verifyNoInteractions(rawEvents);
    }

    @Test
    void usesOnlyFreshCurrentDaikinStateAndNeverInfersOnlineFromControllerConnection() {
        type("T_OPERATION", 1);
        type("ELECTRIC_METER_1P", 1);
        rule("T_OPERATION", "HVAC", true, null, null, 1);
        rule("ELECTRIC_METER_1P", "POWER", false, "MAIN", "P", 1);
        for (String equipmentId : List.of("E_IDU", "E_ODU", "E_MISSING", "E_STALE", "E_OLD_BUILDING",
                "E_OLD_MAPPING", "E_CONFLICT", "E_CONTROLLER", "E_ERROR", "E_DIRECTORY_MISSING")) {
            equipment(equipmentId, "B1", "T_OPERATION", "S1", "G1", equipmentId);
        }
        equipment("E_METER", "B1", "ELECTRIC_METER_1P", "S1", "G1", "电表");
        long now = System.currentTimeMillis();
        bindDaikin("E_IDU", "B1", "I_IDU", "INDOOR", 4, 1, 1);
        state("I_IDU", "E_IDU", "B1", "onOff", "on", "PRESENT", now, "B1", 4, "B1", 4);
        bindDaikin("E_ODU", "B1", "I_ODU", "OUTDOOR", 4, 1, 1);
        state("I_ODU", "E_ODU", "B1", "compressorOnOff", "true", "PRESENT", now, "B1", 4, "B1", 4);
        bindDaikin("E_MISSING", "B1", "I_MISSING", "INDOOR", 4, 1, 1);
        state("I_MISSING", "E_MISSING", "B1", "onOff", "on", "MISSING", now, "B1", 4, "B1", 4);
        bindDaikin("E_STALE", "B1", "I_STALE", "INDOOR", 4, 1, 1);
        state("I_STALE", "E_STALE", "B1", "onOff", "on", "PRESENT", now - 300_001, "B1", 4, "B1", 4);
        bindDaikin("E_OLD_BUILDING", "B1", "I_OLD_BUILDING", "INDOOR", 4, 1, 1);
        state("I_OLD_BUILDING", "E_OLD_BUILDING", "B1", "onOff", "on", "PRESENT", now, "B0", 4, "B1", 4);
        bindDaikin("E_OLD_MAPPING", "B1", "I_OLD_MAPPING", "INDOOR", 4, 1, 1);
        state("I_OLD_MAPPING", "E_OLD_MAPPING", "B1", "onOff", "on", "PRESENT", now, "B1", 3, "B1", 3);
        bindDaikin("E_CONFLICT", "B1", "I_CONFLICT_1", "INDOOR", 4, 1, 1);
        bindDaikin("E_CONFLICT", "B1", "I_CONFLICT_2", "INDOOR", 4, 1, 1);
        state("I_CONFLICT_1", "E_CONFLICT", "B1", "onOff", "on", "PRESENT", now, "B1", 4, "B1", 4);
        state("I_CONFLICT_2", "E_CONFLICT", "B1", "onOff", "on", "PRESENT", now, "B1", 4, "B1", 4);
        state("I_CONFLICT_1", "E_CONFLICT", "B1", "inCommunicationError", "true", "PRESENT", now, "B1", 4, "B1", 4);
        state("I_CONFLICT_2", "E_CONFLICT", "B1", "inCommunicationError", "true", "PRESENT", now, "B1", 4, "B1", 4);
        bindDaikin("E_CONTROLLER", "B1", "I_CONTROLLER", "INDOOR", 4, 1, 1);
        state("I_CONTROLLER", "E_CONTROLLER", "B1", "inCommunicationError", "false", "PRESENT", now, "B1", 4, "B1", 4);
        bindDaikin("E_ERROR", "B1", "I_ERROR", "INDOOR", 4, 1, 1);
        state("I_ERROR", "E_ERROR", "B1", "inCommunicationError", "true", "PRESENT", now, "B1", 4, "B1", 4);
        bindDaikin("E_DIRECTORY_MISSING", "B1", "I_DIRECTORY_MISSING", "INDOOR", 4, 1, 1);
        jdbc.update("DELETE FROM biz_daikin_directory WHERE pending_id=? AND source_id=?",
                "P_I_DIRECTORY_MISSING", "SRC_I_DIRECTORY_MISSING");
        state("I_DIRECTORY_MISSING", "E_DIRECTORY_MISSING", "B1", "inCommunicationError", "true", "PRESENT", now, "B1", 4, "B1", 4);

        LedgerPage page = service.query(1, 20, "B1", null, null, null, null, "ALL", ADMIN);
        assertRunning(item(page, "E_IDU").operation(), "DEVICE");
        assertRunning(item(page, "E_ODU").operation(), "COMPRESSOR");
        assertUnknown(item(page, "E_MISSING").operation(), "STATE_UNAVAILABLE_OR_STALE");
        assertUnknown(item(page, "E_STALE").operation(), "STATE_UNAVAILABLE_OR_STALE");
        assertUnknown(item(page, "E_OLD_BUILDING").operation(), "STATE_UNAVAILABLE_OR_STALE");
        assertUnknown(item(page, "E_OLD_MAPPING").operation(), "STATE_UNAVAILABLE_OR_STALE");
        assertUnknown(item(page, "E_CONFLICT").operation(), "IDENTITY_CONFLICT");
        assertUnknown(item(page, "E_CONTROLLER").communication(), "NOT_MONITORED");
        assertUnknown(item(page, "E_DIRECTORY_MISSING").communication(), "NOT_MONITORED");
        assertThat(item(page, "E_ERROR").communication())
                .extracting(LedgerState::value, LedgerState::reason)
                .containsExactly("UNKNOWN", "NOT_MONITORED");
        assertThat(item(page, "E_METER").operation())
                .extracting(LedgerState::applicable, LedgerState::value, LedgerState::reason)
                .containsExactly(false, "NOT_APPLICABLE", "TYPE_HAS_NO_OPERATION_STATE");
        assertThat(page.summary())
                .extracting(LedgerSummary::online, LedgerSummary::offline, LedgerSummary::communicationUnknown,
                        LedgerSummary::communicationError,LedgerSummary::notMonitored)
                .containsExactly(0L, 0L, 0L, 0L,11L);
    }

    private void createArchiveTables() {
        jdbc.execute("CREATE TABLE building(building_id VARCHAR(32) PRIMARY KEY,building_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE biz_space(space_id VARCHAR(32),building_id VARCHAR(32),space_name VARCHAR(100),"
                + "parent_space_id VARCHAR(32),del_flag INT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY(space_id,building_id))");
        jdbc.execute("CREATE TABLE biz_system_group(system_group_id VARCHAR(32),building_id VARCHAR(32),"
                + "system_group_name VARCHAR(100),PRIMARY KEY(system_group_id,building_id))");
        jdbc.execute("CREATE TABLE biz_device_product(product_id VARCHAR(50) PRIMARY KEY,product_name VARCHAR(100),"
                + "expected_profile_code VARCHAR(50),status VARCHAR(20) DEFAULT 'ENABLED')");
        jdbc.execute("CREATE TABLE biz_equipment_type(type_code VARCHAR(50) PRIMARY KEY,equip_category VARCHAR(50) NOT NULL,status INT NOT NULL)");
        jdbc.execute("CREATE TABLE biz_equipment(equip_id VARCHAR(32),building_id VARCHAR(32),equip_code VARCHAR(50),"
                + "equip_name VARCHAR(100),type_code VARCHAR(50),equip_category VARCHAR(50),space_id VARCHAR(32),"
                + "system_group_id VARCHAR(32),product_id VARCHAR(50),create_time TIMESTAMP,update_time TIMESTAMP,"
                + "del_flag INT NOT NULL,PRIMARY KEY(equip_id,building_id))");
        jdbc.execute("CREATE TABLE biz_data_point(point_id VARCHAR(32),building_id VARCHAR(32),equip_id VARCHAR(32),"
                + "point_code VARCHAR(100),point_name VARCHAR(100),unit VARCHAR(20),component_code VARCHAR(20),"
                + "suffix_code VARCHAR(20),status VARCHAR(20),del_flag INT NOT NULL,PRIMARY KEY(point_id,building_id))");
        jdbc.execute("CREATE TABLE biz_product_point_template(product_id VARCHAR(50),status INT,required_flag INT,"
                + "suffix_code VARCHAR(20))");
        jdbc.execute("CREATE TABLE biz_device_identity(identity_id VARCHAR(32) PRIMARY KEY,equip_id VARCHAR(32),"
                + "building_id VARCHAR(32),identity_type VARCHAR(20),status INT,identity_value VARCHAR(100) DEFAULT 'VALUE',expected_profile_code VARCHAR(100) DEFAULT 'PROFILE',update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE biz_pending_device(pending_id VARCHAR(32),bound_identity_id VARCHAR(32),status VARCHAR(20))");
        jdbc.execute("CREATE TABLE biz_daikin_project_mapping(source_id VARCHAR(200),site_id VARCHAR(200),building_id VARCHAR(32),mapping_version INT)");
        jdbc.execute("CREATE TABLE biz_daikin_monitoring_target(identity_id VARCHAR(32),source_id VARCHAR(200),"
                + "pending_id VARCHAR(32),equipment_id VARCHAR(32),building_id VARCHAR(32),mapping_version INT,active INT)");
        jdbc.execute("CREATE TABLE biz_daikin_directory(pending_id VARCHAR(32),source_id VARCHAR(200),device_kind VARCHAR(10),site_id VARCHAR(200),"
                + "PRIMARY KEY(pending_id,source_id))");
        jdbc.execute("CREATE TABLE biz_daikin_current_state(identity_id VARCHAR(32),field_name VARCHAR(100),"
                + "equipment_id VARCHAR(32),building_id VARCHAR(32),mapping_version INT,normalized_value VARCHAR(100),"
                + "field_status VARCHAR(16),last_valid_at_ms BIGINT,last_attempt_building_id VARCHAR(32),"
                + "last_attempt_mapping_version INT,PRIMARY KEY(identity_id,field_name))");
    }

    private void type(String code, int status) {
        String category = "ELECTRIC_METER_1P".equals(code) || "ELECTRIC_METER_3P".equals(code)
                ? "ELECTRIC_METER" : "TEST_CATEGORY";
        jdbc.update("INSERT INTO biz_equipment_type(type_code,equip_category,status) VALUES(?,?,?)", code, category, status);
    }

    private void rule(String typeCode, String group, boolean operationApplicable, String component, String suffix, long revision) {
        jdbc.update("INSERT INTO biz_ledger_type_rule(type_code,professional_group,operation_applicable,point_component,"
                        + "point_suffix,evidence,revision,updated_by) VALUES(?,?,?,?,?,?,?,?)",
                typeCode, group, operationApplicable ? 1 : 0, component, suffix, "测试专业确认", revision, null);
    }

    private void equipment(String equipmentId, String buildingId, String typeCode, String spaceId, String groupId, String name) {
        ensureBuilding(buildingId);
        if (spaceId != null) ensureSpace(spaceId, buildingId);
        ensureSystemGroup(groupId, buildingId);
        jdbc.update("INSERT INTO biz_equipment(equip_id,building_id,equip_code,equip_name,type_code,equip_category,"
                        + "space_id,system_group_id,product_id,create_time,update_time,del_flag) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                equipmentId, buildingId, equipmentId + "_CODE", name, typeCode, "TEST_CATEGORY", spaceId, groupId, null);
    }

    private void point(String pointId, String equipmentId, String buildingId, String component, String suffix, String status) {
        jdbc.update("INSERT INTO biz_data_point(point_id,building_id,equip_id,point_code,point_name,unit,component_code,"
                        + "suffix_code,status,del_flag) VALUES(?,?,?,?,?,?,?,?,?,0)",
                pointId, buildingId, equipmentId, pointId + "_CODE", pointId + "名称", "kW", component, suffix, status);
    }

    private void bindDaikin(String equipmentId, String buildingId, String identityId, String kind,
                            int mappingVersion, int active, int identityStatus) {
        String sourceId = "SRC_" + identityId;
        String pendingId = "P_" + identityId;
        jdbc.update("INSERT INTO biz_device_identity(identity_id,equip_id,building_id,identity_type,status) VALUES(?,?,?,?,?)",
                identityId, equipmentId, buildingId, "DAIKIN_UNIT", identityStatus);
        jdbc.update("INSERT INTO biz_daikin_monitoring_target(identity_id,source_id,pending_id,equipment_id,building_id,"
                        + "mapping_version,active) VALUES(?,?,?,?,?,?,?)",
                identityId, sourceId, pendingId, equipmentId, buildingId, mappingVersion, active);
        jdbc.update("INSERT INTO biz_daikin_directory(pending_id,source_id,device_kind) VALUES(?,?,?)",
                pendingId, sourceId, kind);
    }

    private void state(String identityId, String equipmentId, String targetBuilding, String field, String value,
                       String status, long observedAt, String valueBuilding, int valueMapping,
                       String attemptBuilding, int attemptMapping) {
        jdbc.update("INSERT INTO biz_daikin_current_state(identity_id,field_name,equipment_id,building_id,mapping_version,"
                        + "normalized_value,field_status,last_valid_at_ms,last_attempt_building_id,last_attempt_mapping_version) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                identityId, field, equipmentId, valueBuilding, valueMapping, value, status, observedAt, attemptBuilding, attemptMapping);
    }

    private void ensureBuilding(String buildingId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM building WHERE building_id=?", Long.class, buildingId) == 0) {
            jdbc.update("INSERT INTO building(building_id,building_name) VALUES(?,?)", buildingId, buildingId + "楼");
        }
    }

    private void ensureSpace(String spaceId, String buildingId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM biz_space WHERE space_id=? AND building_id=?", Long.class,
                spaceId, buildingId) == 0) {
            jdbc.update("INSERT INTO biz_space(space_id,building_id,space_name) VALUES(?,?,?)",
                    spaceId, buildingId, spaceId + "空间");
        }
    }

    private void ensureSystemGroup(String groupId, String buildingId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM biz_system_group WHERE system_group_id=? AND building_id=?", Long.class,
                groupId, buildingId) == 0) {
            jdbc.update("INSERT INTO biz_system_group(system_group_id,building_id,system_group_name) VALUES(?,?,?)",
                    groupId, buildingId, groupId + "系统");
        }
    }

    private static LedgerItem item(LedgerPage page, String equipmentId) {
        return page.items().stream().filter(value -> equipmentId.equals(value.equipment().equipmentId()))
                .findFirst().orElseThrow();
    }

    private static Resolution resolution(Decision decision, int quality, String reason) {
        return new Resolution(decision, quality, POINT_REALTIME_VIEW, PolicySource.SYSTEM_DEFAULT_Q0_ONLY,
                null, 1L, reason);
    }

    private static void assertUnavailable(LedgerDisplayPoint point) {
        assertThat(point).extracting(LedgerDisplayPoint::status, LedgerDisplayPoint::reason)
                .containsExactly("UNAVAILABLE", "READING_OR_POLICY_UNAVAILABLE");
    }

    private static void assertRunning(LedgerState state, String scope) {
        assertThat(state).extracting(LedgerState::applicable, LedgerState::value, LedgerState::reason, LedgerState::scope)
                .containsExactly(true, "RUNNING", "VALID_OBSERVATION", scope);
    }

    private static void assertUnknown(LedgerState state, String reason) {
        assertThat(state).extracting(LedgerState::value, LedgerState::reason)
                .containsExactly("UNKNOWN", reason);
    }
}
