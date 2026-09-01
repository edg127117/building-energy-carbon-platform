package com.platform.energy.catalog;

import com.platform.energy.catalog.EnergyCatalogRepository.ItemVersionRow;
import com.platform.energy.catalog.EnergyCatalogRepository.UnitVersionRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
/** 向下游计算模块提供稳定的已审核能源品种和单位只读契约，隐藏字典内部表结构。 */
public class EnergyCatalogLookup {
    private final EnergyCatalogRepository repository;

    public Optional<ApprovedEnergyItem> findApprovedItem(String itemCode, LocalDateTime effectiveAt) {
        ItemVersionRow value = repository.findApprovedItem(normalize(itemCode), effectiveAt);
        if (value == null) return Optional.empty();
        return Optional.of(new ApprovedEnergyItem(value.itemId(), value.itemCode(), value.versionId(),
                value.versionNo(), value.compatibleCategory(), repository.itemScopes(value.versionId()),
                value.sourceType(), value.sourceReference(), value.effectiveFrom(), value.effectiveTo()));
    }

    public Optional<ApprovedUnit> findApprovedUnit(String unitCode, LocalDateTime effectiveAt) {
        UnitVersionRow value = repository.findApprovedUnitByCode(normalize(unitCode), effectiveAt);
        if (value == null) return Optional.empty();
        return Optional.of(unit(value));
    }

    /** 固定比例单位先转换到共同基准量，再转换到目标单位；业务换算不会经过该入口。 */
    public Optional<UnitConversion> convert(BigDecimal quantity, String fromUnitCode,
                                            String toUnitCode, LocalDateTime effectiveAt) {
        Optional<ApprovedUnit> from = findApprovedUnit(fromUnitCode, effectiveAt);
        Optional<ApprovedUnit> to = findApprovedUnit(toUnitCode, effectiveAt);
        if (from.isEmpty() || to.isEmpty()) return Optional.empty();
        ApprovedUnit source = from.get();
        ApprovedUnit target = to.get();
        if (!source.dimensionCode().equals(target.dimensionCode())
                || !source.canonicalUnitCode().equals(target.canonicalUnitCode())) {
            return Optional.empty();
        }
        BigDecimal canonical = quantity.multiply(source.scaleFactor(), MathContext.DECIMAL128);
        BigDecimal converted = canonical.divide(target.scaleFactor(), MathContext.DECIMAL128);
        return Optional.of(new UnitConversion(quantity, source, converted, target));
    }

    private static ApprovedUnit unit(UnitVersionRow value) {
        return new ApprovedUnit(value.unitId(), value.unitCode(), value.versionId(), value.versionNo(),
                value.symbol(), value.dimensionCode(), value.canonicalUnitCode(), value.scaleFactor(),
                value.conversionType(), value.standardConditionCode(), value.sourceType(),
                value.sourceReference(), value.effectiveFrom(), value.effectiveTo());
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public record ApprovedEnergyItem(
            String itemId, String itemCode, String versionId, int versionNo,
            String compatibleCategory, List<String> usageScopes, String sourceType,
            String sourceReference, LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
    }

    public record ApprovedUnit(
            String unitId, String unitCode, String versionId, int versionNo, String symbol,
            String dimensionCode, String canonicalUnitCode, BigDecimal scaleFactor,
            String conversionType, String standardConditionCode, String sourceType,
            String sourceReference, LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
    }

    public record UnitConversion(
            BigDecimal inputQuantity, ApprovedUnit inputUnit,
            BigDecimal convertedQuantity, ApprovedUnit targetUnit) {
    }
}
