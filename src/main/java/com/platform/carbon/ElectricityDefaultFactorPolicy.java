package com.platform.carbon;

import com.platform.carbon.api.CarbonContracts.CreateFactorVersionRequest;

import java.math.BigDecimal;

/** 已确认的旧全国默认因子例外；不将缺少统计数据年的规则扩展到年度平均因子。 */
public final class ElectricityDefaultFactorPolicy {
    public static final String FACTOR_CODE = "ELECTRICITY_LOCATION_CO2_05703";
    public static final BigDecimal KG_CO2_PER_MWH = new BigDecimal("570.3");

    private ElectricityDefaultFactorPolicy() { }

    public static boolean supports(String factorCode, String applicabilityLevel, Integer accountingYear) {
        return code(FACTOR_CODE, factorCode) && code("NATIONAL", applicabilityLevel)
                && accountingYear != null && (accountingYear == 2021 || accountingYear == 2022);
    }

    /** 同一编码也须核验实际数值和口径，防止其他因子借默认身份绕过数据年要求。 */
    public static boolean allowsMissingDataYear(CreateFactorVersionRequest request) {
        if (request.dataYear() != null || !supports(request.factorCode(), request.applicabilityLevel(), request.accountingYear())
                || !code("SCOPE_2", request.scopeType()) || !code("ELECTRICITY", request.energyItemCode())
                || !code("PURCHASED_ELECTRICITY_LOCATION", request.factorCategory())
                || !code("GAS_MASS", request.resultBasis()) || !code("CO2", request.gasCode())
                || !code("FORMAL", request.usageNature())
                || !code("CO2_ONLY_ELECTRICITY", request.gasCoverage()) || !code("MWH", request.inputUnitCode())
                || request.components() == null || request.components().size() != 1) return false;
        var component = request.components().getFirst();
        return component != null && code("DIRECT_EMISSION_FACTOR", component.componentType())
                && code("KG_CO2/MWH", component.unit()) && component.value() != null
                && KG_CO2_PER_MWH.compareTo(component.value()) == 0;
    }

    private static boolean code(String expected, String value) {
        return value != null && expected.equalsIgnoreCase(value.trim());
    }
}
