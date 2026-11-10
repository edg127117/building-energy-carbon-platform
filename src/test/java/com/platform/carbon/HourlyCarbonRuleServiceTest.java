package com.platform.carbon;

import com.platform.carbon.CarbonModels.ActivitySegment;
import com.platform.carbon.CarbonModels.ApplicabilityLevel;
import com.platform.carbon.CarbonModels.ComponentType;
import com.platform.carbon.CarbonModels.FactorCategory;
import com.platform.carbon.CarbonModels.FactorComponent;
import com.platform.carbon.CarbonModels.FactorSourceVersion;
import com.platform.carbon.CarbonModels.FactorVersion;
import com.platform.carbon.CarbonModels.GwpVersion;
import com.platform.carbon.CarbonModels.LifecycleStatus;
import com.platform.carbon.CarbonModels.PeriodType;
import com.platform.carbon.CarbonModels.ResultNature;
import com.platform.carbon.CarbonModels.ScopeType;
import com.platform.carbon.CarbonModels.UsageNature;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实计算核，固定小时规则快照的正式参数、证据和换版拒绝边界。 */
class HourlyCarbonRuleServiceTest {
    private static final String BUILDING = "BLD001";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime LOCAL_START = LocalDateTime.of(2026, 10, 26, 0, 0);
    private static final LocalDateTime LOCAL_END = LOCAL_START.plusHours(1);
    private static final Instant START = LOCAL_START.atZone(ZONE).toInstant();
    private static final Instant END = LOCAL_END.atZone(ZONE).toInstant();

    private CarbonRuleRepository rules;
    private HourlyCarbonRuleService service;

    @BeforeEach
    void setUp() {
        rules = mock(CarbonRuleRepository.class);
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:hourly_rule_service_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        service = new HourlyCarbonRuleService(rules, new CarbonCalculationCore(),
                new DataSourceTransactionManager(dataSource), new CarbonProperties());
    }

    @Test
    void calculatesKwhWithFormalRulesAndSourceVersionEvidenceForMonitoringActivity() {
        FactorVersion factor = directFactor("FACTOR_FORMAL_V1", LOCAL_START.minusDays(1),
                LOCAL_END.plusDays(1));
        stubHealthyDependencies(factor);

        HourlyCarbonRuleService.Calculated result = service.calculate(activity(new BigDecimal("10")),
                UsageNature.FORMAL);

        assertThat(result.exactKgCo2e()).isEqualByComparingTo("5.827");
        assertThat(result.factorVersionId()).isEqualTo("FACTOR_FORMAL_V1");
        assertThat(result.formulaVersionId()).isEqualTo("FORMULA_FORMAL_V1");
        assertThat(result.roundingPolicyVersionId()).isEqualTo("ROUNDING_FORMAL_V1");
        assertThat(result.evidence().path("activity").path("resultNature").asText())
                .isEqualTo("MONITORING");
        assertThat(result.evidence().path("factor").path("usageNature").asText())
                .isEqualTo("FORMAL");
        assertThat(result.evidence().path("factor").path("factorVersionId").asText())
                .isEqualTo("FACTOR_FORMAL_V1");
        assertThat(result.evidence().path("formulaDefinition").path("formula_version_id").asText())
                .isEqualTo("FORMULA_FORMAL_V1");
        assertThat(result.evidence().path("roundingDefinition").path("rounding_policy_version_id").asText())
                .isEqualTo("ROUNDING_FORMAL_V1");
        assertThat(result.evidence().path("factorSource").path("sourceVersionId").asText())
                .isEqualTo("SOURCE_FORMAL_V1");
        verify(rules).hourlyFactors("ELECTRICITY", LOCAL_START, LOCAL_END);
        verify(rules).activeFormulaMatches("FORMULA_FORMAL_V1",
                FactorCategory.PURCHASED_ELECTRICITY_LOCATION, UsageNature.FORMAL, "CO2E_DIRECT");
    }

