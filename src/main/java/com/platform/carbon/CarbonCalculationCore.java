package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.platform.carbon.CarbonErrors.*;

@Component
/** 执行不访问数据库的因子匹配、CO2e 计算、汇总、占比和年度唯一两项强度。 */
public class CarbonCalculationCore {
    static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal CO2_CARBON_MASS_RATIO = new BigDecimal("44")
            .divide(new BigDecimal("12"), MC);

    public FactorMatch match(ActivitySegment activity, String provinceCode,
                             ResultNature nature, List<FactorVersion> candidates) {
        FactorCategory category = category(activity.energyItemCode());
        List<FactorVersion> filtered = candidates.stream()
                .filter(value -> value.category() == category)
                .filter(value -> value.energyItemCode().equals(activity.energyItemCode()))
                .filter(value -> value.status() == LifecycleStatus.ACTIVE)
                .filter(value -> value.usageNature() == usageNature(nature))
                .filter(value -> priority(category, value.applicabilityLevel()) < 100)
                .filter(value -> !value.effectiveFrom().isAfter(local(
                        activity.startInclusive(), activity.timezoneId())))
                .filter(value -> value.effectiveTo() == null
                        || !value.effectiveTo().isBefore(local(
                        activity.endExclusive(), activity.timezoneId())))
                .filter(value -> applicable(value, activity.buildingId(), provinceCode))
                .toList();
        if (filtered.isEmpty()) {
            throw error(409, FACTOR_MISSING, "活动周期没有完整覆盖且适用的排放因子");
        }
        int best = filtered.stream().mapToInt(value -> priority(category,
                value.applicabilityLevel())).min().orElseThrow();
        List<FactorVersion> selected = filtered.stream()
                .filter(value -> priority(category, value.applicabilityLevel()) == best).toList();
        if (selected.size() != 1) {
            throw error(409, FACTOR_CONFLICT, "同一优先级匹配到多个排放因子版本");
        }
        FactorVersion factor = selected.getFirst();
        BigDecimal converted = convert(activity.quantity(), activity.unitCode(),
                factor.inputUnitCode());
        validateBundle(factor);
        return new FactorMatch(factor, converted,
                "level=" + factor.applicabilityLevel() + ";factor=" + factor.factorVersionId());
    }

    public CalculatedItem calculate(ActivitySegment activity, FactorMatch match, GwpVersion gwp) {
        FactorVersion factor = match.factor();
        BigDecimal exact;
        String gwpVersionId = null;
        if (factor.category() == FactorCategory.STATIONARY_COMBUSTION) {
            if (gwp == null || !factor.gasCode().equals(gwp.gasCode())) {
                throw error(409, FACTOR_CONFLICT, "固定燃烧结果缺少匹配的GWP版本");
            }
            BigDecimal lhv = component(factor, ComponentType.LOWER_HEATING_VALUE).value();
            BigDecimal carbon = component(factor, ComponentType.CARBON_CONTENT_PER_HEAT).value();
            BigDecimal oxidation = component(factor, ComponentType.OXIDATION_RATE).value();
            exact = match.convertedActivity().multiply(lhv, MC).multiply(carbon, MC)
                    .multiply(oxidation, MC).multiply(CO2_CARBON_MASS_RATIO, MC)
                    .multiply(gwp.value(), MC);
            gwpVersionId = gwp.gwpVersionId();
        } else {
            if (gwp != null) {
                throw error(409, FACTOR_CONFLICT, "CO2e直接因子不得重复应用GWP");
            }
            exact = match.convertedActivity().multiply(
                    component(factor, ComponentType.DIRECT_EMISSION_FACTOR).value(), MC);
        }
        String evidence = "{\"snapshotId\":\"" + activity.snapshotId()
                + "\",\"activityEvidenceHash\":\"" + activity.evidenceHash()
                + "\",\"factorVersionId\":\"" + factor.factorVersionId()
                + "\",\"formulaVersionId\":\"" + factor.formulaVersionId()
                + "\",\"activityQuantity\":\"" + activity.quantity().toPlainString()
                + "\",\"activityUnit\":\"" + activity.unitCode()
                + "\",\"convertedQuantity\":\"" + match.convertedActivity().toPlainString()
                + "\",\"factorInputUnit\":\"" + factor.inputUnitCode()
                + "\",\"gwpVersionId\":\"" + (gwp == null ? "" : gwp.gwpVersionId())
                + "\",\"gwpValue\":\"" + (gwp == null ? "" : gwp.value().toPlainString())
                + "\",\"rawKgCO2e\":\"" + exact.toPlainString() + "\"}";
        return new CalculatedItem(activity, factor, match.convertedActivity(),
                factor.formulaVersionId(), gwpVersionId, exact,
                exact.setScale(18, RoundingMode.HALF_UP),
                match.matchReason(), evidence, sha256(evidence));
    }

