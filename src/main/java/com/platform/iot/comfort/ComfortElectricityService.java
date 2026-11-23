package com.platform.iot.comfort;

import com.platform.iot.comfort.ComfortQueryService.Space;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import static com.platform.iot.comfort.ComfortParametersService.EMISSION_FACTOR;
import static com.platform.iot.qualityusage.QualityUsageModels.POINT_HISTORY_VIEW;
import static com.platform.iot.qualityusage.QualityUsageModels.POINT_REALTIME_VIEW;
import static com.platform.iot.qualityusage.QualityUsagePolicyResolver.alignMinute;

/** 读取已鉴权房间的内机共用电表，仅按房间参数测算舒适度页面的碳排。 */
@Service
public class ComfortElectricityService {
    private static final long MAX_SAMPLE_GAP_MS = Duration.ofMinutes(6).toMillis();
    private static final long HOUR_MS = Duration.ofHours(1).toMillis();
    private static final int PAGE_SIZE = 1000;
    private static final MathContext CARBON_PRECISION = MathContext.DECIMAL128;
    private final JdbcTemplate mysql;
    private final HvacRawEventRepository raw;
    private final QualityUsagePolicyResolver quality;
    private final ComfortParametersService parameters;

    public ComfortElectricityService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql, HvacRawEventRepository raw,
                                     QualityUsagePolicyResolver quality, ComfortParametersService parameters) {
        this.mysql = mysql;
        this.raw = raw;
        this.quality = quality;
        this.parameters = parameters;
    }

    @Transactional(readOnly = true)
    public Electricity read(Space room, long asOf) {
        var date = Instant.ofEpochMilli(asOf).atZone(ComfortPmvHistory.ZONE).toLocalDate();
        var meters = roomMeters(room, asOf);
        if (meters.size() != 1) return new Electricity(date, null, null, null, List.of(), CarbonStatus.NO_METER);
        var meter = meters.getFirst();
        var pointList = mysql.query("""
                SELECT point_id,suffix_code FROM biz_data_point
                WHERE building_id=? AND equip_id=? AND status='ONLINE' AND del_flag=0
                  AND ((suffix_code='P' AND LOWER(unit)='kw') OR (suffix_code='EPP' AND LOWER(unit)='kwh'))
                """, (r, n) -> new Point(r.getString(1), r.getString(2)), room.buildingId(), meter.id());

        var power = latestPower(room, meter, pointId(pointList, "P"), asOf);
        var factor = parameters.readManualInputs(room).get(EMISSION_FACTOR);
        if (factor == null) return new Electricity(date, power, null, null, List.of(), CarbonStatus.NO_FACTOR);
        var rate = power == null || power.stale() ? null : power.watts().movePointLeft(3).multiply(factor);
        var counter = pointId(pointList, "EPP");
        if (counter == null) return new Electricity(date, power, rate, null, List.of(), CarbonStatus.INCOMPLETE);

        long midnight = date.atStartOfDay(ComfortPmvHistory.ZONE).toInstant().toEpochMilli();
        var timeline = factorTimeline(room, midnight, asOf);
        var day = meter.effectiveAt() > midnight ? Day.incomplete() : cumulative(room, meter, counter, midnight, asOf, timeline);
        return new Electricity(date, power, rate, day.total(), day.samples(),
                day.total() == null ? CarbonStatus.INCOMPLETE : CarbonStatus.AVAILABLE);
    }

    private List<Meter> roomMeters(Space room, long asOf) {
        // 全部覆盖对象必须是该房间的现有内机，且覆盖全部内机。一块计量组只读取一次。
        return mysql.query("""
                SELECT m.equip_id,r.effective_at
                FROM biz_equipment m
                JOIN biz_meter_coverage_revision r ON r.meter_id=m.equip_id AND r.building_id=m.building_id
                JOIN biz_meter_coverage_target t ON t.meter_id=r.meter_id AND t.revision=r.revision
                LEFT JOIN biz_equipment e ON e.equip_id=t.target_equipment_id AND e.building_id=r.building_id
                    AND t.building_id=r.building_id AND t.space_id=e.space_id AND e.space_id=?
                    AND e.del_flag=0 AND e.equip_category='INDOOR_UNIT'
                WHERE m.building_id=? AND m.del_flag=0 AND m.equip_category='ELECTRIC_METER'
                  AND r.revision=(SELECT MAX(v.revision) FROM biz_meter_coverage_revision v
                                  WHERE v.meter_id=m.equip_id AND v.building_id=m.building_id AND v.effective_at<=?)
                GROUP BY m.equip_id,r.effective_at
                HAVING COUNT(e.equip_id)=COUNT(*) AND COUNT(*)=(SELECT COUNT(*) FROM biz_equipment i
                    WHERE i.building_id=? AND i.space_id=? AND i.del_flag=0 AND i.equip_category='INDOOR_UNIT')
                """, (r, n) -> new Meter(r.getString(1), r.getLong(2)), room.spaceId(), room.buildingId(), asOf,
                room.buildingId(), room.spaceId());
    }

    private static String pointId(List<Point> points, String suffix) {
        var matches = points.stream().filter(point -> suffix.equalsIgnoreCase(point.suffix())).toList();
        return matches.size() == 1 ? matches.getFirst().id() : null;
    }

    private Power latestPower(Space room, Meter meter, String pointId, long asOf) {
        if (pointId == null) return null;
        return raw.findLatestByEquipmentPoints(room.buildingId(), meter.id(), List.of(pointId)).stream()
                .filter(point -> pointId.equals(point.pointId()) && point.eventTime() >= meter.effectiveAt()
                        && point.eventTime() <= asOf && Double.isFinite(point.value()) && point.value() >= 0)
                .filter(point -> quality.resolve(pointId, POINT_REALTIME_VIEW, alignMinute(point.eventTime()),
                        point.dataQuality()).decision() == Decision.ALLOW)
                .findFirst().map(point -> new Power(BigDecimal.valueOf(point.value()).movePointRight(3),
                        Instant.ofEpochMilli(point.eventTime()), asOf - point.eventTime() > MAX_SAMPLE_GAP_MS)).orElse(null);
    }

    private NavigableMap<Long, BigDecimal> factorTimeline(Space room, long midnight, long asOf) {
        return mysql.query("""
                SELECT effective_at,factor FROM biz_comfort_emission_factor_history
                WHERE building_id=? AND space_id=? AND effective_at<=? AND (effective_at>? OR effective_at=(
                    SELECT MAX(old.effective_at) FROM biz_comfort_emission_factor_history old
                    WHERE old.building_id=? AND old.space_id=? AND old.effective_at<=?))
                """, rs -> {
            var factors = new TreeMap<Long, BigDecimal>();
            while (rs.next()) factors.put(rs.getLong(1), rs.getBigDecimal(2));
            return factors;
        }, room.buildingId(), room.spaceId(), asOf, midnight, room.buildingId(), room.spaceId(), midnight);
    }

    private Day cumulative(Space room, Meter meter, String point, long midnight, long asOf,
                           NavigableMap<Long, BigDecimal> factors) {
        long searchStart = Math.max(meter.effectiveAt(), midnight - MAX_SAMPLE_GAP_MS);
        var events = new ArrayList<RawTelemetryEvent>();
        Long cursor = null;
        while (true) {
            var page = raw.findMeterPointHistory(room.buildingId(), meter.id(), point, searchStart, asOf + 1,
                    cursor, PAGE_SIZE);
            events.addAll(page);
            if (page.size() < PAGE_SIZE) break;
            if (events.size() >= 10 * PAGE_SIZE) return Day.incomplete();
            cursor = page.getLast().eventTime();
        }
        if (events.isEmpty()) return Day.incomplete();
        var policy = quality.historyContext(Set.of(point), POINT_HISTORY_VIEW, searchStart, asOf + 1);
        return accumulate(midnight, events, factors, event -> quality.resolve(policy, point, POINT_HISTORY_VIEW,
                alignMinute(event.eventTime()), event.dataQuality()).decision() == Decision.ALLOW);
    }

    static Day accumulate(long midnight, List<RawTelemetryEvent> events, NavigableMap<Long, BigDecimal> factors,
                          Predicate<RawTelemetryEvent> allowed) {
        var previous = events.stream().takeWhile(event -> event.eventTime() <= midnight).reduce((a, b) -> b).orElse(null);
        if (previous == null || !validCounter(previous) || !allowed.test(previous) || factorAt(factors, midnight) == null) {
            return Day.incomplete();
        }
        var byHour = new TreeMap<Long, CarbonSample>();
        BigDecimal total = BigDecimal.ZERO;
        for (var event : events) {
            if (event.eventTime() <= midnight) continue;
            if (!validCounter(event) || !allowed.test(event) || !sameSource(previous, event)
                    || event.eventTime() - previous.eventTime() > MAX_SAMPLE_GAP_MS || event.value() < previous.value()) {
                return new Day(null, List.copyOf(byHour.values()));
            }
            long from = Math.max(midnight, previous.eventTime());
            var factor = intervalFactor(factors, from, event.eventTime());
            if (factor == null) return new Day(null, List.copyOf(byHour.values()));
            BigDecimal usedKwh = BigDecimal.valueOf(event.value()).subtract(BigDecimal.valueOf(previous.value()));
            if (from > previous.eventTime()) {
                // 表码只记录整段增量。跨零点时按时长估算今日份额，零点前的电量留给上一天。
                usedKwh = usedKwh.multiply(BigDecimal.valueOf(event.eventTime() - from))
                        .divide(BigDecimal.valueOf(event.eventTime() - previous.eventTime()), CARBON_PRECISION);
            }
            total = total.add(usedKwh.multiply(factor, CARBON_PRECISION), CARBON_PRECISION);
            long hour = midnight + (event.eventTime() - midnight) / HOUR_MS * HOUR_MS;
            byHour.put(hour, new CarbonSample(Instant.ofEpochMilli(hour), Instant.ofEpochMilli(event.eventTime()), total));
            previous = event;
        }
        return byHour.isEmpty() ? Day.incomplete() : new Day(total, List.copyOf(byHour.values()));
    }

    private static boolean validCounter(RawTelemetryEvent event) {
        return Double.isFinite(event.value()) && event.value() >= 0 && event.sourceSystem() != null
                && event.sourceDeviceId() != null && event.sourcePointCode() != null;
    }

    private static boolean sameSource(RawTelemetryEvent left, RawTelemetryEvent right) {
        return left.sourceSystem().equals(right.sourceSystem()) && left.sourceDeviceId().equals(right.sourceDeviceId())
                && left.sourcePointCode().equals(right.sourcePointCode());
    }

    private static BigDecimal factorAt(NavigableMap<Long, BigDecimal> factors, long at) {
        var effective = factors.floorEntry(at);
        return effective == null ? null : effective.getValue();
    }

    private static BigDecimal intervalFactor(NavigableMap<Long, BigDecimal> factors, long from, long to) {
        // 跨因子变更的两次表码只能确定总增量，按区间内各因子的生效时长分配这段电量。
        BigDecimal factor = factorAt(factors, from);
        if (factor == null) return null;
        BigDecimal weighted = BigDecimal.ZERO;
        long partStart = from;
        for (var change : factors.subMap(from, false, to, false).entrySet()) {
            weighted = weighted.add(factor.multiply(BigDecimal.valueOf(change.getKey() - partStart)));
            factor = change.getValue();
            if (factor == null) return null;
            partStart = change.getKey();
        }
        return weighted.add(factor.multiply(BigDecimal.valueOf(to - partStart)))
                .divide(BigDecimal.valueOf(to - from), CARBON_PRECISION);
    }

    private record Meter(String id, long effectiveAt) {}
    private record Point(String id, String suffix) {}
    record Day(BigDecimal total, List<CarbonSample> samples) {
        static Day incomplete() { return new Day(null, List.of()); }
    }
    public enum CarbonStatus { NO_METER, NO_FACTOR, INCOMPLETE, AVAILABLE }
    public record Power(BigDecimal watts, Instant observedAt, boolean stale) {}
    public record CarbonSample(Instant startsAt, Instant observedAt, BigDecimal cumulativeKg) {}
    public record Electricity(LocalDate date, Power power, BigDecimal carbonRateKgPerHour, BigDecimal carbonTodayKg,
                              List<CarbonSample> carbonHours, CarbonStatus carbonStatus) {}
}
