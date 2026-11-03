package com.platform.carbon;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 面积版本与实际适用区间分开；按民用日期计天，不受夏令时的小时数影响。 */
final class AreaTimeline {
    record Slice(CarbonModels.DenominatorVersion version, LocalDate from, LocalDate to) { }
    record Weighted(String status, BigDecimal areaDays, long days, List<Slice> slices) {
        BigDecimal average() { return areaDays == null ? null : areaDays.divide(BigDecimal.valueOf(days), MathContext.DECIMAL128); }
    }
    static Weighted weigh(List<Slice> slices, LocalDate start, LocalDate end) {
        LocalDate cursor = start;
        BigDecimal areaDays = BigDecimal.ZERO;
        var ordered = slices.stream().filter(s -> s.from().isBefore(end) && (s.to() == null || s.to().isAfter(start)))
                .sorted(java.util.Comparator.comparing(Slice::from)).toList();
        for (Slice s : ordered) {
            LocalDate from = s.from().isBefore(start) ? start : s.from();
            LocalDate to = s.to() == null || s.to().isAfter(end) ? end : s.to();
            if (!from.equals(cursor)) return new Weighted(from.isBefore(cursor) ? "AREA_CONFLICT" : "AREA_GAP", null, ChronoUnit.DAYS.between(start,end), ordered);
            if (s.version().value() == null || s.version().value().signum() <= 0 || !"M2".equals(s.version().unitCode()))
                return new Weighted("AREA_INVALID", null, ChronoUnit.DAYS.between(start,end), ordered);
            areaDays = areaDays.add(s.version().value().multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(from,to))));
            cursor = to;
        }
        return new Weighted(cursor.equals(end) ? "AVAILABLE" : "AREA_GAP", cursor.equals(end) ? areaDays : null,
                ChronoUnit.DAYS.between(start,end), ordered);
    }
}
