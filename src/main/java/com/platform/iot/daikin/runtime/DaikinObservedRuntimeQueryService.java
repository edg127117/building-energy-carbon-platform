package com.platform.iot.daikin.runtime;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.monitoring.query.DaikinMonitoringQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** 查询平台自己观测到的开机时长；不读取或合并厂家运行统计。 */
@Service
public class DaikinObservedRuntimeQueryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final DaikinMonitoringQueryService access;
    private final Clock clock;

    @Autowired
    public DaikinObservedRuntimeQueryService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                             DaikinMonitoringQueryService access) {
        this(jdbc, access, Clock.systemUTC());
    }

    DaikinObservedRuntimeQueryService(JdbcTemplate jdbc, DaikinMonitoringQueryService access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    public Page values(Long user, Set<String> roles, String equipmentId,
                       String granularity, Long before, int limit) {
        var scope = access.requireRuntimeEquipment(user, roles, equipmentId);
        if (!Set.of("DAY", "MONTH", "YEAR").contains(granularity) || limit < 1 || limit > 100
                || before != null && before < 0) {
            throw new BusinessException(400, "DAIKIN_MONITORING_INVALID_PARAMETER", "运行时长查询参数无效");
        }
        long now = clock.millis();
        LocalDate firstDay = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate().minusDays(364);
        long first = firstDay.atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<Day> days = jdbc.query("""
                SELECT day_start_ms,on_ms,covered_ms FROM biz_daikin_observed_runtime_day
                WHERE identity_id=? AND building_id=? AND mapping_version=? AND day_start_ms>=?
                ORDER BY day_start_ms DESC
                """, (rs, row) -> new Day(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                scope.identityId(), scope.buildingId(), scope.mappingVersion(), first);
        TreeMap<Long, Totals> grouped = new TreeMap<>((left, right) -> Long.compare(right, left));
        for (Day day : days) {
            LocalDate date = Instant.ofEpochMilli(day.start()).atZone(ZONE).toLocalDate();
            LocalDate period = switch (granularity) {
                case "MONTH" -> date.withDayOfMonth(1);
                case "YEAR" -> date.withDayOfYear(1);
                default -> date;
            };
            long start = period.atStartOfDay(ZONE).toInstant().toEpochMilli();
            Totals totals = grouped.computeIfAbsent(start, ignored -> new Totals());
            totals.on += day.on();
            totals.covered += day.covered();
        }
        List<Period> periods = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            long start = entry.getKey();
            if (before != null && start >= before) continue;
            LocalDate date = Instant.ofEpochMilli(start).atZone(ZONE).toLocalDate();
            long end = (switch (granularity) {
                case "MONTH" -> date.plusMonths(1);
                case "YEAR" -> date.plusYears(1);
                default -> date.plusDays(1);
            }).atStartOfDay(ZONE).toInstant().toEpochMilli();
            periods.add(new Period(start, end, Math.min(now, end) - start,
                    entry.getValue().on, entry.getValue().covered));
            if (periods.size() > limit) break;
        }
        boolean more = periods.size() > limit;
        List<Period> items = List.copyOf(periods.subList(0, Math.min(periods.size(), limit)));
        return new Page("PLATFORM_OBSERVED", granularity, ZONE.getId(), items,
                more ? items.getLast().periodStart() : null);
    }

    private record Day(long start, long on, long covered) { }
    private static final class Totals { long on; long covered; }
    public record Period(long periodStart, long periodEnd, long elapsedMillis,
                         long onMillis, long coveredMillis) { }
    public record Page(String source, String granularity, String statisticsZone,
                       List<Period> items, Long nextCursor) { }
}
