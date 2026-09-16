package com.platform.iot.onboarding.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.audit.system.BindTypedPendingDeviceHandler;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import com.platform.iot.onboarding.DeviceOnboardingService;
import com.platform.iot.onboarding.DeviceProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H2 隔离验证温度测点配置与审批绑定，不连接真实厂家接口或时序库。 */
@SpringBootTest
@ActiveProfiles("test")
class DaikinTemperatureBindingIntegrationTest {
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-15T01:00:00Z");

    @Autowired DeviceProductService products;
    @Autowired DeviceOnboardingService onboarding;
    @Autowired DaikinDirectoryService directory;
    @Autowired SensitiveChangeService changes;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private String suffix;
    private String directorySourceId;
    private String httpSourceId;
    private String wrongBuildingSourceId;

    @BeforeEach
    void setup() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        directorySourceId = "DTDIR" + suffix;
        httpSourceId = "DTHTTP" + suffix;
        wrongBuildingSourceId = "DTWRONG" + suffix;
        jdbc.update("INSERT INTO biz_equipment_type VALUES (?,?,?,?,?,1)",
                "DTIDU" + suffix, "大金测试内机", "DTI", "INDOOR_UNIT", "TEST");
        jdbc.update("INSERT INTO biz_equipment_type VALUES (?,?,?,?,?,1)",
                "DTODU" + suffix, "大金测试外机", "DTO", "OUTDOOR_UNIT", "TEST");
        insertDataSource(httpSourceId, "DTH" + suffix, "BLD001", "HTTP");
        insertDataSource(wrongBuildingSourceId, "DTW" + suffix, "BLD002", "HTTP");
        grant(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grant(BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        directory.registerSource(directorySourceId, 1L, ADMIN);
        directory.mapProject(directorySourceId, "site-" + suffix, "BLD001", 1L, ADMIN);
    }

    @AfterEach
    void cleanup() {
        // 职责键按用户唯一，优先释放，避免后续资源清理失败污染同一测试上下文。
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE assignment_id LIKE ?", "DT-" + suffix + "%");
        List<String> pendingIds = jdbc.queryForList(
                "SELECT pending_id FROM biz_daikin_directory WHERE source_id=?", String.class, directorySourceId);
        List<String> identityIds = pendingIds.stream().flatMap(id -> jdbc.queryForList(
                "SELECT bound_identity_id FROM biz_pending_device WHERE pending_id=? AND bound_identity_id IS NOT NULL",
                String.class, id).stream()).toList();
        List<String> equipmentIds = identityIds.stream().flatMap(id -> jdbc.queryForList(
                "SELECT equip_id FROM biz_device_identity WHERE identity_id=?", String.class, id).stream()).toList();
        for (String equipmentId : equipmentIds) {
            jdbc.update("DELETE FROM biz_point_alias WHERE point_id IN (SELECT point_id FROM biz_data_point WHERE equip_id=?)",
                    equipmentId);
            jdbc.update("DELETE FROM biz_data_point WHERE equip_id=?", equipmentId);
        }
        var auditTargets = new java.util.ArrayList<>(pendingIds);
        auditTargets.addAll(identityIds);
        auditTargets.addAll(equipmentIds);
        auditTargets.addAll(jdbc.queryForList("SELECT product_id FROM biz_device_product WHERE product_code LIKE ?",
                String.class, "DT_" + suffix + "%"));
        auditTargets.forEach(id -> jdbc.update("DELETE FROM biz_onboarding_audit_log WHERE object_id=?", id));
        jdbc.update("DELETE FROM biz_daikin_directory WHERE source_id=?", directorySourceId);
        pendingIds.forEach(id -> jdbc.update("DELETE FROM biz_pending_device WHERE pending_id=?", id));
        identityIds.forEach(id -> jdbc.update("DELETE FROM biz_device_identity WHERE identity_id=?", id));
        jdbc.update("DELETE FROM biz_daikin_catalog_sync WHERE source_id=?", directorySourceId);
        jdbc.update("DELETE FROM biz_daikin_project_mapping_version WHERE source_id=?", directorySourceId);
        jdbc.update("DELETE FROM biz_daikin_project_mapping WHERE source_id=?", directorySourceId);
        jdbc.update("DELETE FROM biz_daikin_source WHERE source_id=?", directorySourceId);
        equipmentIds.forEach(id -> jdbc.update("DELETE FROM biz_equipment WHERE equip_id=?", id));
        jdbc.update("DELETE FROM biz_product_point_template WHERE product_id IN "
                + "(SELECT product_id FROM biz_device_product WHERE product_code LIKE ?)", "DT_" + suffix + "%");
        jdbc.update("DELETE FROM biz_device_product WHERE product_code LIKE ?", "DT_" + suffix + "%");
        jdbc.update("DELETE FROM biz_equipment_type WHERE type_code IN (?,?)", "DTIDU" + suffix, "DTODU" + suffix);
        jdbc.update("DELETE FROM biz_data_source WHERE source_id IN (?,?)", httpSourceId, wrongBuildingSourceId);
        jdbc.update("DELETE FROM sys_sensitive_change_request WHERE idempotency_key LIKE ?", "DT-" + suffix + "%");
    }

    @Test
    void oldTypedRequestsKeepZeroPointCompatibilityAndMissingJsonListsBecomeEmpty() throws Exception {
        var productRequest = new DeviceProductContracts.TypedStateRequest(
                "DT_" + suffix + "_ZERO", "零点产品", null, null, "DTIDU" + suffix, "DAIKIN_INDOOR_V2");
        var bindRequest = new DeviceOnboardingContracts.TypedBindRequest(
                "product", "BLD001", "SPACE001", "GROUP001", null,
                new DeviceOnboardingContracts.NewEquipmentRequest("旧调用", null));
        assertThat(productRequest.temperatureTemplates()).isEmpty();
        assertThat(bindRequest.pointBindings()).isEmpty();
        assertThat(bindRequest.numericSourceId()).isNull();

        String json = "{\"productCode\":\"DT_" + suffix + "_JSON\",\"productName\":\"JSON零点\","
                + "\"equipmentTypeCode\":\"DTIDU" + suffix + "\",\"expectedProfileCode\":\"DAIKIN_INDOOR_V2\"}";
        assertThat(mapper.readValue(json, DeviceProductContracts.TypedStateRequest.class).temperatureTemplates()).isEmpty();
    }

    @Test
    void approvedIndoorTemperatureBindingCreatesDaikinAliasAndActivatesNumericSnapshot() {
        String pendingId = discoverIndoor();
        String productId = createEnabledIndoorProduct("roomTemp", false);
        var request = typedBinding(productId, httpSourceId, "roomTemp");
        var command = new BindTypedPendingDeviceHandler.Command(pendingId, request);
        var draft = changes.createDraft(1L, BindTypedPendingDeviceHandler.CODE,
                mapper.valueToTree(command), "DT-" + suffix + "-bind");
        changes.submit(1L, draft.requestId());
        changes.approve(1L, draft.requestId(), "温度绑定隔离审核");
        changes.execute(1L, draft.requestId());

        var connection = onboarding.connection(pendingId, ADMIN);
        assertThat(connection.identityStatus()).isEqualTo("INACTIVE");
        assertThat(connection.configEffective()).isTrue();
        Map<String, Object> alias = jdbc.queryForMap(
                "SELECT source_id,source_system,source_point_code FROM biz_point_alias WHERE source_system='DAIKIN_V2' AND source_point_code=?",
                "DAIKIN_UNIT:" + identityValue() + ":roomTemp");
        assertThat(alias.get("source_id")).isEqualTo(httpSourceId);
        assertThat(alias.get("source_system")).isEqualTo("DAIKIN_V2");
        Map<String, Object> point = jdbc.queryForMap(
                "SELECT unit,is_for_calc,value_min,value_max FROM biz_data_point WHERE point_id=(SELECT point_id FROM biz_point_alias WHERE source_system='DAIKIN_V2' AND source_point_code=?)",
                "DAIKIN_UNIT:" + identityValue() + ":roomTemp");
        assertThat(point.get("unit")).isEqualTo("°C");
        assertThat(((Number) point.get("is_for_calc")).intValue()).isZero();
        assertThat(point.get("value_min")).isNull();
        assertThat(point.get("value_max")).isNull();
        assertThat(onboarding.activate(connection.identityId(), 1L, ADMIN).configEffective()).isTrue();
    }

    @Test
    void rejectsInvalidTemperatureTemplatesAndNonHttpOrCrossBuildingSources() {
        assertThatThrownBy(() -> createProduct("temperature", true, "DAIKIN_INDOOR_V2"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> createProduct("humidity", false, "DAIKIN_INDOOR_V2"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> createProduct("roomTemp", false, "DAIKIN_OUTDOOR_V2"))
                .isInstanceOf(BusinessException.class);

        String pending = discoverIndoor();
        String product = createEnabledIndoorProduct("temperature", false);
        assertThatThrownBy(() -> onboarding.bindTyped(pending,
                typedBinding(product, "SOURCE_MQTT_FREEZE_V1", "temperature"), 1L, ADMIN))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onboarding.bindTyped(pending,
                typedBinding(product, wrongBuildingSourceId, "temperature"), 1L, ADMIN))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void explicitTemperatureBoundsRemainOptionalAndCanBeBound() {
        String pending = discoverIndoor();
        var product = createProduct("roomTemp", false, "DAIKIN_INDOOR_V2",
                new BigDecimal("-20"), new BigDecimal("50"));
        String productId = products.enable(product.productId(), 1L, ADMIN).productId();

        var result = onboarding.bindTyped(pending,
                typedBinding(productId, httpSourceId, "roomTemp"), 1L, ADMIN);

        assertThat(result.pointIds()).singleElement().satisfies(pointId -> {
            Map<String, Object> range = jdbc.queryForMap(
                    "SELECT value_min,value_max FROM biz_data_point WHERE point_id=?", pointId);
            assertThat(new BigDecimal(range.get("value_min").toString())).isEqualByComparingTo("-20");
            assertThat(new BigDecimal(range.get("value_max").toString())).isEqualByComparingTo("50");
        });
    }

    private String discoverIndoor() {
        directory.acceptCompleteCatalog(directorySourceId, DaikinDeviceKey.Kind.INDOOR, OBSERVED_AT,
                List.of(new DaikinDeviceObservation(
                        new DaikinDeviceKey(directorySourceId, "site-" + suffix, "lc1",
                                DaikinDeviceKey.Kind.INDOOR, "unit-" + suffix),
                        "equip-" + suffix, "测试项目", "测试内机", OBSERVED_AT, null, Map.of())));
        return jdbc.queryForObject("SELECT pending_id FROM biz_daikin_directory WHERE source_id=?", String.class,
                directorySourceId);
    }

    private String createEnabledIndoorProduct(String metric, boolean forCalc) {
        var product = createProduct(metric, forCalc, "DAIKIN_INDOOR_V2");
        return products.enable(product.productId(), 1L, ADMIN).productId();
    }

    private DeviceProductContracts.DetailView createProduct(String metric, boolean forCalc, String profile) {
        return createProduct(metric, forCalc, profile, null, null);
    }

    private DeviceProductContracts.DetailView createProduct(String metric, boolean forCalc, String profile,
            BigDecimal minValue, BigDecimal maxValue) {
        var template = new DeviceProductContracts.PointTemplateRequest(metric, "大金温度", metric,
                "°C", minValue, maxValue, forCalc, true, 1, true);
        String typeCode = "DAIKIN_OUTDOOR_V2".equals(profile) ? "DTODU" + suffix : "DTIDU" + suffix;
        return products.createTypedState(new DeviceProductContracts.TypedStateRequest(
                "DT_" + suffix + "_" + metric.toUpperCase(), "温度产品", null, null,
                typeCode, profile, List.of(template)), 1L, ADMIN);
    }

    private DeviceOnboardingContracts.TypedBindRequest typedBinding(
            String productId, String sourceId, String metric) {
        var point = new DeviceOnboardingContracts.PointBindingRequest(metric, null,
                "AHU99_" + metric, "大金温度", "RULE_AHU_MAIN", "AHU", "MAIN", "AI");
        return new DeviceOnboardingContracts.TypedBindRequest(productId, "BLD001", "SPACE001", "GROUP001", null,
                new DeviceOnboardingContracts.NewEquipmentRequest("DT-内机-" + suffix, "Daikin"),
                List.of(point), sourceId);
    }

    private String identityValue() {
        return jdbc.queryForObject("SELECT identity_value FROM biz_pending_device WHERE pending_id IN "
                + "(SELECT pending_id FROM biz_daikin_directory WHERE source_id=?)", String.class, directorySourceId);
    }

    private void insertDataSource(String id, String code, String building, String transport) {
        jdbc.update("INSERT INTO biz_data_source(source_id,source_code,source_name,building_id,source_category,"
                        + "transport_type,status,config_revision,runtime_revision) VALUES (?,?,?,?,'DEVICE_ACCESS',?,'ENABLED',1,1)",
                id, code, "温度测试来源", building, transport);
    }

    private void grant(BackendDuty duty) {
        jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by,created_at) "
                        + "VALUES (?,1,?,'ACTIVE',?,1,?)",
                "DT-" + suffix + "-" + (duty == BackendDuty.BACKOFFICE_CHANGE_SUBMITTER ? "SUB" : "REV"), duty.name(),
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
    }
}
