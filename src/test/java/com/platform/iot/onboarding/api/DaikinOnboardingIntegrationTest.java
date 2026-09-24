package com.platform.iot.onboarding.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import com.platform.iot.identity.MySqlDeviceIdentityProvider;
import com.platform.iot.onboarding.DeviceOnboardingService;
import com.platform.iot.onboarding.DeviceProductService;
import com.platform.iot.onboarding.ScopedDeviceOnboardingService;
import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2 软件验收：厂家清单为隔离输入，不连接真实 API、Broker 或 TDengine。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DaikinOnboardingIntegrationTest {
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    private static final Set<String> OPS = Set.of("BUILDING_OWNER");
    private static final Instant AT = Instant.parse("2026-09-01T01:00:00Z");
    @Autowired DaikinDirectoryService directory;
    @Autowired DeviceProductService products;
    @Autowired DeviceOnboardingService onboarding;
    @Autowired ScopedDeviceOnboardingService scoped;
    @Autowired SensitiveChangeService changes;
    @Autowired BizPendingDeviceMapper pendingMapper;
    @Autowired BuildingScopeService buildingScope;
    @Autowired MySqlDeviceIdentityProvider identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    private String source;
    private String numericSource;

    @BeforeEach
    void setup() {
        source = "DTEST-" + UUID.randomUUID();
        jdbc.update("INSERT INTO biz_equipment_type VALUES ('DTEST_ODU','测试外机','ODU','OUTDOOR_UNIT','TEST',1)");
        jdbc.update("INSERT INTO biz_equipment_type VALUES ('DTEST_IDU','测试内机','IDU','INDOOR_UNIT','TEST',1)");
        jdbc.update("INSERT INTO sys_user(id,username,password,nickname,status,del_flag) VALUES (4242,'dtest_ops','unused','test',1,0)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (4242,10)");
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (4242,'BLD001')");
        jdbc.update("INSERT INTO sys_menu(id,parent_id,menu_name,menu_type,path,visible,status,sort_order) VALUES (98765,0,'测试接入','C','/operations/devices/pendingDevices',1,1,1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (10,98765)");
        grant(1L, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grant(1L, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        grant(4242L, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        buildingScope.evict(4242L);
        directory.registerSource(source, 1L, ADMIN);
    }

    @AfterEach
    void cleanup() {
        if (numericSource != null) jdbc.update("DELETE FROM biz_data_source WHERE source_id=?", numericSource);
        var pending = jdbc.queryForList("SELECT pending_id FROM biz_daikin_directory WHERE source_id=?", String.class, source);
        var identityIds = pending.stream().map(id -> pendingMapper.selectById(id).getBoundIdentityId()).filter(java.util.Objects::nonNull).toList();
        var auditTargets = new java.util.ArrayList<>(pending);
        auditTargets.addAll(identityIds);
        auditTargets.addAll(jdbc.queryForList("SELECT equip_id FROM biz_equipment WHERE equip_name LIKE 'DTEST-%'", String.class));
        auditTargets.addAll(jdbc.queryForList("SELECT product_id FROM biz_device_product WHERE product_code LIKE 'DTEST_%'", String.class));
        auditTargets.forEach(id -> jdbc.update("DELETE FROM biz_onboarding_audit_log WHERE object_id=?", id));
        jdbc.update("DELETE FROM biz_daikin_catalog_sync WHERE source_id=?", source);
        jdbc.update("DELETE FROM biz_daikin_directory WHERE source_id=?", source);
        pending.forEach(id -> jdbc.update("DELETE FROM biz_pending_device WHERE pending_id=?", id));
        identityIds.forEach(id -> jdbc.update("DELETE FROM biz_device_identity WHERE identity_id=?", id));
        jdbc.update("DELETE FROM biz_daikin_project_mapping_version WHERE source_id=?", source);
        jdbc.update("DELETE FROM biz_daikin_project_mapping WHERE source_id=?", source);
        jdbc.update("DELETE FROM biz_daikin_source WHERE source_id=?", source);
        jdbc.update("DELETE FROM biz_equipment WHERE equip_name LIKE 'DTEST-%'");
        jdbc.update("DELETE FROM biz_device_product WHERE product_code LIKE 'DTEST_%'");
        jdbc.update("DELETE FROM biz_equipment_type WHERE type_code IN ('DTEST_ODU','DTEST_IDU')");
        jdbc.update("DELETE FROM sys_sensitive_change_request WHERE idempotency_key LIKE 'DTEST-%'");
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE assignment_id LIKE 'DTEST-%'");
        jdbc.update("DELETE FROM sys_role_menu WHERE menu_id=98765");
        jdbc.update("DELETE FROM sys_menu WHERE id=98765");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=4242");
        jdbc.update("DELETE FROM sys_user_building WHERE user_id=4242");
        jdbc.update("DELETE FROM sys_user WHERE id=4242");
        buildingScope.evict(4242L);
        identities.refreshAll();
    }

    @Test
    void approvedTypedBindingCreatesDisabledIdentityWithoutNumericPointsAndUsesExistingActivation() {
        String pending = discover("site1", "unit1", "BLD001");
        String product = enabledProduct();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point", Integer.class);
        assertThat(scoped.list(4242L, OPS, 1, 20, null).items()).filteredOn(item -> item.pendingId().equals(pending))
                .extracting(DeviceOnboardingContracts.PendingListItemView::identityStatus).containsExactly("UNBOUND");
        var application = scoped.apply(1L, ADMIN, pending, binding(product, "BLD001"), "DTEST-bind");
        assertThat(application.status()).isEqualTo("PENDING_REVIEW");
        assertThat(pendingMapper.selectById(pending).getBoundIdentityId()).isNull();
        changes.approve(1L, application.requestId(), "隔离测试审核");
        assertThat(changes.execute(1L, application.requestId()).change().status().name()).isEqualTo("EXECUTED");
        var connection = onboarding.connection(pending, ADMIN);
        assertThat(connection.identityStatus()).isEqualTo("INACTIVE");
        assertThat(scoped.list(4242L, OPS, 1, 20, null).items()).filteredOn(item -> item.pendingId().equals(pending))
                .extracting(DeviceOnboardingContracts.PendingListItemView::identityStatus).containsExactly("INACTIVE");
        assertThat(onboarding.listPending(1, 20, "BOUND", null, null, ADMIN).items())
                .filteredOn(item -> item.pendingId().equals(pending))
                .extracting(DeviceOnboardingContracts.PendingListItemView::identityStatus).containsExactly("INACTIVE");
        assertThat(connection.configEffective()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point", Integer.class)).isEqualTo(before);
        assertThatThrownBy(() -> directory.mapProject(source, "site1", "BLD002", 1L, ADMIN)).isInstanceOf(BusinessException.class);
        var activation = scoped.requestIdentityStatus(4242L, OPS, pending, "ACTIVE", "DTEST-activate");
        assertThat(activation.status()).isEqualTo("PENDING_REVIEW");
        assertThat(scoped.requestIdentityStatus(4242L, OPS, pending, "ACTIVE", "DTEST-activate").requestId())
                .isEqualTo(activation.requestId());
        assertThat(onboarding.connection(pending, ADMIN).identityStatus()).isEqualTo("INACTIVE");
        changes.approve(1L, activation.requestId(), "隔离测试审核");
        changes.execute(1L, activation.requestId());
        assertThat(onboarding.connection(pending, ADMIN).identityStatus()).isEqualTo("ACTIVE");
        assertThat(scoped.list(4242L, OPS, 1, 20, null).items()).filteredOn(item -> item.pendingId().equals(pending))
                .extracting(DeviceOnboardingContracts.PendingListItemView::identityStatus).containsExactly("ACTIVE");
        assertThat(onboarding.connection(pending, ADMIN).configEffective()).isTrue();
        assertThatThrownBy(() -> scoped.requestIdentityStatus(4242L, OPS, pending, "ON", "DTEST-invalid"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsWrongBuildingWrongKindAndLegacyEmptyPointsWithoutPartialAssets() {
        String pending = discover("site1", "unit1", "BLD001");
        String product = enabledProduct();
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment", Long.class);
        assertThatThrownBy(() -> onboarding.bindTyped(pending, binding(product, "BLD002"), 1L, ADMIN))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onboarding.bind(pending, binding(product, "BLD001").asBinding(), 1L, ADMIN))
                .isInstanceOf(BusinessException.class);
        var inner = products.createTypedState(new DeviceProductContracts.TypedStateRequest(
                "DTEST_IN", "测试内机状态", null, null, "DTEST_IDU", "DAIKIN_INDOOR_V2"), 1L, ADMIN);
        products.enable(inner.productId(), 1L, ADMIN);
        assertThatThrownBy(() -> onboarding.bindTyped(pending, binding(inner.productId(), "BLD001"), 1L, ADMIN))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment", Long.class)).isEqualTo(before);
        assertThatThrownBy(() -> products.createTypedState(new DeviceProductContracts.TypedStateRequest(
                "DTEST_WRONG", "测试", null, null, "WCR", "DAIKIN_OUTDOOR_V2"), 1L, ADMIN))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> products.create(new DeviceProductContracts.CreateRequest("DTEST_EMPTY", "测试", null,
                null, "WCR", "HVAC_DEVICE_V1", "MAC", List.of()), 1L, ADMIN)).isInstanceOf(BusinessException.class);
    }

    @Test
    void associatesExistingEquipmentProductAndRejectsSecondManufacturerIdentity() {
        String first = discover("site1", "unit1", "BLD001");
        String second = discover("site1", "unit2", "BLD001");
        String product = enabledProduct();
        jdbc.update("INSERT INTO biz_equipment(equip_id,equip_code,equip_name,type_code,equip_category,system_group_id,building_id,space_id,del_flag) "
                + "VALUES ('DTEST-EXISTING','ODU90','DTEST-已有外机','DTEST_ODU','OUTDOOR_UNIT','GROUP001','BLD001','SPACE001',0)");
        var request = new DeviceOnboardingContracts.TypedBindRequest(product, "BLD001", "SPACE001", "GROUP001", "DTEST-EXISTING", null);
        var result = onboarding.bindTyped(first, request, 1L, ADMIN);
        assertThat(result.pointIds()).isEmpty();
        assertThat(onboarding.connection(first, ADMIN).productId()).isEqualTo(product);
        assertThatThrownBy(() -> onboarding.bindTyped(second, request, 1L, ADMIN)).isInstanceOf(BusinessException.class);
        assertThat(pendingMapper.selectById(second).getStatus()).isEqualTo("DISCOVERED");
    }

    @Test
    void scopesRowsTotalsDetailsAndMenuWithoutExposingUnmappedProjects() {
        String visible = discover("site1", "unit1", "BLD001");
        String other = discover("site2", "unit2", "BLD002");
        String unknown = discover("site3", "unit3", null);
        var page = scoped.list(4242L, OPS, 1, 1, null);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(DeviceOnboardingContracts.PendingListItemView::pendingId).containsExactly(visible);
        assertThat(scoped.directoryDetail(4242L, OPS, visible).directory().buildingId()).isEqualTo("BLD001");
        String product = enabledProduct();
        assertThat(scoped.product(4242L, OPS, visible, product).productId()).isEqualTo(product);
        numericSource = "DHT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.update("INSERT INTO biz_data_source(source_id,source_code,source_name,building_id,source_category,transport_type,status) "
                + "VALUES (?,?,'测试HTTP来源','BLD001','DEVICE_ACCESS','HTTP','ENABLED')", numericSource,
                "DTEST_HTTP_" + numericSource.substring(3));
        assertThat(scoped.numericSources(4242L, OPS, visible))
                .extracting(ScopedDeviceOnboardingService.NumericSource::sourceId).contains(numericSource);
        var unselected = scoped.bindingOptions(4242L, OPS, visible, 1, 20, "SPACE001", "GROUP001", null);
        assertThat(unselected.buildingId()).isEqualTo("BLD001");
        assertThat(unselected.equipmentTotal()).isZero();
        jdbc.update("INSERT INTO biz_equipment(equip_id,equip_code,equip_name,type_code,equip_category,system_group_id,building_id,space_id,del_flag) "
                + "VALUES ('DTEST-CANDIDATE-ODU','DTEST-ODU','DTEST-候选外机','DTEST_ODU','OUTDOOR_UNIT','GROUP001','BLD001','SPACE001',0)");
        jdbc.update("INSERT INTO biz_equipment(equip_id,equip_code,equip_name,type_code,equip_category,system_group_id,building_id,space_id,del_flag) "
                + "VALUES ('DTEST-CANDIDATE-IDU','DTEST-IDU','DTEST-非外机设备','DTEST_IDU','INDOOR_UNIT','GROUP001','BLD001','SPACE001',0)");
        var candidates = scoped.bindingOptions(4242L, OPS, visible, 1, 20, "SPACE001", "GROUP001", product);
        assertThat(candidates.equipmentTotal()).isEqualTo(1);
        assertThat(candidates.equipment()).extracting(ScopedDeviceOnboardingService.EquipmentOption::equipmentId)
                .containsExactly("DTEST-CANDIDATE-ODU");
        var wrongTarget = new DeviceOnboardingContracts.TypedBindRequest(product, "BLD001", "SPACE001", "GROUP001",
                "DTEST-CANDIDATE-IDU", null);
        assertThatThrownBy(() -> scoped.apply(4242L, OPS, visible, wrongTarget, "DTEST-wrong-type"))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_sensitive_change_request WHERE idempotency_key='DTEST-wrong-type'", Integer.class))
                .isZero();
        for (String denied : List.of(other, unknown, "not-existing")) {
            assertThatThrownBy(() -> scoped.product(4242L, OPS, denied, product)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> scoped.numericSources(4242L, OPS, denied)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> scoped.bindingOptions(4242L, OPS, denied, 1, 20, null, null, product)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> scoped.requestIdentityStatus(4242L, OPS, denied, "ACTIVE", "DTEST-denied"))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> scoped.detail(4242L, OPS, denied)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(403));
        }
        assertThat(scoped.list(1L, ADMIN, 1, 20, null).total()).isEqualTo(3);
        jdbc.update("DELETE FROM sys_role_menu WHERE menu_id=98765");
        assertThatThrownBy(() -> scoped.detail(4242L, OPS, visible)).isInstanceOf(BusinessException.class);
    }

    @Test
    void batchReportsPartialFailureAndReusesStableApplicationWithoutBypassingApproval() {
        String visible = discover("site1", "unit1", "BLD001");
        String other = discover("site2", "unit2", "BLD002");
        String product = enabledProduct();
        var requests = List.of(new ScopedDeviceOnboardingService.BindingItem(visible, binding(product, "BLD001"), "DTEST-ok"),
                new ScopedDeviceOnboardingService.BindingItem(other, binding(product, "BLD002"), "DTEST-denied"));
        var result = scoped.applyBatch(4242L, OPS, requests);
        assertThat(result).extracting(ScopedDeviceOnboardingService.BindingApplication::status).containsExactly("PENDING_REVIEW", "FAILED");
        assertThat(scoped.applyBatch(4242L, OPS, requests).getFirst().requestId()).isEqualTo(result.getFirst().requestId());
        assertThat(pendingMapper.selectById(visible).getBoundIdentityId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_sensitive_change_request WHERE idempotency_key='DTEST-ok'", Integer.class)).isEqualTo(1);
        var changed = new DeviceOnboardingContracts.TypedBindRequest(product, "BLD001", "SPACE001", "GROUP001", null,
                new DeviceOnboardingContracts.NewEquipmentRequest("DTEST-不同申请", null));
        assertThat(scoped.applyBatch(4242L, OPS, List.of(new ScopedDeviceOnboardingService.BindingItem(visible, changed, "DTEST-ok")))
                .getFirst().status()).isEqualTo("CONFLICT");
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE user_id=4242");
        assertThatThrownBy(() -> scoped.applyBatch(4242L, OPS, requests)).isInstanceOf(BusinessException.class);
    }

    @Test
    void legacySampleCleanupNeverRemovesDirectoryCandidates() {
        String pending = discover("site1", "unit1", null);
        var cutoff = LocalDateTime.of(2027, 1, 1, 0, 0);
        assertThat(pendingMapper.selectExpiredEligibleIds(cutoff, 100)).doesNotContain(pending);
        assertThat(pendingMapper.deleteExpiredEligibleByIds(List.of(pending), cutoff)).isZero();
        assertThat(directory.detail(pending).unitId()).isEqualTo("unit1");
    }

    @Test
    void newHttpContractsRejectAnonymousAndInvalidBindingsAndDescribeIndependentSchemas() throws Exception {
        mvc.perform(get("/v1/operations/device-onboarding/pending")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ONBOARDING_UNAUTHORIZED"));
        String login = mvc.perform(post("/auth/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"123456\"}")).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(login).path("data").path("token").asText();
        mvc.perform(post("/v1/daikin/directory/sources").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"sourceId\":\" invalid \",\"sourceName\":\"创新港大金空调\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("DAIKIN_DIRECTORY_VALIDATION_FAILED"));
        mvc.perform(post("/v1/operations/device-onboarding/pending/none/binding-requests")
                .header("Authorization", "Bearer " + token).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("ONBOARDING_VALIDATION_FAILED"));
        var api = mapper.readTree(mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(api.path("paths").has("/v1/operations/device-onboarding/pending/{pendingId}/binding-requests")).isTrue();
        assertThat(api.path("paths").has("/v1/device-products/typed-state")).isTrue();
        assertThat(api.path("components").path("schemas").has("ScopedOnboardingBindingRequest")).isTrue();
    }

    private String discover(String site, String unit, String building) {
        if (building != null) directory.mapProject(source, site, building, 1L, ADMIN);
        // 每次提交的确是此测试来源的完整目录，不将逐设备片段误称为完整同步。
        var existing = jdbc.query("SELECT site_id,unit_id FROM biz_daikin_directory WHERE source_id=?",
                (rs, row) -> observation(rs.getString(1), rs.getString(2)), source);
        var all = new java.util.ArrayList<>(existing);
        all.add(observation(site, unit));
        directory.acceptCompleteCatalog(source, DaikinDeviceKey.Kind.OUTDOOR, AT.plusSeconds(all.size()), all);
        return jdbc.queryForObject("SELECT pending_id FROM biz_daikin_directory WHERE source_id=? AND unit_id=?", String.class, source, unit);
    }

    private DaikinDeviceObservation observation(String site, String unit) {
        return new DaikinDeviceObservation(new DaikinDeviceKey(source, site, "lc1", DaikinDeviceKey.Kind.OUTDOOR, unit),
                "equip-" + unit, "测试项目", "测试外机", AT, null, Map.of());
    }

    private String enabledProduct() {
        var product = products.createTypedState(new DeviceProductContracts.TypedStateRequest("DTEST_OUT", "测试状态产品",
                null, null, "DTEST_ODU", "DAIKIN_OUTDOOR_V2"), 1L, ADMIN);
        execute("ENABLE_DEVICE_PRODUCT", Map.of("productId", product.productId()), "DTEST-product");
        return product.productId();
    }

    private DeviceOnboardingContracts.TypedBindRequest binding(String product, String building) {
        boolean first = building.equals("BLD001");
        return new DeviceOnboardingContracts.TypedBindRequest(product, building, first ? "SPACE001" : "SPACE002",
                first ? "GROUP001" : "GROUP002", null, new DeviceOnboardingContracts.NewEquipmentRequest("DTEST-外机", null));
    }

    private void execute(String code, Object command, String key) {
        var draft = changes.createDraft(1L, code, mapper.valueToTree(command), key);
        changes.submit(1L, draft.requestId());
        changes.approve(1L, draft.requestId(), "隔离测试审核");
        assertThat(changes.execute(1L, draft.requestId()).change().status().name()).isEqualTo("EXECUTED");
    }

    private void grant(long userId, BackendDuty duty) {
        jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by,created_at) VALUES (?,?,?,'ACTIVE',?,1,?)",
                "DTEST-" + UUID.randomUUID().toString().substring(0, 20), userId, duty.name(), LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
    }
}
