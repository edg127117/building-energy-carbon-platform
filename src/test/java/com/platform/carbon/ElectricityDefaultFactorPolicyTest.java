package com.platform.carbon;

import com.platform.carbon.api.CarbonContracts.CreateFactorVersionRequest;
import com.platform.carbon.api.CarbonContracts.FactorComponentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ElectricityDefaultFactorPolicyTest {
    private static final String LEGACY_FACTOR_CODE = "ELECTRICITY_LOCATION_CO2_05703";
    private static final String CATEGORY = "PURCHASED_ELECTRICITY_LOCATION";
    private static final String GAS = "CO2";
    private static final String COVERAGE = "CO2_ONLY_ELECTRICITY";

    @Test
    void allowsMissingDataYearOnlyForTheConfirmedLegacyNationalFactorYears() {
        assertThat(ElectricityDefaultFactorPolicy.allowsMissingDataYear(
                legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021, CATEGORY, GAS, COVERAGE,
                        "MWH", List.of(component("570.3", "KG_CO2/MWH")), null))).isTrue();
        assertThat(ElectricityDefaultFactorPolicy.allowsMissingDataYear(
                legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2022, CATEGORY, GAS, COVERAGE,
                        "MWH", List.of(component("570.3", "KG_CO2/MWH")), null))).isTrue();
    }

    @Test
    void requiresDataYearForTheHubeiAnnualFactor() {
        CreateFactorVersionRequest request = legacyRequest(
                "ELECTRICITY_AVERAGE_2024_PROVINCE_420000", "PROVINCE", 2024,
                CATEGORY, GAS, COVERAGE, "MWH",
                List.of(component("440.3", "KG_CO2/MWH")), null);

        assertThat(ElectricityDefaultFactorPolicy.allowsMissingDataYear(request)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requestsOutsideTheLegacyException")
    void rejectsRequestsOutsideTheConfirmedLegacyException(
            String ignored, CreateFactorVersionRequest request) {
        assertThat(ElectricityDefaultFactorPolicy.allowsMissingDataYear(request)).isFalse();
    }

    static Stream<Arguments> requestsOutsideTheLegacyException() {
        return Stream.of(
                Arguments.of("不同因子编码", legacyRequest("ELECTRICITY_LOCATION_CO2_05704", "NATIONAL", 2021,
                        CATEGORY, GAS, COVERAGE, "MWH", List.of(component("570.3", "KG_CO2/MWH")), null)),
                Arguments.of("不同适用层级", legacyRequest(LEGACY_FACTOR_CODE, "PROVINCE", 2021,
                        CATEGORY, GAS, COVERAGE, "MWH", List.of(component("570.3", "KG_CO2/MWH")), null)),
                Arguments.of("过早核算年", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2020,
                        CATEGORY, GAS, COVERAGE, "MWH", List.of(component("570.3", "KG_CO2/MWH")), null)),
                Arguments.of("过晚核算年", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2023,
                        CATEGORY, GAS, COVERAGE, "MWH", List.of(component("570.3", "KG_CO2/MWH")), null)),
                Arguments.of("不同因子数值", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021,
                        CATEGORY, GAS, COVERAGE, "MWH", List.of(component("570.2", "KG_CO2/MWH")), null)),
                Arguments.of("不同组件单位", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021,
                        CATEGORY, GAS, COVERAGE, "MWH", List.of(component("570.3", "KG_CO2/KWH")), null)),
                Arguments.of("不同输入单位", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021,
                        CATEGORY, GAS, COVERAGE, "KWH", List.of(component("570.3", "KG_CO2/MWH")), null)),
                Arguments.of("不同气体", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021,
                        CATEGORY, "CO2E", COVERAGE, "MWH", List.of(component("570.3", "KG_CO2/MWH")), null)),
                Arguments.of("不同因子类别", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021,
                        "PURCHASED_ELECTRICITY_MARKET", GAS, COVERAGE, "MWH",
                        List.of(component("570.3", "KG_CO2/MWH")), null)),
                Arguments.of("不同气体覆盖", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021,
                        CATEGORY, GAS, "CO2_CH4_N2O", "MWH", List.of(component("570.3", "KG_CO2/MWH")), null)),
                Arguments.of("开发参考用途", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021,
                        CATEGORY, GAS, COVERAGE, "MWH", List.of(component("570.3", "KG_CO2/MWH")),
                        null, "DEVELOPMENT_REFERENCE")),
                Arguments.of("空组件", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021,
                        CATEGORY, GAS, COVERAGE, "MWH", List.of(), null)),
                Arguments.of("多个组件", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021,
                        CATEGORY, GAS, COVERAGE, "MWH", List.of(
                                component("570.3", "KG_CO2/MWH"), component("0", "KG_CO2/MWH")), null)),
                Arguments.of("已提供数据年", legacyRequest(LEGACY_FACTOR_CODE, "NATIONAL", 2021,
                        CATEGORY, GAS, COVERAGE, "MWH", List.of(component("570.3", "KG_CO2/MWH")), 2021)));
    }

    private static CreateFactorVersionRequest legacyRequest(
            String factorCode, String applicabilityLevel, Integer accountingYear,
            String factorCategory, String gasCode, String gasCoverage, String inputUnitCode,
            List<FactorComponentRequest> components, Integer dataYear) {
        return legacyRequest(factorCode, applicabilityLevel, accountingYear, factorCategory, gasCode,
                gasCoverage, inputUnitCode, components, dataYear, "FORMAL");
    }

    private static CreateFactorVersionRequest legacyRequest(
            String factorCode, String applicabilityLevel, Integer accountingYear,
            String factorCategory, String gasCode, String gasCoverage, String inputUnitCode,
            List<FactorComponentRequest> components, Integer dataYear, String usageNature) {
        return new CreateFactorVersionRequest(
                factorCode, "SCOPE_2", "ELECTRICITY", factorCategory, "GAS_MASS", gasCode,
                gasCoverage, "SOURCE_LEGACY_NATIONAL", applicabilityLevel, null, null, inputUnitCode,
                null, usageNature, LocalDateTime.of(2021, 1, 1, 0, 0), null,
                "CFV_ELECTRICITY_CO2_V1", "CRP_DECIMAL128_V1", components, dataYear, accountingYear);
    }

    private static FactorComponentRequest component(String value, String unit) {
        return new FactorComponentRequest(
                "DIRECT_EMISSION_FACTOR", new BigDecimal(value), unit,
                "SOURCE_LEGACY_NATIONAL", "isolated policy test");
    }
}
