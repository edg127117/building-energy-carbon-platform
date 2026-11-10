package com.platform.carbon;

import com.platform.framework.exception.BusinessException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.*;

/** 先按同一电量来源匹配因子再相乘。较细因子的时长平均只用于输入粒度适配，结果明确保留估算性质。 */
public final class DynamicElectricityCore {
    private static final MathContext MC = MathContext.DECIMAL128;
    private DynamicElectricityCore() { }

    public record Factor(String id, String pointId, Instant start, Instant end, BigDecimal value,
                         String sourceNature, String sourceReference, String region, String scenario) { }
    public record Applied(BigDecimal quantity, BigDecimal emissionKgCo2, BigDecimal factor,
                          List<String> factorIds, List<String> sourceNatures, boolean timeAveraged) { }

    public static Applied calculate(List<DynamicElectricityInputReader.Contribution> inputs,
                                    List<Factor> factors, Instant start, Instant end) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal emission = BigDecimal.ZERO;
        Set<String> ids = new TreeSet<>(), natures = new TreeSet<>(), points = new HashSet<>();
        boolean averaged = false;
        if (inputs.isEmpty()) throw problem("DYNAMIC_ELECTRICITY_MISSING", "计量组成为空");
        for (var input : inputs) {
            if (!points.add(input.pointId()) || input.electricityKwh() == null || input.electricityKwh().signum() < 0)
                throw problem("DYNAMIC_ELECTRICITY_INVALID", "计量组成重复或用量无效");
            var specific = factors.stream().filter(f -> f.pointId().equals(input.pointId())).toList();
            var selected = specific.isEmpty() ? factors.stream().filter(f -> f.pointId().equals("*")).toList() : specific;
            var sorted = selected.stream().sorted(Comparator.comparing(Factor::start)).toList();
            Instant cursor = start;
            BigDecimal integral = BigDecimal.ZERO;
            int segments = 0;
            for (var factor : sorted) {
                if (!factor.end().isAfter(start) || !factor.start().isBefore(end)) continue;
                Instant from = factor.start().isBefore(start) ? start : factor.start();
                Instant to = factor.end().isAfter(end) ? end : factor.end();
                if (!cursor.equals(from)) throw problem("DYNAMIC_FACTOR_COVERAGE", "因子存在空档或重叠");
                if (factor.value() == null || factor.value().signum() < 0)
                    throw problem("DYNAMIC_FACTOR_INVALID", "因子无效");
                long millis = to.toEpochMilli() - from.toEpochMilli();
                integral = integral.add(factor.value().multiply(BigDecimal.valueOf(millis)));
                cursor = to;
                ids.add(factor.id());
                natures.add(factor.sourceNature());
                segments++;
            }
            if (!cursor.equals(end)) throw problem("DYNAMIC_FACTOR_MISSING", "时段因子未完整覆盖");
            var mean = integral.divide(BigDecimal.valueOf(end.toEpochMilli() - start.toEpochMilli()), MC);
            quantity = quantity.add(input.electricityKwh());
            emission = emission.add(input.electricityKwh().multiply(mean, MC));
            averaged |= segments > 1;
        }
        var equivalent = quantity.signum() == 0 ? null : emission.divide(quantity, MC);
        return new Applied(quantity, emission, equivalent, List.copyOf(ids), List.copyOf(natures), averaged);
    }

    static BusinessException problem(String code, String message) { return new BusinessException(409, code, message); }
}
