package com.platform.iot.onboarding.api;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.identity.MySqlDeviceIdentityProvider;
import com.platform.iot.onboarding.DeviceOnboardingService;
import com.platform.iot.onboarding.DeviceProductService;
import com.platform.iot.onboarding.OnboardingErrors;
import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DeviceOnboardingServiceIntegrationTest {
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    private static final String PREFIX = "BTEST-";

    @Autowired private DeviceProductService productService;
    @Autowired private DeviceOnboardingService onboardingService;
    @Autowired private BizPendingDeviceMapper pendingMapper;
    @Autowired private MySqlDeviceIdentityProvider identityProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanCreatedRecords() {
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
    void createsEnablesBindsAndActivatesWithoutTouchingHistoricalTelemetry() {
        DeviceProductContracts.DetailView product = createEnabledProduct("BTEST_PRODUCT_1");
        insertPending("BTEST-PENDING-1", PREFIX + "DEVICE-001", "DISCOVERED");

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
        assertThat(identityProvider.find(new DeviceIdentityKey("MAC", PREFIX + "DEVICE-001")))
                .isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_data_point WHERE equip_id = ?",
                Long.class, bound.equipmentId())).isEqualTo(1L);
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
    void ignoresAndRestoresButRejectsBoundStateMutationAndNonAdminAccess() {
        insertPending("BTEST-PENDING-3", PREFIX + "DEVICE-003", "DISCOVERED");

        DeviceOnboardingContracts.PendingDetailView ignored = onboardingService.updatePendingStatus(
                "BTEST-PENDING-3",
                new DeviceOnboardingContracts.PendingStatusRequest("IGNORED", "暂不接入"),
                1L,
                ADMIN);
        assertThat(ignored.status()).isEqualTo("IGNORED");

        DeviceOnboardingContracts.PendingDetailView restored = onboardingService.updatePendingStatus(
                "BTEST-PENDING-3",
                new DeviceOnboardingContracts.PendingStatusRequest("DISCOVERED", null),
                1L,
                ADMIN);
        assertThat(restored.status()).isEqualTo("DISCOVERED");
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
}