    public List<SummaryMetric> summarize(List<CalculatedItem> items, PeriodType periodType,
                                         DenominatorVersion area,
                                         DenominatorVersion population) {
        return summarizeWithDenominatorSelections(items, periodType,
                DenominatorSelection.available(area), DenominatorSelection.available(population));
    }

    public List<SummaryMetric> summarizeWithDenominatorSelections(List<CalculatedItem> items,
                                                                   PeriodType periodType,
                                                                   DenominatorSelection area,
                                                                   DenominatorSelection population) {
        BigDecimal scope1 = sum(items.stream().filter(value ->
                value.factor().scopeType() == ScopeType.SCOPE_1).toList());
        BigDecimal scope2 = sum(items.stream().filter(value ->
                value.factor().scopeType() == ScopeType.SCOPE_2).toList());
        BigDecimal total = scope1.add(scope2, MC);
        List<SummaryMetric> result = new ArrayList<>();
        result.add(emission("SCOPE_EMISSION", "SCOPE_1", scope1));
        result.add(emission("SCOPE_EMISSION", "SCOPE_2", scope2));
        result.add(emission("TOTAL_EMISSION", "TOTAL", total));
        Map<String, BigDecimal> byItem = new LinkedHashMap<>();
        items.forEach(value -> byItem.merge(value.activity().energyItemCode(),
                value.exactEmissionKgCo2e(), (left, right) -> left.add(right, MC)));
        byItem.forEach((item, value) -> result.add(emission("ENERGY_ITEM_EMISSION", item, value)));
        result.add(share("SCOPE_SHARE", "SCOPE_1", scope1, total));
        result.add(share("SCOPE_SHARE", "SCOPE_2", scope2, total));
        byItem.forEach((item, value) -> result.add(share("ENERGY_ITEM_SHARE", item, value, total)));
        if (periodType == PeriodType.YEAR) {
            result.add(intensity("AREA_INTENSITY", "TOTAL", total, area,
                    "kgCO2e/m²", "缺少覆盖核算年度的已激活建筑面积版本"));
            result.add(intensity("POPULATION_INTENSITY", "TOTAL", total, population,
                    "kgCO2e/人", "缺少覆盖核算年度的已激活常驻人数版本"));
        }
        return List.copyOf(result);
    }

    private static SummaryMetric emission(String metric, String dimension, BigDecimal kg) {
        BigDecimal tonnes = kg.divide(new BigDecimal("1000"), MC);
        return metric(metric, dimension, tonnes, tonnes.setScale(6, RoundingMode.HALF_UP),
                "tCO2e", null, null);
    }

    private static SummaryMetric share(String metric, String dimension,
                                       BigDecimal numerator, BigDecimal total) {
        if (total.signum() == 0) {
            return metric(metric, dimension, null, null, "RATIO", null,
                    "总排放量为零，无法计算占比");
        }
        BigDecimal raw = numerator.divide(total, MC);
        return metric(metric, dimension, raw, raw.setScale(12, RoundingMode.HALF_UP),
                "RATIO", null, null);
    }

    private static SummaryMetric intensity(String metric, String dimension, BigDecimal kg,
                                           DenominatorSelection selection, String unit,
                                           String unavailable) {
        DenominatorVersion denominator = selection == null ? null : selection.value();
        if (denominator == null) {
            String reason = selection == null || selection.unavailableReason() == null
                    ? unavailable : selection.unavailableReason();
            return metric(metric, dimension, null, null, unit, null, reason);
        }
        BigDecimal raw = kg.divide(denominator.value(), MC);
        return metric(metric, dimension, raw, raw.setScale(6, RoundingMode.HALF_UP),
                unit, denominator.denominatorVersionId(), null);
    }

    /** 年度强度的分母选择；冲突只使对应强度不可用，不能影响已完成的排放量汇总。 */
    public record DenominatorSelection(DenominatorVersion value, String unavailableReason) {
        public DenominatorSelection {
            if (value != null && unavailableReason != null) {
                throw new IllegalArgumentException("已选分母不能同时保存不可用原因");
            }
        }

        public static DenominatorSelection available(DenominatorVersion value) {
            return new DenominatorSelection(value, null);
        }

        public static DenominatorSelection unavailable(String reason) {
            return new DenominatorSelection(null, reason);
        }
    }

    private static SummaryMetric metric(String metric, String dimension, BigDecimal raw,
                                        BigDecimal value, String unit, String denominator,
                                        String unavailable) {
        String evidence = metric + ";dimension=" + dimension + ";raw="
                + (raw == null ? "null" : raw.toPlainString()) + ";unit=" + unit
                + ";denominator=" + denominator + ";reason=" + unavailable;
        return new SummaryMetric(metric, dimension, raw, value, unit, denominator,
                unavailable, sha256(evidence));
    }

    private static BigDecimal sum(List<CalculatedItem> items) {
        return items.stream().map(CalculatedItem::exactEmissionKgCo2e)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
    }

