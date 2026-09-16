package com.platform.iot.daikin.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.monitoring.query.DaikinMonitoringQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 单设备历史只读查询：每次校验菜单/建筑，按当前身份与映射过滤归属快照；绝不将厂家历史归到房间汇总。 */
@Service
public class DaikinRuntimeQueryService {
    private final JdbcTemplate jdbc;
    private final DaikinMonitoringQueryService access;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public DaikinRuntimeQueryService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            DaikinMonitoringQueryService access, ObjectMapper mapper) {
        this(jdbc, access, mapper, Clock.systemUTC());
    }

    DaikinRuntimeQueryService(JdbcTemplate jdbc, DaikinMonitoringQueryService access, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.mapper = mapper;
        this.clock = clock;
    }

    public RuntimePage values(Long user, Set<String> roles, String equipment, String granularity, String cursor, int limit) {
        var scope = access.requireRuntimeEquipment(user, roles, equipment);
        validate(granularity, cursor, limit);
        var rows = jdbc.query("""
                SELECT * FROM biz_daikin_runtime_value WHERE equipment_id=? AND identity_id=? AND building_id=?
                  AND mapping_version=? AND granularity=? AND period_end_ms>=? AND value_id>?
                ORDER BY value_id LIMIT ?
                """, (rs, row) -> new RuntimeValue(rs.getString("value_id"), granularity, rs.getLong("period_start_ms"),
                rs.getLong("period_end_ms"), rs.getString("statistics_zone"), rs.getString("unit"),
                metrics(rs.getString("metrics_json")), rs.getBoolean("period_complete"), rs.getBoolean("ownership_verified"),
                rs.getInt("revision_no"), rs.getObject("last_success_at_ms", Long.class), rs.getLong("last_attempt_at_ms"),
                rs.getString("last_attempt_status")), equipment, scope.identityId(), scope.buildingId(), scope.mappingVersion(),
                granularity, clock.millis() - DaikinRuntimeScheduler.YEAR_MS, cursor == null ? "" : cursor, limit + 1);
        var jobs = jdbc.query("""
                SELECT j.status,COUNT(*) FROM biz_daikin_runtime_job j
                JOIN biz_daikin_directory d ON d.source_id=j.source_id AND d.device_kind=j.device_kind
                JOIN biz_pending_device p ON p.pending_id=d.pending_id AND p.bound_identity_id=? AND p.status='BOUND'
                WHERE j.granularity=? AND j.period_end_ms>=? GROUP BY j.status
                """, (rs, row) -> new SyncCount(rs.getString(1), rs.getLong(2)), scope.identityId(), granularity,
                clock.millis() - DaikinRuntimeScheduler.YEAR_MS);
        boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new RuntimePage(items, more ? items.getLast().valueId() : null, jobs);
    }

    public RevisionPage revisions(Long user, Set<String> roles, String equipment, String valueId, int after, int limit) {
        var scope = access.requireRuntimeEquipment(user, roles, equipment);
        if (valueId == null || !valueId.matches("[a-f0-9]{64}") || after < 0 || limit < 1 || limit > 100) throw invalid();
        var rows = jdbc.query("""
                SELECT revision_no,metrics_json,period_complete,ownership_verified,observed_at_ms,unit,statistics_zone,
                  period_start_ms,period_end_ms,granularity
                FROM biz_daikin_runtime_revision WHERE value_id=? AND equipment_id=? AND identity_id=? AND building_id=?
                  AND mapping_version=? AND period_end_ms>=? AND revision_no>? ORDER BY revision_no LIMIT ?
                """, (rs, row) -> new RuntimeRevision(rs.getInt(1), metrics(rs.getString(2)), rs.getBoolean(3), rs.getBoolean(4),
                rs.getLong(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9), rs.getString(10)),
                valueId, equipment, scope.identityId(), scope.buildingId(), scope.mappingVersion(),
                clock.millis() - DaikinRuntimeScheduler.YEAR_MS, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new RevisionPage(items, more ? items.getLast().revision() : null);
    }

    private static void validate(String grain, String cursor, int limit) {
        if (!Set.of("DAY", "MONTH", "YEAR").contains(grain) || limit < 1 || limit > 100
                || cursor != null && !cursor.matches("[a-f0-9]{64}")) throw invalid();
    }

    private Map<String, BigDecimal> metrics(String json) {
        if (json == null) return null;
        try { return mapper.readValue(json, new TypeReference<Map<String, BigDecimal>>() { }); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("DAIKIN_RUNTIME_STORED_METRICS_INVALID", invalid); }
    }

    private static BusinessException invalid() {
        return new BusinessException(400, "DAIKIN_MONITORING_INVALID_PARAMETER", "运行统计查询参数无效");
    }

    public record RuntimeValue(String valueId, String granularity, long periodStart, long periodEnd,
                               String statisticsZone, String unit, Map<String, BigDecimal> metrics,
                               boolean periodComplete, boolean ownershipVerified, int revision,
                               Long lastSuccessAt, long lastAttemptAt, String lastAttemptStatus) { }
    public record RuntimeRevision(int revision, Map<String, BigDecimal> metrics, boolean periodComplete,
                                  boolean ownershipVerified, long observedAt, String unit, String statisticsZone,
                                  long periodStart, long periodEnd, String granularity) { }
    public record SyncCount(String status, long periods) { }
    public record RuntimePage(List<RuntimeValue> items, String nextCursor, List<SyncCount> synchronization) { }
    public record RevisionPage(List<RuntimeRevision> items, Integer nextCursor) { }
}