    @Test
    void preservesZeroKwhAsACalculatedZeroInsteadOfTreatingItAsMissing() {
        FactorVersion factor = directFactor("FACTOR_FORMAL_V1", LOCAL_START.minusDays(1),
                LOCAL_END.plusDays(1));
        stubHealthyDependencies(factor);

        HourlyCarbonRuleService.Calculated result = service.calculate(activity(BigDecimal.ZERO),
                UsageNature.FORMAL);

        assertThat(result.exactKgCo2e()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.evidence().path("rawKgCO2e").asText()).isEqualTo("0.0000");
    }

    @Test
    void rejectsAFactorVersionThatChangesDuringTheHour() {
        FactorVersion covering = directFactor("FACTOR_FORMAL_V1", LOCAL_START.minusDays(1),
                LOCAL_END.plusDays(1));
        FactorVersion replacement = directFactor("FACTOR_MID_HOUR_V2", LOCAL_START.plusMinutes(30), null);
        stubHealthyDependencies(covering);
        when(rules.hourlyFactors("ELECTRICITY", LOCAL_START, LOCAL_END))
                .thenReturn(List.of(covering, replacement));

        assertError(() -> service.calculate(activity(BigDecimal.TEN), UsageNature.FORMAL),
                CarbonErrors.FACTOR_CONFLICT);
    }

    @Test
    void rejectsAGwpVersionThatChangesDuringTheHour() {
        FactorVersion factor = gasMassFactor("FACTOR_GAS_V1", LOCAL_START.minusDays(1),
                LOCAL_END.plusDays(1));
        stubHealthyDependencies(factor);
        when(rules.hourlyGwp("CO2", UsageNature.FORMAL, LOCAL_START, LOCAL_END))
                .thenReturn(List.of(
                        new GwpVersion("GWP_CO2_OLD", "CO2", BigDecimal.ONE, "formal source",
                                UsageNature.FORMAL, LOCAL_START.minusDays(1), LOCAL_START.plusMinutes(30)),
                        new GwpVersion("GWP_CO2_NEW", "CO2", BigDecimal.ONE, "formal source",
                                UsageNature.FORMAL, LOCAL_START.plusMinutes(30), null)));

        assertError(() -> service.calculate(activity(BigDecimal.TEN), UsageNature.FORMAL),
                CarbonErrors.FACTOR_CONFLICT);
        verify(rules).hourlyGwp("CO2", UsageNature.FORMAL, LOCAL_START, LOCAL_END);
    }

    @Test
    void rejectsAFormulaVersionThatDoesNotCoverTheWholeHour() {
        FactorVersion factor = directFactor("FACTOR_FORMAL_V1", LOCAL_START.minusDays(1),
                LOCAL_END.plusDays(1));
        stubHealthyDependencies(factor);
        when(rules.formulaEvidence("FORMULA_FORMAL_V1"))
                .thenReturn(formulaEvidence(LOCAL_START.minusDays(1), LOCAL_END.minusSeconds(1)));

        assertError(() -> service.calculate(activity(BigDecimal.TEN), UsageNature.FORMAL),
                CarbonErrors.FACTOR_CONFLICT);
    }

    private void stubHealthyDependencies(FactorVersion factor) {
        when(rules.findBuildingRegion(BUILDING)).thenReturn("310100");
        when(rules.hourlyFactors("ELECTRICITY", LOCAL_START, LOCAL_END)).thenReturn(List.of(factor));
        when(rules.activeFormulaMatches(factor.formulaVersionId(), factor.category(), UsageNature.FORMAL,
                factor.resultBasis())).thenReturn(true);
        when(rules.activeRoundingPolicy(factor.roundingPolicyVersionId())).thenReturn(true);
        when(rules.formulaEvidence(factor.formulaVersionId()))
                .thenReturn(formulaEvidence(LOCAL_START.minusDays(1), LOCAL_END.plusDays(1)));
        when(rules.roundingEvidence(factor.roundingPolicyVersionId())).thenReturn(Map.of(
                "rounding_policy_version_id", factor.roundingPolicyVersionId(),
                "usage_nature", "FORMAL", "rounding_mode", "HALF_UP"));
        when(rules.findSourceVersion(factor.sourceVersionId())).thenReturn(source());
    }