    private static void validateBundle(FactorVersion factor) {
        Map<ComponentType, FactorComponent> components = new EnumMap<>(ComponentType.class);
        factor.components().forEach(value -> {
            if (components.put(value.type(), value) != null) {
                throw error(409, FACTOR_CONFLICT, "排放因子组合包含重复参数");
            }
        });
        if (factor.category() == FactorCategory.STATIONARY_COMBUSTION) {
            required(components, ComponentType.LOWER_HEATING_VALUE);
            required(components, ComponentType.CARBON_CONTENT_PER_HEAT);
            FactorComponent oxidation = required(components, ComponentType.OXIDATION_RATE);
            FactorComponent lhv = required(components, ComponentType.LOWER_HEATING_VALUE);
            FactorComponent carbon = required(components, ComponentType.CARBON_CONTENT_PER_HEAT);
            if (components.size() != 3 || lhv.value().signum() <= 0
                    || carbon.value().signum() <= 0 || oxidation.value().signum() <= 0
                    || oxidation.value().compareTo(BigDecimal.ONE) > 0
                    || !lhv.unit().equals("MJ/" + factor.inputUnitCode())
                    || !"KG_C_PER_MJ".equals(carbon.unit())
                    || !"RATIO".equals(oxidation.unit())) {
                throw error(409, FACTOR_CONFLICT, "固定燃烧因子组合参数或单位无效");
            }
        } else {
            FactorComponent direct = required(components, ComponentType.DIRECT_EMISSION_FACTOR);
            if (components.size() != 1 || direct.value().signum() < 0
                    || !direct.unit().equals("KG_CO2E/" + factor.inputUnitCode())) {
                throw error(409, FACTOR_CONFLICT, "电力或热力直接因子组合参数或单位无效");
            }
        }
    }

    private static FactorComponent component(FactorVersion factor, ComponentType type) {
        return factor.components().stream().filter(value -> value.type() == type)
                .findFirst().orElseThrow(() -> error(409, FACTOR_CONFLICT,
                        "排放因子组合缺少必要参数"));
    }

    private static FactorComponent required(Map<ComponentType, FactorComponent> values,
                                            ComponentType type) {
        FactorComponent value = values.get(type);
        if (value == null) throw error(409, FACTOR_CONFLICT, "排放因子组合缺少必要参数");
        return value;
    }

    private static boolean applicable(FactorVersion value, String buildingId, String province) {
        return switch (value.applicabilityLevel()) {
            case BUILDING_SPECIFIC -> buildingId.equals(value.buildingId());
            case PROVINCE -> province != null && province.equals(value.regionCode());
            case NATIONAL, NOT_REGION_SPECIFIC -> true;
        };
    }

    private static int priority(FactorCategory category, ApplicabilityLevel level) {
        return switch (category) {
            case STATIONARY_COMBUSTION -> switch (level) {
                case BUILDING_SPECIFIC -> 0;
                case PROVINCE -> 1;
                case NATIONAL -> 2;
                case NOT_REGION_SPECIFIC -> 3;
            };
            case PURCHASED_ELECTRICITY_LOCATION -> switch (level) {
                case PROVINCE -> 0;
                case NATIONAL -> 1;
                default -> 100;
            };
            case PURCHASED_HEAT -> switch (level) {
                case BUILDING_SPECIFIC -> 0;
                case PROVINCE -> 1;
                case NATIONAL -> 2;
                default -> 100;
            };
        };
    }

    private static FactorCategory category(String energyItemCode) {
        return switch (energyItemCode) {
            case "ELECTRICITY" -> FactorCategory.PURCHASED_ELECTRICITY_LOCATION;
            case "HEAT" -> FactorCategory.PURCHASED_HEAT;
            default -> FactorCategory.STATIONARY_COMBUSTION;
        };
    }

    static BigDecimal convert(BigDecimal value, String from, String to) {
        String source = from.toUpperCase(Locale.ROOT);
        String target = to.toUpperCase(Locale.ROOT);
        if (source.equals(target)) return value;
        BigDecimal factor = switch (source + "->" + target) {
            case "MWH->KWH", "GJ->MJ", "T->KG" -> new BigDecimal("1000");
            case "KWH->MWH", "MJ->GJ", "KG->T" -> new BigDecimal("0.001");
            default -> throw error(409, UNIT_INCOMPATIBLE,
                    "活动量与排放因子单位不能通过固定比例转换");
        };
        return value.multiply(factor, MC);
    }

    private static UsageNature usageNature(ResultNature nature) {
        return nature == ResultNature.FORMAL ? UsageNature.FORMAL
                : UsageNature.DEVELOPMENT_REFERENCE;
    }

    private static java.time.LocalDateTime local(java.time.Instant value, String timezoneId) {
        return java.time.LocalDateTime.ofInstant(value, java.time.ZoneId.of(timezoneId));
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }
}
