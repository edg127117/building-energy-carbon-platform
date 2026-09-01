package com.platform.energy.conversion;

import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.catalog.api.EnergyCatalogContracts.ApproveRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.CreateFormulaVersionRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.CreateParameterVersionRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.SimulationRequest;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EnergyConversionServiceIntegrationTest {
    private static final long USER = 101L;
    private static final Set<String> ENERGY = Set.of("ENERGY_MANAGER");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired private EnergyConversionService service;
    @Autowired private EnergyCatalogService catalogService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void prepareAuthority() {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)", USER, "BLD001");
        assign("ENERGY_CATALOG_REVIEW", "DUTY_CATALOG_REVIEW");
        assign("ENERGY_RULE_MAINTAIN", "DUTY_RULE_MAINTAIN");
        assign("ENERGY_RULE_REVIEW", "DUTY_RULE_REVIEW");
        assign("ENERGY_CALCULATION_RUN", "DUTY_CALCULATION_RUN");
    }

    @Test
    void calculatesPurchasedHeatAsOneTceWithCompleteVersionEvidence() {
        approveCore("EIV_HEAT_1", "ECFV_HEAT_CAL_1", "ECPV_HEAT_CAL_1", false);

        var result = service.simulate(USER, ENERGY, new SimulationRequest(
                "BLD001", "HEAT", new BigDecimal("29.3076"), "GJ",
                "ENERGY_EQUIVALENT", "CALORIFIC_EQUIVALENT", "PURCHASED_HEAT",
                "GLOBAL", AT));

        assertThat(result.resultNature()).isEqualTo("DEVELOPMENT_SIMULATION");
        assertThat(result.tce()).isEqualByComparingTo("1");
        assertThat(result.resultUnitCode()).isEqualTo("TCE");
        assertThat(result.standardCoalLhvVersionId()).isEqualTo("SCLV_STANDARD_1");
        assertThat(result.formulaVersionId()).isEqualTo("ECFV_HEAT_CAL_1");
        assertThat(result.parameterVersionId()).isEqualTo("ECPV_HEAT_CAL_1");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE source_module='ENERGY_CONVERSION'
                  AND action_type='RUN_TCE_DEVELOPMENT_SIMULATION'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void convertsKilogramsToTonnesBeforeApplyingDieselLowerHeatingValue() {
        approveCore("EIV_DIESEL_1", "ECFV_LHV_T_1", "ECPV_DIESEL_LHV_1", true);

        var result = service.simulate(USER, ENERGY, new SimulationRequest(
                "BLD001", "DIESEL", new BigDecimal("1000"), "KG",
                "LOWER_HEATING_VALUE", "CALORIFIC_EQUIVALENT", "STATIONARY_COMBUSTION",
                "GLOBAL", AT));

        assertThat(result.convertedQuantity()).isEqualByComparingTo("1");
        assertThat(result.applicableInputUnitCode()).isEqualTo("T");
        assertThat(result.tce()).isEqualByComparingTo(
                new BigDecimal("43.330").divide(new BigDecimal("29.3076"), java.math.MathContext.DECIMAL128));
    }

    @Test
    void calculatesDirectFactorWithoutStandardCoalReference() {
        approveCatalog("EIV_HEAT_1", false);
        var formula = service.createFormulaVersion(USER, ENERGY, new CreateFormulaVersionRequest(
                "TEST_DIRECT_HEAT", "DIRECT_TCE_FACTOR", "CALORIFIC_EQUIVALENT",
                "DIRECT_TCE_FACTOR_V1", "GJ", "TCE", "TCE_PER_INPUT_UNIT",
                "MANUAL", "研发模拟直接系数", AT, null));
        service.approveFormula(USER, ENERGY, formula.versionId(), conversionApprove());
        var parameter = service.createParameterVersion(USER, ENERGY, new CreateParameterVersionRequest(
                "TEST_DIRECT_HEAT_FACTOR", "HEAT", formula.versionId(), new BigDecimal("0.5"),
                "TCE_PER_INPUT_UNIT", null, "PURCHASED_HEAT", "GLOBAL",
                "DEVELOPMENT_SIMULATION", "MANUAL", "研发模拟直接系数", AT, null));
        service.approveParameter(USER, ENERGY, parameter.versionId(), conversionApprove());

        var result = service.simulate(USER, ENERGY, heatDirectRequest(new BigDecimal("2")));

        assertThat(result.tce()).isEqualByComparingTo("1");
        assertThat(result.standardCoalLhvVersionId()).isNull();
        assertThat(result.algorithmCode()).isEqualTo("DIRECT_TCE_FACTOR_V1");
    }

    @Test
    void rejectsMissingConflictingAndIncompatibleRulesWithoutChoosingLatest() {
        approveCatalog("EIV_HEAT_1", false);
        assertCode(() -> service.simulate(USER, ENERGY, heatRequest("GJ", new BigDecimal("1"))),
                409, EnergyConversionErrors.RULE_MISSING);

        approveCore(null, "ECFV_HEAT_CAL_1", "ECPV_HEAT_CAL_1", false);
        jdbc.update("""
                INSERT INTO biz_energy_conversion_parameter
                (parameter_id,parameter_code,energy_item_id,created_by)
                VALUES ('ECP_HEAT_DUP','PURCHASED_HEAT_DUP','EI_HEAT',101)
                """);
        jdbc.update("""
                INSERT INTO biz_energy_conversion_parameter_version
                (version_id,parameter_id,version_no,energy_item_version_id,formula_version_id,
                 parameter_value,parameter_unit,standard_coal_lhv_version_id,consumption_scope,
                 region_code,usage_scope,status,source_type,source_reference,effective_from,
                 config_revision,created_by,approved_by,approved_at)
                VALUES ('ECPV_HEAT_DUP_1','ECP_HEAT_DUP',1,'EIV_HEAT_1','ECFV_HEAT_CAL_1',
                        1000,'MJ_PER_INPUT_UNIT','SCLV_STANDARD_1','PURCHASED_HEAT','GLOBAL',
                        'DEVELOPMENT_SIMULATION','APPROVED','MANUAL','冲突规则测试','2000-01-01',
                        1,101,101,CURRENT_TIMESTAMP)
                """);
        assertCode(() -> service.simulate(USER, ENERGY, heatRequest("GJ", new BigDecimal("1"))),
                409, EnergyConversionErrors.RULE_CONFLICT);

        jdbc.update("DELETE FROM biz_energy_conversion_parameter_version WHERE version_id='ECPV_HEAT_DUP_1'");
        jdbc.update("DELETE FROM biz_energy_conversion_parameter WHERE parameter_id='ECP_HEAT_DUP'");
        assertCode(() -> service.simulate(USER, ENERGY, heatRequest("KG", new BigDecimal("1"))),
                409, EnergyConversionErrors.UNIT_INCOMPATIBLE);
    }

    @Test
    void keepsProfessionalSeedsPendingAndRejectsMobileOrMissingDuty() {
        assertThat(service.listParameterVersions(ENERGY))
                .allMatch(value -> "PENDING_EXPERT".equals(value.status()));
        approveCore("EIV_HEAT_1", "ECFV_HEAT_CAL_1", "ECPV_HEAT_CAL_1", false);
        assertCode(() -> service.simulate(USER, ENERGY, new SimulationRequest(
                        "BLD001", "HEAT", BigDecimal.ONE, "GJ", "ENERGY_EQUIVALENT",
                        "CALORIFIC_EQUIVALENT", "MOBILE_COMBUSTION", "GLOBAL", AT)),
                400, EnergyConversionErrors.MOBILE_SCOPE_REJECTED);

        jdbc.update("DELETE FROM sys_user_backend_duty WHERE user_id=? AND duty_key=?",
                USER, "ENERGY_CALCULATION_RUN");
        assertCode(() -> service.simulate(USER, ENERGY, heatRequest("GJ", BigDecimal.ONE)),
                403, EnergyConversionErrors.FORBIDDEN);
    }

    private void approveCore(String itemVersion, String formulaVersion,
                             String parameterVersion, boolean massUnits) {
        if (itemVersion != null) approveCatalog(itemVersion, massUnits);
        service.approveStandardCoal(USER, ENERGY, "SCLV_STANDARD_1", conversionApprove());
        service.approveFormula(USER, ENERGY, formulaVersion, conversionApprove());
        service.approveParameter(USER, ENERGY, parameterVersion, conversionApprove());
    }

    private void approveCatalog(String itemVersion, boolean massUnits) {
        catalogService.approveItem(USER, ENERGY, itemVersion, catalogApprove());
        catalogService.approveUnit(USER, ENERGY, "EUV_MJ_1", catalogApprove());
        catalogService.approveUnit(USER, ENERGY, "EUV_GJ_1", catalogApprove());
        catalogService.approveUnit(USER, ENERGY, "EUV_KGCE_1", catalogApprove());
        catalogService.approveUnit(USER, ENERGY, "EUV_TCE_1", catalogApprove());
        if (massUnits) {
            catalogService.approveUnit(USER, ENERGY, "EUV_KG_1", catalogApprove());
            catalogService.approveUnit(USER, ENERGY, "EUV_T_1", catalogApprove());
        }
    }

    private SimulationRequest heatRequest(String unitCode, BigDecimal quantity) {
        return new SimulationRequest("BLD001", "HEAT", quantity, unitCode,
                "ENERGY_EQUIVALENT", "CALORIFIC_EQUIVALENT", "PURCHASED_HEAT", "GLOBAL", AT);
    }

    private SimulationRequest heatDirectRequest(BigDecimal quantity) {
        return new SimulationRequest("BLD001", "HEAT", quantity, "GJ",
                "DIRECT_TCE_FACTOR", "CALORIFIC_EQUIVALENT", "PURCHASED_HEAT", "GLOBAL", AT);
    }

    private ApproveRequest catalogApprove() {
        return new ApproveRequest(0, "研发环境专业流程模拟审核");
    }

    private com.platform.energy.conversion.api.EnergyConversionContracts.ApproveRequest conversionApprove() {
        return new com.platform.energy.conversion.api.EnergyConversionContracts.ApproveRequest(
                0, "研发环境专业流程模拟审核");
    }

    private void assign(String duty, String id) {
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by)
                VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,1)
                """, id, USER, duty);
    }

    private static void assertCode(Runnable invocation, int status, String code) {
        assertThatThrownBy(invocation::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(status);
            assertThat(error.getErrorCode()).isEqualTo(code);
        });
    }
}
