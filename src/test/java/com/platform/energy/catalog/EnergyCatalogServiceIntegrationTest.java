package com.platform.energy.catalog;

import com.platform.energy.catalog.api.EnergyCatalogContracts.ApproveRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateBindingVersionRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateCompatibilityVersionRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateItemVersionRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateUnitVersionRequest;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.energymetadata.EnergyPointProfileService;
import com.platform.iot.energymetadata.api.EnergyPointProfileContracts.CreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EnergyCatalogServiceIntegrationTest {
    private static final long USER = 101L;
    private static final Set<String> ENERGY = Set.of("ENERGY_MANAGER");
    private static final LocalDateTime EFFECTIVE = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired private EnergyCatalogService service;
    @Autowired private EnergyPointProfileService profileService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void prepareAuthorityAndPoint() {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)", USER, "BLD001");
        assign("ENERGY_CATALOG_MAINTAIN", "DUTY_ENERGY_MAINTAIN");
        assign("ENERGY_CATALOG_REVIEW", "DUTY_ENERGY_REVIEW");
        jdbc.update("""
                INSERT INTO biz_data_point
                (point_id,point_code,point_name,building_id,naming_rule_id,family_code,
                 component_code,suffix_code,data_type,unit,is_for_calc,status,del_flag)
                VALUES ('ENERGY_KWH_POINT','METER_ELECTRICITY_TOTAL','模拟电量累计点','BLD001',
                        'RULE_WCR_MAIN','WCR','MAIN','PPE','ACCUMULATE','kWh',0,'ONLINE',0)
                """);
    }

    @Test
    void approvesCatalogCompatibilityAndBindingWithVersionEvidence() {
        approveSeededItemAndUnit();
        var compatibility = service.createCompatibilityVersion(USER, ENERGY,
                compatibility("ELECTRICITY", "KWH", "CUMULATIVE", "NONE"));
        var approvedCompatibility = service.approveCompatibility(USER, ENERGY,
                compatibility.versionId(), approve(0));
        assertThat(approvedCompatibility.status()).isEqualTo("APPROVED");

        profileService.create(USER, ENERGY, new CreateRequest("BLD001", "ENERGY_KWH_POINT",
                "ELECTRICITY", "GRID_PURCHASED", "CUMULATIVE", "MONTH", true,
                "CONFIRMED", "研发模拟测点专业属性"));
        var pending = service.createBindingVersion(USER, ENERGY,
                new CreateBindingVersionRequest("BLD001", "ENERGY_KWH_POINT", "ELECTRICITY",
                        LocalDateTime.of(2026, 2, 1, 0, 0), null, "研发模拟绑定依据"));
        assertThat(pending.confirmationStatus()).isEqualTo("PENDING_EXPERT");
        var approved = service.approveBinding(USER, ENERGY, pending.bindingVersionId(), approve(0));
        var effective = service.effectiveBinding(USER, ENERGY, "BLD001", "ENERGY_KWH_POINT",
                LocalDateTime.of(2026, 3, 1, 0, 0));

        assertThat(approved.confirmationStatus()).isEqualTo("CONFIRMED");
        assertThat(effective.bindingVersionId()).isEqualTo(approved.bindingVersionId());
        assertThat(effective.rawUnit()).isEqualTo("kWh");
        assertThat(effective.energyItemVersionId()).isEqualTo("EIV_ELECTRICITY_1");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE source_module='ENERGY_CATALOG' AND object_type='ENERGY_POINT_ITEM_BINDING'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void safelyRejectsMissingCompatibilityUnconfirmedProfileAndCategoryMismatch() {
        approveSeededItemAndUnit();
        assertCode(() -> service.createBindingVersion(USER, ENERGY,
                        new CreateBindingVersionRequest("BLD001", "ENERGY_KWH_POINT", "ELECTRICITY",
                                EFFECTIVE, null, "尚无专业属性")),
                409, EnergyCatalogErrors.POINT_PROFILE_REQUIRED);

        profileService.create(USER, ENERGY, new CreateRequest("BLD001", "ENERGY_KWH_POINT",
                "ELECTRICITY", "GRID_PURCHASED", "CUMULATIVE", "MONTH", true,
                "CONFIRMED", "研发模拟测点专业属性"));
        assertCode(() -> service.createBindingVersion(USER, ENERGY,
                        new CreateBindingVersionRequest("BLD001", "ENERGY_KWH_POINT", "ELECTRICITY",
                                EFFECTIVE, null, "缺兼容规则")),
                409, EnergyCatalogErrors.UNIT_INCOMPATIBLE);

        service.approveItem(USER, ENERGY, "EIV_NATURAL_GAS_1", approve(0));
        assertCode(() -> service.createBindingVersion(USER, ENERGY,
                        new CreateBindingVersionRequest("BLD001", "ENERGY_KWH_POINT", "NATURAL_GAS",
                                EFFECTIVE, null, "粗分类冲突")),
                409, EnergyCatalogErrors.ITEM_CATEGORY_CONFLICT);
    }

    @Test
    void rejectsMobileScopeOutputUnitsAndInventedFixedConversions() {
        assertCode(() -> service.createItemVersion(USER, ENERGY,
                        new CreateItemVersionRequest("MOBILE_DIESEL", "移动柴油", "FUEL",
                                List.of("MOBILE_COMBUSTION"), "MANUAL", "研发错误样例",
                                EFFECTIVE, null)),
                400, EnergyCatalogErrors.MOBILE_SCOPE_REJECTED);

        assertCode(() -> service.createUnitVersion(USER, ENERGY,
                        new CreateUnitVersionRequest("CUSTOM_TON", "ct", "自定义吨", "MASS", "KG",
                                new BigDecimal("999"), "FIXED_SCALE", null, 6,
                                "MANUAL", "研发错误样例", EFFECTIVE, null)),
                400, EnergyCatalogErrors.VALIDATION_FAILED);

        service.approveItem(USER, ENERGY, "EIV_ELECTRICITY_1", approve(0));
        service.approveUnit(USER, ENERGY, "EUV_KGCE_1", approve(0));
        service.approveUnit(USER, ENERGY, "EUV_TCE_1", approve(0));
        assertCode(() -> service.createCompatibilityVersion(USER, ENERGY,
                        compatibility("ELECTRICITY", "TCE", "CUMULATIVE", "NONE")),
                400, EnergyCatalogErrors.UNIT_INCOMPATIBLE);
    }

    @Test
    void requiresDynamicMaintainDutyAndRejectsStaleApprovalRevision() {
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE user_id=?", USER);
        assertCode(() -> service.createItemVersion(USER, ENERGY,
                        new CreateItemVersionRequest("TEST_FUEL", "测试燃料", "FUEL",
                                List.of("STATIONARY_COMBUSTION"), "MANUAL", "研发测试",
                                EFFECTIVE, null)),
                403, EnergyCatalogErrors.FORBIDDEN);

        assign("ENERGY_CATALOG_REVIEW", "DUTY_REVIEW_ONLY");
        service.approveItem(USER, ENERGY, "EIV_ELECTRICITY_1", approve(0));
        assertCode(() -> service.approveItem(USER, ENERGY, "EIV_ELECTRICITY_1", approve(0)),
                409, EnergyCatalogErrors.STATUS_CONFLICT);
    }

    private void approveSeededItemAndUnit() {
        service.approveItem(USER, ENERGY, "EIV_ELECTRICITY_1", approve(0));
        service.approveUnit(USER, ENERGY, "EUV_KWH_1", approve(0));
    }

    private CreateCompatibilityVersionRequest compatibility(String itemCode, String unitCode,
                                                              String semantics, String requirement) {
        return new CreateCompatibilityVersionRequest(itemCode, unitCode, semantics, true, requirement,
                "MANUAL", "研发模拟兼容依据", EFFECTIVE, null);
    }

    private ApproveRequest approve(int revision) {
        return new ApproveRequest(revision, "研发环境专业流程模拟审核");
    }

    private void assign(String duty, String assignmentId) {
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by)
                VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,1)
                """, assignmentId, USER, duty);
    }

    private static void assertCode(Runnable invocation, int status, String code) {
        assertThatThrownBy(invocation::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(status);
            assertThat(error.getErrorCode()).isEqualTo(code);
        });
    }
}
