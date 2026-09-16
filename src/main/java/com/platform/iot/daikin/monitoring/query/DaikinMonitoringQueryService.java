package com.platform.iot.daikin.monitoring.query;

import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.service.BuildingScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static com.platform.iot.daikin.monitoring.query.DaikinMonitoringQueryDtos.*;

/**
 * 大金监测查询的数据权限边界。每次请求同时校验 HVAC 叶子菜单和当前建筑授权；SQL继续按
 * 采集时归属快照过滤事件与设备异常，来源异常仅通过本建筑监测目标建立可见关联。
 */
@Service
public class DaikinMonitoringQueryService {
    private static final Set<String> HVAC_MENUS = Set.of("/operations/realtime/hvac", "/hvac-demo");
    private static final long ONE_YEAR_MS = Duration.ofDays(365).toMillis();
    private static final long STALE_AFTER_MS = Duration.ofMinutes(5).toMillis();
    private final JdbcTemplate jdbc;
    private final BuildingScopeService buildings;
    private final SysMenuMapper menus;
    private final Clock clock;

    @Autowired
    public DaikinMonitoringQueryService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                        BuildingScopeService buildings, SysMenuMapper menus) {
        this(jdbc, buildings, menus, Clock.systemUTC());
    }

    DaikinMonitoringQueryService(JdbcTemplate jdbc, BuildingScopeService buildings,
                                 SysMenuMapper menus, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.buildings = Objects.requireNonNull(buildings);
        this.menus = Objects.requireNonNull(menus);
        this.clock = Objects.requireNonNull(clock);
    }

    public PageResponse<DeviceListItem> devices(Long userId, Set<String> roles, String buildingId,
                                                 int page, int size, String spaceId, String kind) {
        requireBuilding(userId, roles, buildingId);
        requirePage(page, size);
        String validSpace = optionalId(spaceId, "spaceId");
        String validKind = optionalKind(kind);
        StringBuilder filters = new StringBuilder(" WHERE e.building_id=?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(buildingId);
        if (validSpace != null) {
            filters.append(" AND e.space_id=?");
            parameters.add(validSpace);
        }
        if (validKind != null) {
            filters.append(" AND d.device_kind=?");
            parameters.add(validKind);
        }
        String joins = """
                 FROM biz_daikin_monitoring_target t
                 JOIN biz_equipment e ON e.equip_id=t.equipment_id AND e.del_flag=0
                 JOIN biz_device_identity i ON i.identity_id=t.identity_id AND i.equip_id=e.equip_id
                      AND i.building_id=e.building_id AND i.identity_type='DAIKIN_UNIT'
                 JOIN biz_daikin_directory d ON d.pending_id=t.pending_id AND d.source_id=t.source_id
                 """;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + joins + filters,
                Long.class, parameters.toArray());
        long offset = Math.multiplyExact((long) page - 1, size);
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add(offset);
        List<DeviceListItem> items = jdbc.query("""
                SELECT t.identity_id,t.equipment_id,t.pending_id,e.building_id,e.space_id,e.system_group_id,
                       d.device_kind,t.mapping_version,t.active,i.status AS identity_status,
                       t.first_planned_at_ms,
                       (SELECT MAX(runtime.last_valid_at_ms) FROM biz_daikin_current_state runtime
                         WHERE runtime.identity_id=t.identity_id AND runtime.building_id=e.building_id
                           AND runtime.mapping_version=t.mapping_version
                           AND runtime.field_name IN ('onOff','mode','unitStatus','fanSpeed','airflowDirection',
                             'roomTemp','temperature','errorType','errorCode','inCommunicationError',
                             'inEquipmentError','inMantenanceMode','isFilterDirty',
                             'controller.isConnectionUp','controller.inForcedStop','compressorOnOff'))
                         AS current_last_valid_at,
                       onoff.normalized_value AS onoff_value,onoff.field_status AS onoff_status,
                       onoff.last_valid_at_ms AS onoff_last_valid,
                       onoff.building_id AS onoff_building,onoff.mapping_version AS onoff_mapping,
                       onoff.last_attempt_building_id AS onoff_attempt_building,
                       onoff.last_attempt_mapping_version AS onoff_attempt_mapping,
                       mode.normalized_value AS mode_value,mode.field_status AS mode_status,
                       mode.last_valid_at_ms AS mode_last_valid,
                       mode.building_id AS mode_building,mode.mapping_version AS mode_mapping,
                       mode.last_attempt_building_id AS mode_attempt_building,
                       mode.last_attempt_mapping_version AS mode_attempt_mapping,
                       unitstate.normalized_value AS unitstate_value,unitstate.field_status AS unitstate_status,
                       unitstate.last_valid_at_ms AS unitstate_last_valid,
                       unitstate.building_id AS unitstate_building,unitstate.mapping_version AS unitstate_mapping,
                       unitstate.last_attempt_building_id AS unitstate_attempt_building,
                       unitstate.last_attempt_mapping_version AS unitstate_attempt_mapping,
                       CASE WHEN EXISTS (SELECT 1 FROM biz_daikin_exception_instance x
                         WHERE x.identity_id=t.identity_id AND x.building_id=e.building_id
                           AND x.active_key IS NOT NULL) THEN 1 ELSE 0 END AS has_exception
                """ + joins + """
                 LEFT JOIN biz_daikin_current_state onoff
                   ON onoff.identity_id=t.identity_id AND onoff.field_name='onOff'
                 LEFT JOIN biz_daikin_current_state mode
                   ON mode.identity_id=t.identity_id AND mode.field_name='mode'
                 LEFT JOIN biz_daikin_current_state unitstate
                   ON unitstate.identity_id=t.identity_id AND unitstate.field_name='unitStatus'
                """ + filters + " ORDER BY t.equipment_id,t.identity_id LIMIT ? OFFSET ?",
                (rs, row) -> {
                    Long lastValid = nullableLong(rs, "current_last_valid_at");
                    long firstPlanned = rs.getLong("first_planned_at_ms");
                    long freshness = lastValid == null ? firstPlanned : Math.max(firstPlanned, lastValid);
                    StateSummaryView onOff = summary(rs, "onoff", rs.getString("building_id"),
                            rs.getInt("mapping_version"));
                    StateSummaryView mode = summary(rs, "mode", rs.getString("building_id"),
                            rs.getInt("mapping_version"));
                    StateSummaryView unitStatus = summary(rs, "unitstate", rs.getString("building_id"),
                            rs.getInt("mapping_version"));
                    return new DeviceListItem(rs.getString("identity_id"), rs.getString("equipment_id"),
                            rs.getString("pending_id"), rs.getString("building_id"), rs.getString("space_id"),
                            rs.getString("system_group_id"), rs.getString("device_kind"),
                            rs.getInt("mapping_version"), rs.getInt("active") == 1
                                    && rs.getInt("identity_status") == 1,
                            freshness < clock.millis() - STALE_AFTER_MS, lastValid,
                            onOff, mode, unitStatus, rs.getInt("has_exception") == 1);
                }, pageParameters.toArray());
        return new PageResponse<>(page, size, total == null ? 0 : total, items);
    }

    /** 供同模块温度接口复用，返回已通过菜单、正式目标和建筑范围复核的建筑ID。 */
    public String requireEquipment(Long userId, Set<String> roles, String equipmentId) {
        return equipmentTarget(userId, roles, equipmentId).buildingId();
    }

    public DeviceCurrentView current(Long userId, Set<String> roles, String equipmentId) {
        EquipmentTarget target = equipmentTarget(userId, roles, equipmentId);
        List<CurrentFieldView> fields = jdbc.query("""
                SELECT field_name,raw_json,normalized_value,field_status,last_valid_at_ms,
                       last_attempt_raw_json,last_attempt_at_ms,building_id,mapping_version,
                       last_attempt_building_id,last_attempt_mapping_version
                FROM biz_daikin_current_state
                WHERE identity_id=? AND field_name NOT IN ('roomTemp','temperature')
                  AND last_attempt_building_id=? AND last_attempt_mapping_version=?
                ORDER BY field_name
                """, (rs, row) -> {
            boolean visible = target.buildingId().equals(rs.getString("building_id"))
                    && rs.getInt("mapping_version") == target.mappingVersion();
            Long lastValidAt = visible ? nullableLong(rs, "last_valid_at_ms") : null;
            return new CurrentFieldView(rs.getString("field_name"),
                    visible ? rs.getString("raw_json") : null,
                    visible ? rs.getString("normalized_value") : null,
                    rs.getString("field_status"), lastValidAt,
                    rs.getString("last_attempt_raw_json"), rs.getLong("last_attempt_at_ms"), visible,
                    lastValidAt == null || lastValidAt < clock.millis() - STALE_AFTER_MS,
                    rs.getInt("mapping_version"), rs.getInt("last_attempt_mapping_version"));
        }, target.identityId(), target.buildingId(), target.mappingVersion());
        return new DeviceCurrentView(target.identityId(), equipmentId, target.buildingId(), target.spaceId(),
                target.systemGroupId(), target.mappingVersion(), target.active(), target.lastValidAt(), fields);
    }

    public CursorPage<StateEventView> stateEvents(Long userId, Set<String> roles, String equipmentId,
                                                   String cursor, int limit) {
        EquipmentTarget target = equipmentTarget(userId, roles, equipmentId);
        requireLimit(limit);
        Cursor after = decodeCursor(cursor);
        List<Object> parameters = new ArrayList<>();
        parameters.add(target.identityId());
        parameters.add(target.buildingId());
        parameters.add(clock.millis() - ONE_YEAR_MS);
        String afterSql = "";
        if (after != null) {
            afterSql = " AND (observed_at_ms<? OR (observed_at_ms=? AND event_id<?))";
            parameters.add(after.time());
            parameters.add(after.time());
            parameters.add(after.id());
        }
        parameters.add(limit + 1);
        List<StateEventView> rows = jdbc.query("""
                SELECT event_id,field_name,round_id,before_raw_json,before_normalized_value,
                       after_raw_json,after_normalized_value,previous_observed_at_ms,observed_at_ms,
                       after_gap,building_id,space_id,system_group_id,mapping_version
                FROM biz_daikin_state_event
                WHERE identity_id=? AND building_id=? AND observed_at_ms>=?
                """ + afterSql + " ORDER BY observed_at_ms DESC,event_id DESC LIMIT ?",
                (rs, row) -> new StateEventView(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getLong(8), rs.getLong(9), rs.getInt(10) == 1, rs.getString(11),
                        rs.getString(12), rs.getString(13), rs.getInt(14)), parameters.toArray());
        return eventPage(rows, limit);
    }

    public CursorPage<ExceptionView> currentExceptions(Long userId, Set<String> roles, String buildingId,
                                                        String cursor, int limit) {
        return exceptions(userId, roles, buildingId, cursor, limit, true);
    }

    public CursorPage<ExceptionView> exceptionHistory(Long userId, Set<String> roles, String buildingId,
                                                       String cursor, int limit) {
        return exceptions(userId, roles, buildingId, cursor, limit, false);
    }

    private CursorPage<ExceptionView> exceptions(Long userId, Set<String> roles, String buildingId,
                                                  String cursor, int limit, boolean active) {
        requireBuilding(userId, roles, buildingId);
        requireLimit(limit);
        Cursor after = decodeCursor(cursor);
        String timeColumn = active ? "x.last_detected_at_ms" : "x.recovered_at_ms";
        List<Object> parameters = new ArrayList<>();
        if (!active) parameters.add(clock.millis() - ONE_YEAR_MS);
        parameters.add(buildingId);
        parameters.add(buildingId);
        String afterSql = "";
        if (after != null) {
            afterSql = " AND (" + timeColumn + "<? OR (" + timeColumn + "=? AND x.exception_id<?))";
            parameters.add(after.time());
            parameters.add(after.time());
            parameters.add(after.id());
        }
        parameters.add(limit + 1);
        String activity = active ? "x.active_key IS NOT NULL" : "x.active_key IS NULL AND x.recovered_at_ms>=?";
        String sql = """
                SELECT x.exception_id,x.exception_type,x.scope_type,x.source_id,x.identity_id,
                       x.equipment_id,x.building_id,x.field_name,x.first_detected_at_ms,
                       x.last_detected_at_ms,x.recovered_at_ms,x.last_round_id
                FROM biz_daikin_exception_instance x
                WHERE
                """ + activity + """
                  AND (x.building_id=? OR (x.scope_type='SOURCE' AND EXISTS (
                    SELECT 1 FROM biz_daikin_monitoring_target t
                    JOIN biz_equipment e ON e.equip_id=t.equipment_id AND e.del_flag=0
                    JOIN biz_device_identity i ON i.identity_id=t.identity_id AND i.equip_id=e.equip_id
                      AND i.building_id=e.building_id AND i.identity_type='DAIKIN_UNIT'
                    WHERE t.source_id=x.source_id AND e.building_id=?)))
                """ + afterSql + " ORDER BY " + timeColumn + " DESC,x.exception_id DESC LIMIT ?";
        List<ExceptionView> rows = jdbc.query(sql,
                (rs, row) -> new ExceptionView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        "SOURCE".equals(rs.getString(3)) ? buildingId : rs.getString(7), rs.getString(8),
                        rs.getLong(9), rs.getLong(10), nullableLong(rs, 11), nullableLong(rs, 12)),
                parameters.toArray());
        return exceptionPage(rows, limit);
    }

    private EquipmentTarget equipmentTarget(Long userId, Set<String> roles, String equipmentId) {
        requireMenu(userId, roles);
        requireId(equipmentId, "equipmentId");
        List<EquipmentTarget> rows = jdbc.query("""
                SELECT t.identity_id,e.building_id,e.space_id,e.system_group_id,t.mapping_version,
                       (SELECT MAX(runtime.last_valid_at_ms) FROM biz_daikin_current_state runtime
                         WHERE runtime.identity_id=t.identity_id AND runtime.building_id=e.building_id
                           AND runtime.mapping_version=t.mapping_version
                           AND runtime.field_name IN ('onOff','mode','unitStatus','fanSpeed','airflowDirection',
                             'roomTemp','temperature','errorType','errorCode','inCommunicationError',
                             'inEquipmentError','inMantenanceMode','isFilterDirty',
                             'controller.isConnectionUp','controller.inForcedStop','compressorOnOff'))
                         AS current_last_valid_at,
                       t.active,i.status,d.device_kind
                FROM biz_daikin_monitoring_target t
                JOIN biz_equipment e ON e.equip_id=t.equipment_id AND e.del_flag=0
                JOIN biz_device_identity i ON i.identity_id=t.identity_id AND i.equip_id=e.equip_id
                  AND i.building_id=e.building_id AND i.identity_type='DAIKIN_UNIT'
                JOIN biz_daikin_directory d ON d.pending_id=t.pending_id AND d.source_id=t.source_id
                WHERE e.equip_id=? ORDER BY t.identity_id LIMIT 2
                """, (rs, row) -> new EquipmentTarget(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5), nullableLong(rs, 6),
                rs.getInt(7) == 1 && rs.getInt(8) == 1, rs.getString(9)), equipmentId);
        if (rows.isEmpty()) {
            if (!roles.contains("PLATFORM_ADMIN")) throw forbidden();
            throw error(404, "DAIKIN_MONITORING_EQUIPMENT_NOT_FOUND", "监测设备不存在");
        }
        if (rows.size() != 1) {
            if (!roles.contains("PLATFORM_ADMIN")) throw forbidden();
            throw error(409, "DAIKIN_MONITORING_IDENTITY_CONFLICT", "监测设备身份冲突");
        }
        buildings.checkAccess(userId, roles, rows.get(0).buildingId());
        return rows.get(0);
    }

    private void requireBuilding(Long userId, Set<String> roles, String buildingId) {
        requireMenu(userId, roles);
        requireId(buildingId, "buildingId");
        buildings.checkAccess(userId, roles, buildingId);
    }

    private void requireMenu(Long userId, Set<String> roles) {
        if (userId == null || roles == null) throw forbidden();
        if (roles.contains("PLATFORM_ADMIN")) return;
        if (menus.selectVisibleMenusByUserId(userId).stream()
                .noneMatch(menu -> "C".equals(menu.getMenuType()) && HVAC_MENUS.contains(menu.getPath()))) {
            throw forbidden();
        }
    }

    private static CursorPage<StateEventView> eventPage(List<StateEventView> rows, int limit) {
        boolean more = rows.size() > limit;
        List<StateEventView> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        StateEventView last = more ? items.get(items.size() - 1) : null;
        return new CursorPage<>(items, last == null ? null : encodeCursor(last.observedAt(), last.eventId()));
    }

    private static CursorPage<ExceptionView> exceptionPage(List<ExceptionView> rows, int limit) {
        boolean more = rows.size() > limit;
        List<ExceptionView> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        ExceptionView last = more ? items.get(items.size() - 1) : null;
        long time = last == null ? 0 : last.recoveredAt() == null ? last.lastDetectedAt() : last.recoveredAt();
        return new CursorPage<>(items, last == null ? null : encodeCursor(time, last.exceptionId()));
    }

    private static String encodeCursor(long time, long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (time + ":" + id).getBytes(StandardCharsets.US_ASCII));
    }

    private static Cursor decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            String[] parts = decoded.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            long time = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (time < 0 || id < 1) throw new IllegalArgumentException();
            return new Cursor(time, id);
        } catch (IllegalArgumentException failure) {
            throw error(400, "DAIKIN_MONITORING_INVALID_CURSOR", "游标无效");
        }
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw error(400, "DAIKIN_MONITORING_INVALID_PAGE", "分页参数超出允许范围");
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw error(400, "DAIKIN_MONITORING_INVALID_PAGE", "limit必须在1到100之间");
        }
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw error(400, "DAIKIN_MONITORING_INVALID_REQUEST", name + "无效");
        }
    }

    private static String optionalId(String value, String name) {
        if (value == null || value.isBlank()) return null;
        requireId(value, name);
        return value;
    }

    private static String optionalKind(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!Set.of("INDOOR", "OUTDOOR").contains(normalized)) {
            throw error(400, "DAIKIN_MONITORING_INVALID_REQUEST", "kind无效");
        }
        return normalized;
    }

    private StateSummaryView summary(java.sql.ResultSet rs, String prefix, String buildingId,
                                     int mappingVersion) throws java.sql.SQLException {
        boolean currentAttempt = buildingId.equals(rs.getString(prefix + "_attempt_building"))
                && mappingVersion == rs.getInt(prefix + "_attempt_mapping");
        boolean visibleValue = currentAttempt && buildingId.equals(rs.getString(prefix + "_building"))
                && mappingVersion == rs.getInt(prefix + "_mapping");
        Long lastValidAt = visibleValue ? nullableLong(rs, prefix + "_last_valid") : null;
        return new StateSummaryView(visibleValue ? rs.getString(prefix + "_value") : null,
                currentAttempt ? rs.getString(prefix + "_status") : null, lastValidAt,
                lastValidAt == null || lastValidAt < clock.millis() - STALE_AFTER_MS);
    }

    private static BusinessException forbidden() {
        return error(403, "DAIKIN_MONITORING_FORBIDDEN", "缺少暖通空调监控权限");
    }

    private static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }

    private static Long nullableLong(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private record Cursor(long time, long id) { }
    private record EquipmentTarget(String identityId, String buildingId, String spaceId,
                                   String systemGroupId, int mappingVersion, Long lastValidAt,
                                   boolean active, String deviceKind) { }
}
