package com.platform.iot.comfort;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.monitoring.DaikinTemperatureQueryService;
import com.platform.iot.daikin.monitoring.DaikinTemperatureQueryService.Current;
import com.platform.iot.daikin.monitoring.DaikinTemperatureQueryService.Reading;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.service.BuildingScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** 从可信房间绑定出发查询室温，温度值、质量判定与历史读取沿用大金监测链路。 */
@Service
public class ComfortQueryService {
    private static final String MENU = "/operations/realtime/comfort";
    private static final String ROOM_TEMP = "roomTemp";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 10;

    private static final String ROOM_SELECT = """
            SELECT p.space_id,p.space_name,p.building_id,f.space_name AS floor_name,
                   EXISTS (
                       SELECT 1
                       FROM biz_equipment e
                       JOIN biz_device_identity i ON i.equip_id=e.equip_id AND i.building_id=e.building_id
                           AND i.status=1 AND i.identity_type='MAC'
                       JOIN biz_room_sensor_source s ON s.identity_id=i.identity_id AND s.enabled=1
                       WHERE e.building_id=p.building_id AND e.space_id=p.space_id AND e.del_flag=0
                           AND ((s.protocol='MSR01_RADAR' AND i.expected_profile_code='MSR01_RADAR')
                             OR (s.protocol='S4_DOOR' AND i.expected_profile_code='S4_DOOR'))
                   ) AS sensor_bound
            FROM biz_space p
            LEFT JOIN biz_space f ON f.space_id=p.parent_space_id AND f.building_id=p.building_id
                AND f.space_type='FLOOR' AND f.del_flag=0
            WHERE p.space_type='ROOM' AND p.del_flag=0
            """;

    private final JdbcTemplate mysql;
    private final BuildingScopeService buildings;
    private final SysMenuMapper menus;
    private final DaikinTemperatureQueryService temperatures;
    private final Set<String> pilotSpaceIds;
    private final Clock clock;