    private static ActivitySegment activity(BigDecimal quantity) {
        return new ActivitySegment("HOUR_SNAPSHOT_1", BUILDING, PeriodType.HOUR, START, END,
                ZONE.getId(), "ELECTRICITY", quantity, "KWH", "LOCKED_COMPLETE", "COMPLETE",
                ResultNature.MONITORING, "ACTIVITY_EVIDENCE_HASH", CarbonEvidence.object());
    }

    private static FactorVersion directFactor(String versionId, LocalDateTime effectiveFrom,
                                              LocalDateTime effectiveTo) {
        return factor(versionId, effectiveFrom, effectiveTo, "CO2E_DIRECT", null,
                "DIRECT_FACTOR_GAS_COVERAGE", "KG_CO2E/KWH");
    }

    private static FactorVersion gasMassFactor(String versionId, LocalDateTime effectiveFrom,
                                               LocalDateTime effectiveTo) {
        return factor(versionId, effectiveFrom, effectiveTo, "GAS_MASS", "CO2",
                "CO2_ONLY_ELECTRICITY", "KG_CO2/KWH");
    }

    private static FactorVersion factor(String versionId, LocalDateTime effectiveFrom,
                                        LocalDateTime effectiveTo, String resultBasis, String gasCode,
                                        String gasCoverage, String componentUnit) {
        return new FactorVersion("FACTOR_" + versionId, "PURCHASED_ELECTRICITY_LOCATION", versionId,
                1, ScopeType.SCOPE_2, "ELECTRICITY", FactorCategory.PURCHASED_ELECTRICITY_LOCATION,
                resultBasis, gasCode, gasCoverage, "SOURCE_FORMAL_V1", ApplicabilityLevel.NATIONAL,
                null, null, "KWH", null, UsageNature.FORMAL, LifecycleStatus.ACTIVE,
                effectiveFrom, effectiveTo,
                "GAS_MASS".equals(resultBasis) ? "PURCHASED_ELECTRICITY_LOCATION_CO2_V1"
                        : "FORMULA_FORMAL_V1",
                "ROUNDING_FORMAL_V1", 1, 7L, LOCAL_START.minusDays(2), 8L,
                LOCAL_START.minusDays(1), "formal reviewed", 9L, LOCAL_START.minusDays(1),
                List.of(new FactorComponent("DIRECT_" + versionId, ComponentType.DIRECT_EMISSION_FACTOR,
                        new BigDecimal("0.5827"), componentUnit, "SOURCE_FORMAL_V1", "formal source evidence")),
                2026, 2026);
    }

    private static Map<String, Object> formulaEvidence(LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("formula_version_id", "FORMULA_FORMAL_V1");
        evidence.put("algorithm_code", "PURCHASED_ELECTRICITY_LOCATION_CO2E_V1");
        evidence.put("usage_nature", "FORMAL");
        evidence.put("effective_from", Timestamp.valueOf(effectiveFrom));
        evidence.put("effective_to", Timestamp.valueOf(effectiveTo));
        return evidence;
    }

    private static FactorSourceVersion source() {
        return new FactorSourceVersion("SOURCE_FORMAL", "NATIONAL_GRID_2026", "SOURCE_FORMAL_V1", 1,
                "2026 national grid factor", "Carbon authority", "formal-document", 2026,
                LocalDate.of(2026, 1, 1), "formal monitoring source", "formal source evidence",
                UsageNature.FORMAL, 7L, LOCAL_START.minusDays(2));
    }

    private static void assertError(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expectedCode);
    }
}
