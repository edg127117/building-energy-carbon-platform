package com.platform.iot.daikin.monitoring.query;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.monitoring.DaikinMonitoringTargets;
import com.platform.iot.daikin.monitoring.DaikinMonitoringTargets.Target;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.platform.iot.daikin.monitoring.query.DaikinBuildingMonitoringDtos.*;

/**
 * 只读取 MySQL 正式目标、当前状态和观测日累计；不触发厂家采集，也不把厂家累计或在线时间转换成开机时长。
 * 每次请求复用暖通菜单及建筑授权，在一致读中完成完整设备范围的汇总，避免前端分页漏计和身份重复计数。
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DaikinBuildingMonitoringService {
    private static final String SCOPE = "ACTIVE_BOUND_DAIKIN_INDOOR";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long FRESH_MS = 300_000;
    private static final List<String> MODES = List.of("fan", "cooling", "heating", "dependent", "dry",
            "automaticCooling", "automaticHeating", "ventilationMonitorOnly");
    private static final List<String> SPEEDS = List.of("low", "middle", "high", "automatic", "middleLow", "middleHigh");
    private final JdbcTemplate jdbc;
    private final DaikinMonitoringQueryService access;
    private final DaikinMonitoringTargets targets;
    private final Clock clock;

    @Autowired
    public DaikinBuildingMonitoringService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            DaikinMonitoringQueryService access, DaikinMonitoringTargets targets) {
        this(jdbc, access, targets, Clock.systemUTC());
    }

    DaikinBuildingMonitoringService(JdbcTemplate jdbc, DaikinMonitoringQueryService access,
                                    DaikinMonitoringTargets targets, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.targets = targets;
        this.clock = clock;
    }

    public DeviceSummary summary(Long user, Set<String> roles, String buildingId) {
        access.requireBuilding(user, roles, buildingId);
        long now = clock.millis();
        var equipment = equipment(buildingId);
        var fields = fields(buildingId);
        var reasons = new LinkedHashMap<String, Long>();
        var modes = new CategoryCounter(MODES);
        var speeds = new CategoryCounter(SPEEDS);
        long on = 0, off = 0, expires = now + FRESH_MS;
        for (var identities : equipment.values()) {
            if (identities.size() != 1) { increment(reasons, "IDENTITY_CONFLICT"); continue; }
            Target target = identities.getFirst();
            Map<String, Field> values = fields.getOrDefault(target.identityId(), Map.of());
            // 字段到期会改变分组；包括造成未知的通信/冲突字段，缓存均不得跨过最早到期点。
            for (Field field : values.values()) {
                if (unavailable(field, target, now) == null) expires = Math.min(expires, field.validAt() + FRESH_MS + 1);
            }
            Field power = values.get("onOff");
            String reason = unavailable(power, target, now);
            if (reason == null && !Set.of("on", "off").contains(power.value())) reason = "UNKNOWN_VALUE";
            if (reason == null && (matches(values.get("controller.isConnectionUp"), target, now, "false")
                    || matches(values.get("inCommunicationError"), target, now, "true"))) reason = "COMMUNICATION_ERROR";
            if (reason == null && ("on".equals(power.value()) && matches(values.get("unitStatus"), target, now, "stopped")
                    || "off".equals(power.value()) && matches(values.get("unitStatus"), target, now, "operating"))) {
                reason = "STATE_CONFLICT";
            }
            if (reason != null) { increment(reasons, reason); continue; }
            if ("off".equals(power.value())) { off++; continue; }
            on++;
            modes.add(values.get("mode"), target, now);
            speeds.add(values.get("fanSpeed"), target, now);
        }
        return new DeviceSummary(buildingId, SCOPE, now, expires, equipment.size(), on, off,
                equipment.size() - on - off, Map.copyOf(reasons), modes.result(on), speeds.result(on));
    }

    public RuntimeRanking ranking(Long user, Set<String> roles, String buildingId, String month, int limit) {
        access.requireBuilding(user, roles, buildingId);
        long now = clock.millis();
        YearMonth selected;
        try {
            if (month == null || !month.matches("[0-9]{4}-(0[1-9]|1[0-2])") || limit < 1 || limit > 100) throw new IllegalArgumentException();
            selected = YearMonth.parse(month);
        } catch (RuntimeException invalid) { throw invalidMonth(); }
        long start = selected.atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long end = selected.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long retentionStart = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate().minusDays(364)
                .atStartOfDay(ZONE).toInstant().toEpochMilli();
        if (start > now || start < retentionStart) throw invalidMonth();
        long elapsed = Math.min(now, end) - start;
        var equipment = equipment(buildingId);
        var days = jdbc.query("""
                SELECT identity_id,mapping_version,day_start_ms,on_ms,covered_ms
                FROM biz_daikin_observed_runtime_day WHERE building_id=? AND day_start_ms>=? AND day_start_ms<?
                """, (rs, row) -> new Day(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                buildingId, start, Math.min(now, end));
        Map<String, List<Day>> history = new HashMap<>();
        for (Day day : days) history.computeIfAbsent(day.identityId(), ignored -> new ArrayList<>()).add(day);
        var spaces = spaces(buildingId);
        Map<String, String> names = new HashMap<>();
        jdbc.query("SELECT equip_id,equip_name FROM biz_equipment WHERE building_id=? AND del_flag=0",
                rs -> { names.put(rs.getString(1), rs.getString(2)); }, buildingId);
        var excluded = new LinkedHashMap<String, Long>();
        List<RuntimeRank> candidates = new ArrayList<>();
        for (var identities : equipment.values()) {
            if (identities.size() != 1) { increment(excluded, "IDENTITY_CONFLICT"); continue; }
            Target target = identities.getFirst();
            long on = 0, covered = 0;
            boolean invalid = false;
            for (Day day : history.getOrDefault(target.identityId(), List.of())) {
                if (day.mapping() != target.mappingVersion()) continue;
                var date = Instant.ofEpochMilli(day.start()).atZone(ZONE).toLocalDate();
                long dayStart = date.atStartOfDay(ZONE).toInstant().toEpochMilli();
                long dayEnd = date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
                if (day.start() != dayStart || day.on() < 0 || day.covered() < day.on()
                        || day.covered() > Math.min(now, dayEnd) - dayStart) { invalid = true; break; }
                on = Math.addExact(on, day.on());
                covered = Math.addExact(covered, day.covered());
            }
            if (invalid) { increment(excluded, "INVALID_RUNTIME"); continue; }
            if (covered == 0) { increment(excluded, "NO_COVERAGE"); continue; }
            Space space = spaces.get(target.spaceId());
            candidates.add(new RuntimeRank(0, target.equipmentId(), target.identityId(), target.mappingVersion(),
                    target.equipmentCode(), names.get(target.equipmentId()), space == null ? null : space.name(),
                    floor(space, spaces), on, covered, BigDecimal.valueOf(on).divide(BigDecimal.valueOf(3_600_000), 4, RoundingMode.HALF_UP),
                    null, covered < elapsed ? "PARTIAL" : "COMPLETE"));
        }
        candidates.sort(Comparator.comparingLong(RuntimeRank::onMillis).reversed().thenComparing(RuntimeRank::equipmentId));
        long highest = candidates.isEmpty() ? 0 : candidates.getFirst().onMillis();
        List<RuntimeRank> items = new ArrayList<>();
        for (RuntimeRank row : candidates.subList(0, Math.min(limit, candidates.size()))) {
            items.add(new RuntimeRank(items.size() + 1, row.equipmentId(), row.identityId(), row.mappingVersion(),
                    row.equipmentCode(), row.equipmentName(), row.spaceName(), row.floorName(), row.onMillis(), row.coveredMillis(),
                    row.hours(), highest == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(row.onMillis()).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(highest), 2, RoundingMode.HALF_UP), row.coverageStatus()));
        }
        return new RuntimeRanking(buildingId, SCOPE, "PLATFORM_OBSERVED", month, ZONE.getId(), now, start, end,
                elapsed, equipment.size(), candidates.size(), Map.copyOf(excluded), List.copyOf(items));
    }

    private Map<String, List<Target>> equipment(String buildingId) {
        Map<String, List<Target>> result = new LinkedHashMap<>();
        for (Target target : targets.forBuilding(buildingId)) {
            if (target.key().kind() != DaikinDeviceKey.Kind.INDOOR) continue;
            var values = result.computeIfAbsent(target.equipmentId(), ignored -> new ArrayList<>());
            if (!values.contains(target)) values.add(target);
        }
        return result;
    }

    private Map<String, Map<String, Field>> fields(String buildingId) {
        Map<String, Map<String, Field>> result = new HashMap<>();
        jdbc.query("""
                SELECT c.* FROM biz_daikin_current_state c
                JOIN biz_daikin_monitoring_target t ON t.identity_id=c.identity_id AND t.active=1
                  AND t.equipment_id=c.equipment_id AND t.building_id=c.building_id AND t.mapping_version=c.mapping_version
                WHERE t.building_id=? AND c.field_name IN ('onOff','mode','fanSpeed',
                  'unitStatus','controller.isConnectionUp','inCommunicationError')
                """, rs -> {
            Field field = new Field(rs.getString("equipment_id"), rs.getString("source_id"), rs.getString("pending_id"),
                    rs.getString("normalized_value"), rs.getString("field_status"), rs.getObject("last_valid_at_ms", Long.class),
                    rs.getString("building_id"), rs.getInt("mapping_version"), rs.getString("last_attempt_building_id"),
                    rs.getInt("last_attempt_mapping_version"));
            result.computeIfAbsent(rs.getString("identity_id"), ignored -> new HashMap<>()).put(rs.getString("field_name"), field);
        }, buildingId);
        return result;
    }

    private static String unavailable(Field field, Target target, long now) {
        if (field == null) return "MISSING";
        if (!target.equipmentId().equals(field.equipmentId()) || !target.key().sourceId().equals(field.sourceId())
                || !target.pendingId().equals(field.pendingId()) || !target.buildingId().equals(field.buildingId())
                || !target.buildingId().equals(field.attemptBuilding()) || target.mappingVersion() != field.mapping()
                || target.mappingVersion() != field.attemptMapping()) return "OWNERSHIP_CHANGED";
        if (!"PRESENT".equals(field.status())) return Set.of("MISSING", "UNKNOWN", "INVALID", "UNCONFIRMED").contains(field.status())
                ? field.status() : "INVALID";
        if (field.validAt() == null || field.value() == null) return "MISSING";
        if (field.validAt() > now) return "INVALID_TIME";
        return field.validAt() < now - FRESH_MS ? "STALE" : null;
    }

    private static boolean matches(Field field, Target target, long now, String expected) {
        return unavailable(field, target, now) == null && expected.equals(field.value());
    }

    private Map<String, Space> spaces(String buildingId) {
        Map<String, Space> result = new HashMap<>();
        jdbc.query("SELECT space_id,space_name,parent_space_id,space_type FROM biz_space WHERE building_id=? AND del_flag=0",
                rs -> { result.put(rs.getString(1), new Space(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))); }, buildingId);
        return result;
    }

    private static String floor(Space space, Map<String, Space> spaces) {
        Set<String> visited = new HashSet<>();
        while (space != null && visited.add(space.id())) {
            if ("FLOOR".equals(space.type())) return space.name();
            space = spaces.get(space.parent());
        }
        return null;
    }

    private static void increment(Map<String, Long> counts, String reason) { counts.merge(reason, 1L, Long::sum); }
    private static BusinessException invalidMonth() {
        return new BusinessException(400, "DAIKIN_MONITORING_INVALID_PARAMETER", "月份必须处于完整保留范围内且不晚于本月，limit须为1到100");
    }

    private record Field(String equipmentId, String sourceId, String pendingId, String value, String status, Long validAt,
                         String buildingId, int mapping, String attemptBuilding, int attemptMapping) { }
    private record Day(String identityId, int mapping, long start, long on, long covered) { }
    private record Space(String id, String name, String parent, String type) { }

    private static final class CategoryCounter {
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final Map<String, Long> reasons = new LinkedHashMap<>();
        private long observed;
        CategoryCounter(List<String> values) { for (String value : values) counts.put(value, 0L); }

        void add(Field field, Target target, long now) {
            String reason = unavailable(field, target, now);
            // 可设置选项不代表监测支持范围，空列表不能将缺失观测升级为“不支持”。
            // 只使用正式状态值分类，不由控制能力推断、折叠或补造监测状态。
            if (reason != null) {
                increment(reasons, reason);
            } else if (!counts.containsKey(field.value())) {
                increment(reasons, "UNKNOWN_VALUE");
            } else {
                increment(counts, field.value()); observed++;
            }
        }

        Categories result(long on) {
            return new Categories(on > 0 && observed == 0 ? Map.of() : Map.copyOf(counts), observed,
                    on - observed, 0, Map.copyOf(reasons));
        }
    }
}
