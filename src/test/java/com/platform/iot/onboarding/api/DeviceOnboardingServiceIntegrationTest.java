package com.platform.iot.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.TraceContext;
import com.platform.audit.sensitive.SensitiveChangeRecord;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.audit.sensitive.SensitiveChangeStatus;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.identity.MySqlDeviceIdentityProvider;
import com.platform.iot.onboarding.DeviceOnboardingService;
import com.platform.iot.onboarding.DeviceProductService;
import com.platform.iot.onboarding.OnboardingErrors;
import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import com.platform.iot.quality.MySqlDataPointConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:standard_onboarding;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false")
@ActiveProfiles("test")
class DeviceOnboardingServiceIntegrationTest {
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    private static final String PREFIX = "BTEST-";

    @Autowired private DeviceProductService productService;
    @Autowired private DeviceOnboardingService onboardingService;
    @Autowired private BizPendingDeviceMapper pendingMapper;
    @Autowired private MySqlDeviceIdentityProvider identityProvider;
    @SpyBean private MySqlDataPointConfigProvider pointProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SensitiveChangeService sensitiveChangeService;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void prepareSensitiveDuties() {
        jdbcTemplate.update("DELETE FROM sys_security_audit_event");
        jdbcTemplate.update("DELETE FROM sys_sensitive_change_request");
        jdbcTemplate.update("DELETE FROM sys_user_backend_duty");
        grant(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grant(BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        MDC.put(TraceContext.MDC_KEY, "abcdef0123456789abcdef0123456789");
    }

    @AfterEach
    void cleanCreatedRecords() {
        MDC.remove(TraceContext.MDC_KEY);
        jdbcTemplate.update("DELETE FROM sys_security_audit_event");
        jdbcTemplate.update("DELETE FROM sys_sensitive_change_request");
        jdbcTemplate.update("DELETE FROM sys_user_backend_duty");
        jdbcTemplate.update("DELETE FROM biz_pending_device WHERE identity_value LIKE 'BTEST-%'");
        jdbcTemplate.update("DELETE FROM biz_onboarding_audit_log");
        jdbcTemplate.update("DELETE FROM biz_point_alias WHERE source_point_code LIKE '%BTEST-%'");
        jdbcTemplate.update("DELETE FROM biz_device_identity WHERE identity_value LIKE 'BTEST-%'");
        jdbcTemplate.update("""
                DELETE FROM biz_data_point
                WHERE equip_id IN (
                  SELECT equip_id FROM biz_equipment WHERE equip_name LIKE 'BTEST-%'
                )
                """);
        jdbcTemplate.update("DELETE FROM biz_equipment WHERE equip_name LIKE 'BTEST-%'");
        jdbcTemplate.update("""
                DELETE FROM biz_product_point_template
                WHERE product_id IN (
                  SELECT product_id FROM biz_device_product WHERE product_code LIKE 'BTEST_%'
                )
                """);
        jdbcTemplate.update("DELETE FROM biz_device_product WHERE product_code LIKE 'BTEST_%'");
        identityProvider.refreshAll();
    }

    @Test
    void equipmentTypeOptionsExcludeDisabledTypes() {
        jdbcTemplate.update("INSERT INTO biz_equipment_type (type_code,type_name,asset_code_prefix,equip_category,standard_source,status) VALUES ('BTEST_OFF','Disabled','BO','TEST','TEST',0)");
        try {
            assertThat(productService.equipmentTypes(ADMIN)).isNotEmpty()
                    .noneMatch(type -> type.typeCode().equals("BTEST_OFF"));
            assertThatThrownBy(() -> productService.equipmentTypes(Set.of("OWNER")))
                    .isInstanceOf(BusinessException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM biz_equipment_type WHERE type_code='BTEST_OFF'");
        }
    }

    @Test
    void approvedSensitiveChangesReuseOnboardingTransactionsAndTrustedBuildingScope() {
        DeviceProductContracts.DetailView draft = productService.create(
                productRequest("BTEST_PRODUCT_APPROVED"), 1L, ADMIN);
        SensitiveChangeRecord enabled = executeSensitive(
                "ENABLE_DEVICE_PRODUCT",
                objectMapper.createObjectNode().put("productId", draft.productId()),
                "approved-product-enable");
        assertThat(enabled.status()).isEqualTo(SensitiveChangeStatus.EXECUTED);
        assertThat(enabled.buildingId()).isNull();
        insertPending("BTEST-PENDING-APPROVED", PREFIX + "DEVICE-APPROVED", "DISCOVERED");

        var bind = objectMapper.createObjectNode()
                .put("pendingId", "BTEST-PENDING-APPROVED")
                .put("productId", draft.productId())
                .put("buildingId", "BLD001")
                .put("spaceId", "SPACE001")
                .put("systemGroupId", "GROUP001")
                .put("existingEquipmentId", "EQUIP_WCR_B1");
        bind.putArray("pointBindings").addObject()
                .put("metricCode", "temperature")
                .put("existingPointId", "POINT001");
        JsonNode spoofedBuilding = bind.deepCopy().put("buildingId", "BLD002");
        assertThatThrownBy(() -> sensitiveChangeService.createDraft(
                1L, "BIND_PENDING_DEVICE", spoofedBuilding, "spoofed-building-bind"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(OnboardingErrors.VALIDATION_FAILED));
        SensitiveChangeRecord bound = executeSensitive(
                "BIND_PENDING_DEVICE", bind, "approved-pending-bind");
        assertThat(bound.buildingId()).isEqualTo("BLD001");
        assertThat(bound.commandJson()).doesNotContain("activateOnApprove");

        String identityId = jdbcTemplate.queryForObject(
                "SELECT identity_id FROM biz_device_identity WHERE identity_value=?",
                String.class, PREFIX + "DEVICE-APPROVED");
        SensitiveChangeRecord activated = executeSensitive(
                "ACTIVATE_DEVICE_IDENTITY",
                objectMapper.createObjectNode().put("identityId", identityId),
                "approved-identity-activate");
        assertThat(activated.buildingId()).isEqualTo("BLD001");
        assertThat(activated.impactSummary()).contains("equipmentName=", ";action=ACTIVATE");
        executeSensitive("DEACTIVATE_DEVICE_IDENTITY",
                objectMapper.createObjectNode().put("identityId", identityId),
                "approved-identity-deactivate");
        executeSensitive("DISABLE_DEVICE_PRODUCT",
                objectMapper.createObjectNode().put("productId", draft.productId()),
                "approved-product-disable");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM biz_device_identity WHERE identity_id=?", Integer.class, identityId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM biz_device_product WHERE product_id=?", String.class, draft.productId()))
                .isEqualTo("DISABLED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM biz_onboarding_audit_log
                WHERE action_type IN ('PRODUCT_ENABLE','PENDING_BIND','IDENTITY_ACTIVATE',
                                      'IDENTITY_DEACTIVATE','PRODUCT_DISABLE')
                """, Integer.class)).isEqualTo(5);
    }

    @Test
    void createsEnablesBindsAndActivatesWithoutTouchingHistoricalTelemetry() {
        DeviceProductContracts.DetailView product = createEnabledProduct("BTEST_PRODUCT_1");
        insertPending("BTEST-PENDING-1", PREFIX + "DEVICE-001", "DISCOVERED");
        assertThat(onboardingService.connection("BTEST-PENDING-1", ADMIN).identityStatus()).isEqualTo("UNBOUND");
        assertThatThrownBy(() -> onboardingService.connection("BTEST-PENDING-1", Set.of("OWNER")))
                .isInstanceOf(BusinessException.class);

        DeviceOnboardingContracts.BindResultView bound = onboardingService.bind(
                "BTEST-PENDING-1",
                new DeviceOnboardingContracts.BindRequest(
                        product.productId(), "BLD001", "SPACE001", "GROUP001", null,
                        new DeviceOnboardingContracts.NewEquipmentRequest("BTEST-设备-1", null),
                        List.of(new DeviceOnboardingContracts.PointBindingRequest(
                                "temperature", null, "WCR2_TWin", null,
                                "RULE_WCR_MAIN", "WCR", "MAIN", "ANALOG"))),
                1L,
                ADMIN);

        assertThat(bound.status()).isEqualTo("BOUND");
        var connection = onboardingService.connection("BTEST-PENDING-1", ADMIN);
        assertThat(connection.identityStatus()).isEqualTo("INACTIVE");
        assertThat(connection.equipmentId()).isEqualTo(bound.equipmentId());
        assertThat(connection.productId()).isEqualTo(product.productId());
        assertThat(connection.configEffective()).isTrue();
        assertThat(bound.configEffective()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM biz_device_identity WHERE identity_id = ?",
                Integer.class, bound.identityId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT product_id FROM biz_equipment WHERE equip_id = ?",
                String.class, bound.equipmentId())).isEqualTo(product.productId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_point_alias WHERE source_point_code = ?",
                Long.class, "MAC:BTEST-DEVICE-001:temperature")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_id FROM biz_point_alias WHERE source_point_code = ?",
                String.class, "MAC:BTEST-DEVICE-001:temperature"))
                .isEqualTo("SOURCE_MQTT_FREEZE_V1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_onboarding_audit_log WHERE action_type = 'PENDING_BIND'",
                Long.class)).isEqualTo(1L);

        DeviceOnboardingContracts.IdentityStatusView active = onboardingService.activate(
                bound.identityId(), 1L, ADMIN);

        assertThat(active.configEffective()).isTrue();
        assertThat(onboardingService.connection("BTEST-PENDING-1", ADMIN).identityStatus()).isEqualTo("ACTIVE");
        assertThat(onboardingService.connection("BTEST-PENDING-1", ADMIN).configEffective()).isTrue();
        jdbcTemplate.update("UPDATE biz_device_identity SET status=0 WHERE identity_id=?", bound.identityId());
        assertThat(onboardingService.connection("BTEST-PENDING-1", ADMIN).configEffective()).isFalse();
        jdbcTemplate.update("UPDATE biz_device_identity SET status=1 WHERE identity_id=?", bound.identityId());
        assertThat(identityProvider.find(new DeviceIdentityKey("MAC", PREFIX + "DEVICE-001")))
                .isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_data_point WHERE equip_id = ?",
                Long.class, bound.equipmentId())).isEqualTo(1L);
    }

    @Test
    void automaticallyCreatesTemplatePointsForNewEquipment() {
        DeviceProductContracts.DetailView product = createEnabledProduct("BTEST_PRODUCT_AUTO");
        insertPending("BTEST-PENDING-AUTO", PREFIX + "DEVICE-AUTO", "DISCOVERED");

        DeviceOnboardingContracts.BindResultView bound = onboardingService.bind(
                "BTEST-PENDING-AUTO",
                new DeviceOnboardingContracts.BindRequest(
                        product.productId(), "BLD001", "SPACE001", "GROUP001", null,
                        new DeviceOnboardingContracts.NewEquipmentRequest("BTEST-自动建点设备", null),
                        List.of(), true),
                1L, ADMIN);

        assertThat(bound.pointIds()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT equip_code FROM biz_equipment WHERE equip_id=?", String.class, bound.equipmentId()))
                .isEqualTo("WCR2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT point_code FROM biz_data_point WHERE point_id=?", String.class, bound.pointIds().getFirst()))
                .isEqualTo("WCR2_TWin");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT naming_rule_id FROM biz_data_point WHERE point_id=?", String.class, bound.pointIds().getFirst()))
                .isEqualTo("RULE_WCR_MAIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_point_alias WHERE source_point_code=?", Long.class,
                "MAC:" + PREFIX + "DEVICE-AUTO:temperature")).isEqualTo(1L);
    }

    @Test
    void crossBuildingValidationRollsBackEntireBinding() {
        DeviceProductContracts.DetailView product = createEnabledProduct("BTEST_PRODUCT_2");
        insertPending("BTEST-PENDING-2", PREFIX + "DEVICE-002", "DISCOVERED");

        assertThatThrownBy(() -> onboardingService.bind(
                "BTEST-PENDING-2",
                new DeviceOnboardingContracts.BindRequest(
                        product.productId(), "BLD001", "SPACE002", "GROUP001", null,
                        new DeviceOnboardingContracts.NewEquipmentRequest("BTEST-设备-2", null),
                        List.of(new DeviceOnboardingContracts.PointBindingRequest(
                                "temperature", null, "WCR2_TWin", null,
                                "RULE_WCR_MAIN", "WCR", "MAIN", "ANALOG"))),
                1L,
                ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getErrorCode()).isEqualTo(OnboardingErrors.VALIDATION_FAILED);
                });

        assertThat(pendingMapper.selectById("BTEST-PENDING-2").getStatus()).isEqualTo("DISCOVERED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_device_identity WHERE identity_value = ?",
                Long.class, PREFIX + "DEVICE-002")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_equipment WHERE equip_name = 'BTEST-设备-2'",
                Long.class)).isZero();
    }

    @Test
    void bindsExistingEquipmentAndPointWithoutBackfillingHistoricalProductId() {
        DeviceProductContracts.DetailView product = createEnabledProduct("BTEST_PRODUCT_4");
        insertPending("BTEST-PENDING-4", PREFIX + "DEVICE-004", "DISCOVERED");

        DeviceOnboardingContracts.BindResultView bound = onboardingService.bind(
                "BTEST-PENDING-4",
                new DeviceOnboardingContracts.BindRequest(
                        product.productId(), "BLD001", "SPACE001", "GROUP001", "EQUIP_WCR_B1",
                        null,
                        List.of(new DeviceOnboardingContracts.PointBindingRequest(
                                "temperature", "POINT001", null, null,
                                null, null, null, null))),
                1L,
                ADMIN);

        assertThat(bound.configEffective()).isTrue();
        assertThat(bound.equipmentId()).isEqualTo("EQUIP_WCR_B1");
        assertThat(bound.pointIds()).containsExactly("POINT001");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT product_id FROM biz_equipment WHERE equip_id = 'EQUIP_WCR_B1'",
                String.class)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_point_alias WHERE source_point_code = ? AND point_id = 'POINT001'",
                Long.class, "MAC:BTEST-DEVICE-004:temperature")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_id FROM biz_point_alias WHERE source_point_code = ?",
                String.class, "MAC:BTEST-DEVICE-004:temperature"))
                .isEqualTo("SOURCE_MQTT_FREEZE_V1");
    }

    @Test
    void approvedAutomaticBindingReusesEquipmentAndRecoversAfterCommitRefreshFailure() {
        var draftProduct = productService.create(new DeviceProductContracts.CreateRequest(
                "BTEST_AUTO_REUSE", "采集设备", null, null, "WCR", "HVAC_DEVICE_V1", "MAC", List.of(
                    new DeviceProductContracts.PointTemplateRequest(
                            "counter", "累计量", "TWin", "kWh", null, null, false, true, 1, true, "ACCUMULATE"),
                    new DeviceProductContracts.PointTemplateRequest(
                            "state", "状态", "RUN", "1", null, null, false, true, 2, true, "DIGITAL"))),
                1L, ADMIN);
        assertThat(draftProduct.points()).extracting(DeviceProductContracts.PointTemplateView::dataType)
                .containsExactly("ACCUMULATE", "DIGITAL");
        productService.enable(draftProduct.productId(), 1L, ADMIN);
        insertAutoEquipment();

        jdbcTemplate.update("""
                INSERT INTO biz_data_point
                  (point_id,point_code,point_name,building_id,system_group_id,equip_id,naming_rule_id,
                   family_code,component_code,suffix_code,data_type,unit,is_for_calc,status,del_flag)
                VALUES ('BTEST-AUTO-POINT','WCR99_TWin','已有累计量','BLD001','GROUP001','BTEST-AUTO-EQUIP',
                        'RULE_WCR_MAIN','WCR','MAIN','TWin','ACCUMULATE','kWh',0,'ONLINE',0)
                """);
        insertPending("BTEST-AUTO-PENDING", "BTEST-AUTO-MAC", "DISCOVERED");
        var bind = automaticReuseCommand(draftProduct.productId(), "BTEST-AUTO-PENDING").put("activateOnApprove", true);
        jdbcTemplate.update("UPDATE biz_data_point SET unit='kW' WHERE point_id='BTEST-AUTO-POINT'");
        assertThatThrownBy(() -> executeSensitive("BIND_PENDING_DEVICE", bind, "BTEST-AUTO-POINT-CONFLICT"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(OnboardingErrors.AUTO_POINT_MAPPING_REQUIRED));
        assertThat(pendingMapper.selectById("BTEST-AUTO-PENDING").getStatus()).isEqualTo("DISCOVERED");
        jdbcTemplate.update("UPDATE biz_data_point SET unit='kWh' WHERE point_id='BTEST-AUTO-POINT'");
        doThrow(new IllegalStateException("模拟测点快照不可用")).when(pointProvider).refreshAll();
        try {
            var executed = executeSensitive("BIND_PENDING_DEVICE", bind, "BTEST-AUTO-APPROVAL");
            assertThat(executed.status()).isEqualTo(SensitiveChangeStatus.EXECUTED);
            assertThat(executed.commandJson()).contains("\"activateOnApprove\":true");
            var pending = onboardingService.connection("BTEST-AUTO-PENDING", ADMIN);
            assertThat(pending.identityStatus()).isEqualTo("ACTIVE");
            assertThat(pending.configEffective()).isFalse();
            assertThat(onboardingService.refreshEffective("BTEST-AUTO-PENDING", ADMIN).configEffective()).isFalse();
        } finally {
            doCallRealMethod().when(pointProvider).refreshAll();
        }

        var effective = onboardingService.refreshEffective("BTEST-AUTO-PENDING", ADMIN);
        assertThat(effective.configEffective()).isTrue();
        assertThat(onboardingService.refreshEffective("BTEST-AUTO-PENDING", ADMIN)).isEqualTo(effective);
        assertThat(effective.equipmentId()).isEqualTo("BTEST-AUTO-EQUIP");
        assertThat(effective.productId()).isEqualTo(draftProduct.productId());
        assertThat(jdbcTemplate.queryForList(
                "SELECT data_type FROM biz_data_point WHERE equip_id=?", String.class, effective.equipmentId()))
                .containsExactlyInAnyOrder("ACCUMULATE", "DIGITAL");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT point_id FROM biz_point_alias WHERE source_point_code='MAC:BTEST-AUTO-MAC:counter'",
                String.class)).isEqualTo("BTEST-AUTO-POINT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_device_identity WHERE equip_id=?", Integer.class, effective.equipmentId()))
                .isEqualTo(1);
    }

    @Test
    void concurrentAutomaticBindingsCannotClaimTheSameEquipment() throws Exception {
        var product = createEnabledProduct("BTEST_CONCURRENT_REUSE");
        insertAutoEquipment();
        insertPending("BTEST-REUSE-A", "BTEST-REUSE-A", "DISCOVERED");
        insertPending("BTEST-REUSE-B", "BTEST-REUSE-B", "DISCOVERED");
        var binding = new DeviceOnboardingContracts.BindRequest(product.productId(),
                "BLD001", "SPACE001", "GROUP001", "BTEST-AUTO-EQUIP", null, List.of(), true);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var one = workers.submit(() -> attemptAutomaticBind("BTEST-REUSE-A", binding, start));
            var two = workers.submit(() -> attemptAutomaticBind("BTEST-REUSE-B", binding, start));
            start.countDown();
            assertThat(List.of(one.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    two.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("BOUND", OnboardingErrors.VALIDATION_FAILED);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_data_point WHERE equip_id='BTEST-AUTO-EQUIP'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_device_identity WHERE equip_id='BTEST-AUTO-EQUIP'", Integer.class)).isEqualTo(1);
    }

    private String attemptAutomaticBind(String pendingId, DeviceOnboardingContracts.BindRequest binding,
            java.util.concurrent.CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return onboardingService.bind(pendingId, binding, 1L, ADMIN).status();
        } catch (BusinessException failure) {
            return failure.getErrorCode();
        }
    }

    private void insertAutoEquipment() {
        jdbcTemplate.update("""
                INSERT INTO biz_equipment
                  (equip_id,equip_code,equip_name,type_code,equip_category,system_group_id,building_id,space_id,del_flag)
                VALUES ('BTEST-AUTO-EQUIP','WCR99','BTEST-待接入档案','WCR','CHILLER','GROUP001','BLD001','SPACE001',0)
                """);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode automaticReuseCommand(String productId, String pendingId) {
        var command = objectMapper.createObjectNode().put("pendingId", pendingId).put("productId", productId)
                .put("buildingId", "BLD001").put("spaceId", "SPACE001").put("systemGroupId", "GROUP001")
                .put("existingEquipmentId", "BTEST-AUTO-EQUIP").put("autoCreatePoints", true);
        command.putArray("pointBindings");
        return command;
    }

    @Test
    void filtersUnifiedPendingListBeforeCountingAndPaging() {
        insertPending("BTEST-LIST-A", "BTEST-LIST-A", "DISCOVERED");
        insertPending("BTEST-LIST-B", "BTEST-LIST-B", "DISCOVERED");
        insertPending("BTEST-LIST-C", "BTEST-LIST-C", "DISCOVERED");
        jdbcTemplate.update("UPDATE biz_pending_device SET identity_type='DAIKIN_UNIT' WHERE pending_id='BTEST-LIST-A'");

        var all = onboardingService.listPending(1, 1, null, "BTEST-LIST-", null,
                DeviceOnboardingContracts.PendingSourceScope.ALL, ADMIN);
        var daikin = onboardingService.listPending(1, 1, null, "BTEST-LIST-", null,
                DeviceOnboardingContracts.PendingSourceScope.DAIKIN, ADMIN);
        var standard = onboardingService.listPending(2, 1, null, "BTEST-LIST-", null,
                DeviceOnboardingContracts.PendingSourceScope.STANDARD, ADMIN);

        assertThat(all.total()).isEqualTo(3);
        assertThat(daikin.total()).isEqualTo(1);
        assertThat(daikin.items()).extracting(DeviceOnboardingContracts.PendingListItemView::pendingId)
                .containsExactly("BTEST-LIST-A");
        assertThat(standard.total()).isEqualTo(2);
        assertThat(standard.items()).extracting(DeviceOnboardingContracts.PendingListItemView::pendingId)
                .containsExactly("BTEST-LIST-C");
        assertThatThrownBy(() -> onboardingService.refreshEffective("BTEST-LIST-A", ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(OnboardingErrors.STATE_CONFLICT));
    }

    @Test
    void ignoresAndRestoresButRejectsBoundStateMutationAndNonAdminAccess() {
        insertPending("BTEST-PENDING-3", PREFIX + "DEVICE-003", "DISCOVERED");

        DeviceOnboardingContracts.PendingDetailView ignored = onboardingService.updatePendingStatus(
                "BTEST-PENDING-3",
                new DeviceOnboardingContracts.PendingStatusRequest("IGNORED", "暂不接入"),
                1L,
                ADMIN);
        assertThat(ignored.status()).isEqualTo("IGNORED");
        assertThat(onboardingService.listPending(1, 20, null, "BTEST-DEVICE-003", null, ADMIN).items())
                .singleElement().satisfies(item -> assertThat(item.allowedActions()).containsExactly("RESTORE"));

        DeviceOnboardingContracts.PendingDetailView restored = onboardingService.updatePendingStatus(
                "BTEST-PENDING-3",
                new DeviceOnboardingContracts.PendingStatusRequest("DISCOVERED", null),
                1L,
                ADMIN);
        assertThat(restored.status()).isEqualTo("DISCOVERED");
        assertThat(onboardingService.listPending(1, 20, null, "BTEST-DEVICE-003", null, ADMIN).items())
                .singleElement().satisfies(item -> assertThat(item.allowedActions()).containsExactly("IGNORE", "BIND"));
        assertThatThrownBy(() -> onboardingService.pendingDetail(
                "BTEST-PENDING-3", Set.of("ENERGY_MANAGER")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(OnboardingErrors.FORBIDDEN));
    }

    @Test
    void managesDraftCopyEnableDisableAndRejectsDestructiveEnabledUpdate() {
        DeviceProductContracts.DetailView draft = productService.create(
                productRequest("BTEST_PRODUCT_3"), 1L, ADMIN);
        DeviceProductContracts.DetailView updated = productService.update(
                draft.productId(),
                new DeviceProductContracts.UpdateRequest(
                        "B 测试产品更新", "测试厂商", "T-2", "WCR",
                        "HVAC_DEVICE_V1", "MAC", productPoints()),
                1L,
                ADMIN);
        DeviceProductContracts.DetailView copy = productService.copy(
                updated.productId(),
                new DeviceProductContracts.CopyRequest("BTEST_PRODUCT_3_COPY", "B 测试产品副本"),
                1L,
                ADMIN);

        assertThat(updated.productName()).isEqualTo("B 测试产品更新");
        assertThat(copy.status()).isEqualTo("DRAFT");
        assertThat(copy.points()).hasSize(1);
        DeviceProductContracts.DetailView enabled = productService.enable(updated.productId(), 1L, ADMIN);
        assertThat(enabled.status()).isEqualTo("ENABLED");
        assertThatThrownBy(() -> productService.update(
                enabled.productId(),
                new DeviceProductContracts.UpdateRequest(
                        "破坏性更新", null, null, "WCR", "HVAC_DEVICE_V1", "MAC", productPoints()),
                1L,
                ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(OnboardingErrors.STATE_CONFLICT));
        assertThat(productService.disable(enabled.productId(), 1L, ADMIN).status())
                .isEqualTo("DISABLED");
        assertThat(productService.list(1, 20, null, "BTEST_PRODUCT_3", ADMIN).items())
                .hasSize(2);
    }

    @Test
    void filtersCompatibleProductsBeforePaginationAndListsEnabledNamingRules() {
        var matched = createEnabledProduct("BTEST_MATCHED");
        productService.create(new DeviceProductContracts.CreateRequest("BTEST_OTHER", "other", null, null,
                "WCR", "OTHER_PROFILE", "SN", productPoints()), 1L, ADMIN);
        var page = productService.list(1, 1, "ENABLED", "BTEST", "hvac_device_v1", "mac", ADMIN);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(DeviceProductContracts.ListItemView::productId)
                .containsExactly(matched.productId());
        assertThat(productService.list(1, 20, null, "BTEST", "OTHER_PROFILE", "MAC", ADMIN).items()).isEmpty();
        assertThat(onboardingService.namingRules(ADMIN)).anySatisfy(rule -> {
            assertThat(rule.ruleId()).isEqualTo("RULE_WCR_MAIN");
            assertThat(rule.familyCode()).isEqualTo("WCR");
            assertThat(rule.pattern()).isNotBlank();
        });
        assertThatThrownBy(() -> onboardingService.namingRules(Set.of("OWNER")))
                .isInstanceOf(BusinessException.class);
    }

    private DeviceProductContracts.DetailView createEnabledProduct(String productCode) {
        DeviceProductContracts.DetailView draft = productService.create(
                productRequest(productCode),
                1L,
                ADMIN);
        return productService.enable(draft.productId(), 1L, ADMIN);
    }

    private DeviceProductContracts.CreateRequest productRequest(String productCode) {
        return new DeviceProductContracts.CreateRequest(
                productCode, "B 测试产品", "测试厂商", "T-1", "WCR",
                "HVAC_DEVICE_V1", "MAC", productPoints());
    }

    private List<DeviceProductContracts.PointTemplateRequest> productPoints() {
        return List.of(new DeviceProductContracts.PointTemplateRequest(
                "temperature", "进水温度", "TWin", "℃", null, null,
                false, true, 1, true));
    }

    private void insertPending(String pendingId, String identityValue, String status) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 17, 12, 0);
        BizPendingDevice pending = new BizPendingDevice();
        pending.setPendingId(pendingId);
        pending.setIdentityType("MAC");
        pending.setIdentityValue(identityValue);
        pending.setProfileCode("HVAC_DEVICE_V1");
        pending.setLastProfileVersion(1);
        pending.setFirstSeenTime(now);
        pending.setLastSeenTime(now);
        pending.setReportCount(1L);
        pending.setLatestEventTime(now);
        pending.setLatestTimeSource("DEVICE_REPORTED");
        pending.setLatestMetricsJson("[{\"code\":\"temperature\",\"value\":20.5,\"unit\":\"℃\"}]");
        pending.setSampleTruncated(0);
        pending.setStatus(status);
        pending.setCreateTime(now);
        pending.setUpdateTime(now);
        pendingMapper.insert(pending);
    }

    private SensitiveChangeRecord executeSensitive(
            String operationCode, JsonNode command, String idempotencyKey) {
        SensitiveChangeRecord draft = sensitiveChangeService.createDraft(
                1L, operationCode, command, idempotencyKey);
        sensitiveChangeService.submit(1L, draft.requestId());
        sensitiveChangeService.approve(1L, draft.requestId(), "研发阶段完整两步审批");
        return sensitiveChangeService.execute(1L, draft.requestId()).change();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void rejectsMissingSourceBeforeCreatingApprovalDraft() {
        var product = createEnabledProduct("BTEST_SOURCE_REQUIRED");
        insertPending("BTEST-SOURCE", "BTEST-SOURCE-DEVICE", "DISCOVERED");
        jdbcTemplate.update("UPDATE biz_data_source SET status='DISABLED' WHERE building_id='BLD001'");
        var bind = objectMapper.createObjectNode()
                .put("pendingId", "BTEST-SOURCE").put("productId", product.productId())
                .put("buildingId", "BLD001").put("spaceId", "SPACE001")
                .put("systemGroupId", "GROUP001").put("existingEquipmentId", "EQUIP_WCR_B1");
        bind.putArray("pointBindings").addObject()
                .put("metricCode", "temperature").put("existingPointId", "POINT001");
        int before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_sensitive_change_request", Integer.class);
        assertThatThrownBy(() -> sensitiveChangeService.createDraft(1L, "BIND_PENDING_DEVICE", bind, "missing-source"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OnboardingErrors.SOURCE_REQUIRED));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_sensitive_change_request", Integer.class)).isEqualTo(before);
        assertThat(pendingMapper.selectById("BTEST-SOURCE").getStatus()).isEqualTo("DISCOVERED");
    }

    private void grant(BackendDuty duty) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        jdbcTemplate.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID().toString().replace("-", ""), 1L, duty.name(),
                Timestamp.valueOf(now), 1L, Timestamp.valueOf(now));
    }
}