    @Autowired
    public ComfortQueryService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
                               BuildingScopeService buildings, SysMenuMapper menus,
                               DaikinTemperatureQueryService temperatures,
                               @Value("${comfort.pilot-space-ids:}") List<String> pilotSpaceIds) {
        this(mysql, buildings, menus, temperatures, Set.copyOf(pilotSpaceIds), Clock.systemUTC());
    }

    ComfortQueryService(JdbcTemplate mysql, BuildingScopeService buildings, SysMenuMapper menus,
                        DaikinTemperatureQueryService temperatures, Set<String> pilotSpaceIds, Clock clock) {
        this.mysql = mysql;
        this.buildings = buildings;
        this.menus = menus;
        this.temperatures = temperatures;
        this.pilotSpaceIds = Set.copyOf(pilotSpaceIds);
        this.clock = clock;
    }

    public List<Space> spaces(Long userId, Set<String> roles, String buildingId) {
        requireMenu(userId, roles);
        buildings.checkAccess(userId, roles, buildingId);
        return rooms("""
                AND p.building_id=? ORDER BY COALESCE(f.floor_level,p.floor_level),
                    COALESCE(f.space_code,f.space_name), COALESCE(p.space_code,p.space_name), p.space_id
                """, buildingId).stream()
                .map(Room::space)
                .toList();
    }

    public Overview overview(Long userId, Set<String> roles, String spaceId, String equipmentId) {
        return overview(room(userId, roles, spaceId), equipmentId, clock.millis(),
                source -> readCurrent(() -> temperatures.current(userId, roles, source.equipmentId(), ROOM_TEMP)));
    }

    /** 仅供建筑报警任务枚举当前纳入监测的房间。 */
    public List<Space> alarmSpaces(String buildingId) {
        return rooms("AND p.building_id=? ORDER BY p.space_name,p.space_id", buildingId).stream()
                .map(Room::space).toList();
    }

    long now() {
        return clock.millis();
    }

    Overview alarmOverview(Space room, long asOf) {
        return overview(room, null, asOf, source ->
                readCurrent(() -> temperatures.currentForBuilding(room.buildingId(), source.equipmentId(), ROOM_TEMP, asOf)));
    }

    private Overview overview(Space room, String equipmentId, long asOf, Function<TemperatureSource, Current> read) {
        var sources = sources(room);
        var source = selectSource(sources, equipmentId);
        if (source == null && !sources.isEmpty()) {
            var readings = new ArrayList<Reading>();
            var lastValid = new ArrayList<Reading>();
            for (var unit : sources) {
                var current = read.apply(unit);
                var reading = currentReading(current, unit);
                if (reading != null && validValue(reading) && reading.observedAt() <= asOf) {
                    lastValid.add(reading);
                    if (!reading.stale() && "PRESENT".equals(current.fieldStatus())) readings.add(reading);
                }
            }
            verifyAverageBindings(room, sources);
            return new Overview(room, sources, null, "ROOM_AVERAGE", null, null, average(readings),
                    readings.isEmpty() ? lastRoomTemperature(lastValid, asOf) : null);
        }
        var current = source == null ? null : read.apply(source);
        var reading = source == null ? null : currentReading(current, source);
        verifyCurrentBinding(room, source);
        return new Overview(room, sources, source, selectionStatus(sources, source),
                current == null ? null : current.pointId(),
                current == null ? null : current.fieldStatus(),
                reading == null ? null : observation(reading), null);
    }

    public Trends trends(Long userId, Set<String> roles, String spaceId, String equipmentId) {
        var room = room(userId, roles, spaceId);
        var sources = sources(room);
        var source = selectSource(sources, equipmentId);
        var now = clock.instant();
        LocalDate day = now.atZone(ZONE).toLocalDate();
        long start = day.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long end = Math.min(day.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli(), now.toEpochMilli() + 1);
        if (source == null && !sources.isEmpty()) {
            var unitSeries = new ArrayList<Reading[]>();
            for (var unit : sources) {
                Reading[] byHour = new Reading[24];
                loadHourly(userId, roles, unit, start, end, byHour, true);
                unitSeries.add(byHour);
            }
            verifyAverageBindings(room, sources);
            var hours = new ArrayList<Hour>(24);
            for (int hour = 0; hour < 24; hour++) {
                int slot = hour;
                var readings = unitSeries.stream().map(unit -> unit[slot]).filter(Objects::nonNull).toList();
                hours.add(new Hour(hourStart(day, hour), average(readings)));
            }
            return new Trends(room, null, "ROOM_AVERAGE", null, day, ZONE.getId(), List.copyOf(hours));
        }
        Reading[] byHour = new Reading[24];
        String pointId = source == null ? null : loadHourly(userId, roles, source, start, end, byHour, false);
        verifyCurrentBinding(room, source);
        var hours = new ArrayList<Hour>(24);
        for (int hour = 0; hour < 24; hour++) {
            Reading reading = byHour[hour];
            hours.add(new Hour(hourStart(day, hour), reading == null ? null : observation(reading)));
        }
        return new Trends(room, source, selectionStatus(sources, source), pointId, day, ZONE.getId(), List.copyOf(hours));
    }

    private List<Room> rooms(String filter, String id) {
        return mysql.query(ROOM_SELECT + filter, (rs, row) -> new Room(
                new Space(rs.getString("space_id"), rs.getString("space_name"), rs.getString("building_id"),
                        rs.getString("floor_name")), rs.getBoolean("sensor_bound")), id).stream()
                .filter(room -> room.sensorBound() || pilotSpaceIds.contains(room.space().spaceId()))
                .toList();
    }

    /** 统一校验舒适度菜单和房间所属建筑，供同模块的只读接口复用。 */
    Space room(Long userId, Set<String> roles, String spaceId) {
        requireMenu(userId, roles);
        return rooms("AND p.space_id=?", spaceId).stream().map(Room::space)
                .filter(space -> buildings.canAccess(userId, roles, space.buildingId()))
                .findFirst().orElseThrow(() -> new BusinessException(404, "COMFORT_SPACE_NOT_FOUND", "监测房间不可用"));
    }

    private List<TemperatureSource> sources(Space space) {
        // 现有设备归属与采集目标快照都须指向该房间，避免换绑过渡期拼接不同空间的观测。
        return mysql.query("""
                SELECT e.equip_id,e.equip_name,t.first_planned_at_ms,t.mapping_version
                FROM biz_equipment e
                JOIN biz_daikin_monitoring_target t ON t.equipment_id=e.equip_id AND t.building_id=e.building_id
                    AND t.space_id=e.space_id AND t.active=1
                JOIN biz_device_identity i ON i.identity_id=t.identity_id AND i.equip_id=e.equip_id
                    AND i.building_id=e.building_id AND i.identity_type='DAIKIN_UNIT' AND i.status=1
                JOIN biz_daikin_directory d ON d.pending_id=t.pending_id AND d.source_id=t.source_id
                    AND d.device_kind='INDOOR'
                WHERE e.building_id=? AND e.space_id=? AND e.del_flag=0
                ORDER BY e.equip_name,e.equip_id
                """, (rs, row) -> new TemperatureSource(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4)),
                space.buildingId(), space.spaceId());
    }

    private void verifyCurrentBinding(Space space, TemperatureSource source) {
        // 温度读取跨 MySQL 和 TDengine，返回前再核对绑定，避免中途换绑后把观测归错房间。
        if (source != null && !sources(space).contains(source)) {
            throw sourceChanged();
        }
    }

    private void verifyAverageBindings(Space space, List<TemperatureSource> original) {
        if (!sources(space).equals(original)) throw sourceChanged();
    }

    private static BusinessException sourceChanged() {
        return new BusinessException(409, "COMFORT_TEMPERATURE_SOURCE_CHANGED", "室温测点已变更，请重新查询");
    }

    private static TemperatureSource selectSource(List<TemperatureSource> sources, String equipmentId) {
        if (equipmentId == null) {
            return sources.size() == 1 ? sources.getFirst() : null;
        }
        return sources.stream().filter(source -> source.equipmentId().equals(equipmentId))
                .findFirst().orElseThrow(() -> new BusinessException(
                        404, "COMFORT_TEMPERATURE_SOURCE_NOT_FOUND", "所选内机不是该房间的室温来源"));
    }

    private Current readCurrent(Supplier<Current> read) {
        try {
            return read.get();
        } catch (BusinessException ex) {
            if ("DAIKIN_TEMPERATURE_NOT_BOUND".equals(ex.getErrorCode())) return null;
            throw ex;
        }
    }

    private String loadHourly(Long userId, Set<String> roles, TemperatureSource source,
                              long start, long end, Reading[] byHour, boolean validOnly) {
        // 室温时序只保存设备归属，房间趋势从现行监测绑定启用时刻起取当日数据。
        long from = Math.max(start, source.bindingStartedAt());
        if (from >= end) return null;
        Long cursor = null;
        String pointId = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            DaikinTemperatureQueryService.History result;
            try {
                result = temperatures.history(userId, roles, source.equipmentId(), ROOM_TEMP, from, end, cursor, PAGE_SIZE);
            } catch (BusinessException ex) {
                if (page == 0 && "DAIKIN_TEMPERATURE_NOT_BOUND".equals(ex.getErrorCode())) return null;
                throw ex;
            }
            if (pointId != null && !pointId.equals(result.pointId())) throw sourceChanged();
            pointId = result.pointId();
            // 房间均值每小时每台内机取最后一条有效读数，避免上报更勤的设备被重复计权。
            // 单台测点查询保留原有语义，最后一条观测被质量策略屏蔽时仍返回其采样时间。
            for (var reading : result.items()) {
                if (validOnly && !validValue(reading)) continue;
                int hour = Instant.ofEpochMilli(reading.observedAt()).atZone(ZONE).getHour();
                byHour[hour] = reading;
            }
            cursor = result.nextCursor();
            if (cursor == null) return pointId;
        }
        throw new BusinessException(409, "COMFORT_TEMPERATURE_HISTORY_LIMIT", "当日室温观测超过查询上限");
    }

    private void requireMenu(Long userId, Set<String> roles) {
        if (userId != null && roles != null && (roles.contains("PLATFORM_ADMIN")
                || menus.selectVisibleMenusByUserId(userId).stream()
                    .anyMatch(menu -> "C".equals(menu.getMenuType()) && MENU.equals(menu.getPath())))) {
            return;
        }
        throw new BusinessException(403, "COMFORT_FORBIDDEN", "无权查看人体舒适度监控");
    }

    private static String selectionStatus(List<TemperatureSource> sources, TemperatureSource selected) {
        return selected != null ? "SELECTED" : sources.isEmpty() ? "UNCONFIGURED" : "ROOM_AVERAGE";
    }

    private static Reading currentReading(Current current, TemperatureSource source) {
        return current == null || current.reading() == null || current.reading().observedAt() < source.bindingStartedAt()
                ? null : current.reading();
    }

    private static boolean validValue(Reading reading) {
        return reading.value() != null && Double.isFinite(reading.value());
    }

    private static Instant hourStart(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(ZONE).toInstant();
    }

    private static TemperatureObservation average(List<Reading> readings) {
        if (readings.isEmpty()) return null;
        double value = readings.stream().mapToDouble(Reading::value).average().orElseThrow();
        long latest = readings.stream().mapToLong(Reading::observedAt).max().orElseThrow();
        return new TemperatureObservation(value, Instant.ofEpochMilli(latest), false);
    }

    private static TemperatureObservation lastRoomTemperature(List<Reading> readings, long asOf) {
        if (readings.isEmpty()) return null;
        long latest = readings.stream().mapToLong(Reading::observedAt).max().orElseThrow();
        // 以上次观测时间为基准沿用内机有效期，防止将不同采样时期的旧值拼成房间均值。
        double value = readings.stream().filter(reading -> !DaikinTemperatureQueryService.isStaleAt(reading.observedAt(), latest))
                .mapToDouble(Reading::value).average().orElseThrow();
        return new TemperatureObservation(value, Instant.ofEpochMilli(latest),
                DaikinTemperatureQueryService.isStaleAt(latest, asOf));
    }

    private static TemperatureObservation observation(Reading reading) {
        return new TemperatureObservation(reading.value(), Instant.ofEpochMilli(reading.observedAt()), reading.stale());
    }

    private record Room(Space space, boolean sensorBound) {}
    public record Space(String spaceId, String spaceName, String buildingId, String floorName) {}
    public record TemperatureSource(String equipmentId, String equipmentName, long bindingStartedAt, int mappingVersion) {}
    public record TemperatureObservation(Double celsius, Instant observedAt, boolean stale) {}
    public record Overview(Space space, List<TemperatureSource> temperatureSources, TemperatureSource selectedSource,
                           String temperatureSourceStatus, String pointId, String fieldStatus,
                           TemperatureObservation indoorTemperature, TemperatureObservation lastValidIndoorTemperature) {}
    public record Hour(Instant startsAt, TemperatureObservation indoorTemperature) {}
    public record Trends(Space space, TemperatureSource selectedSource, String temperatureSourceStatus,
                         String pointId, LocalDate date, String timeZone, List<Hour> hours) {}
}
