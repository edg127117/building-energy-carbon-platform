package com.platform.iot.daikin.monitoring;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.monitoring.query.DaikinMonitoringQueryService;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointAliasKey;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.temporal.HvacRawEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 独立于继承冷站31天限制的温度查询。先验证建筑权限和正式测点，再做单点有界查询；
 * 每个点按实际质量和场景策略决定是否展示，缺口只标记不补造读数。
 */
@Service
public class DaikinTemperatureQueryService {
    private static final long RETENTION = 90L * 24 * 60 * 60 * 1000;
    private final JdbcTemplate jdbc;
    private final DaikinMonitoringQueryService access;
    private final DataPointConfigProvider points;
    private final HvacRawEventRepository raw;
    private final QualityUsagePolicyResolver quality;
    private final Clock clock;

    @Autowired
    public DaikinTemperatureQueryService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            DaikinMonitoringQueryService access, DataPointConfigProvider points,
            HvacRawEventRepository raw, QualityUsagePolicyResolver quality) {
        this(jdbc, access, points, raw, quality, Clock.systemUTC());
    }

    DaikinTemperatureQueryService(JdbcTemplate jdbc, DaikinMonitoringQueryService access,
            DataPointConfigProvider points, HvacRawEventRepository raw, QualityUsagePolicyResolver quality, Clock clock) {
        this.jdbc = jdbc; this.access = access; this.points = points; this.raw = raw; this.quality = quality; this.clock = clock;
    }

    public History history(Long userId, Set<String> roles, String equipmentId, String field,
            long from, long to, Long after, int limit) {
        String building = access.requireEquipment(userId, roles, equipmentId);
        long now = clock.millis();
        if (from < 0 || from >= to || to - from > RETENTION || to > now + 1
                || limit < 1 || limit > 1000 || after != null && (after < from || after >= to)) throw invalid();
        PointRuntimeConfig point = point(building, equipmentId, field);
        // 请求发出与翻页期间时钟继续前进；裁剪滚动保留边界，不能让90天窗口的第二页因几毫秒偏移失效。
        long retainedFrom = Math.max(from, now - RETENTION);
        if (retainedFrom >= to) return new History(field, point.pointId(), "°C", List.of(), null, "PLATFORM_OBSERVED_AT");
        var rows = raw.findPointHistory(building, equipmentId, point.pointId(), retainedFrom, to, after, limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.stream().limit(limit).toList();
        var context = quality.historyContext(Set.of(point.pointId()), "POINT_HISTORY_VIEW", retainedFrom / 60000 * 60000, to);
        var values = new ArrayList<Reading>();
        Long previous = after == null ? from : after;
        for (var event : page) {
            // 二次核对仓储返回的归属，防止错误实现把别的建筑历史带到已授权设备下。
            if (!building.equals(event.buildingId()) || !equipmentId.equals(event.equipId())
                    || !point.pointId().equals(event.pointId()) || !DaikinTemperatureIngestion.SOURCE_SYSTEM.equals(event.sourceSystem())) {
                throw new IllegalStateException("DAIKIN_HISTORY_IDENTITY_MISMATCH");
            }
            Resolution decision = quality.resolve(context, point.pointId(), "POINT_HISTORY_VIEW",
                    event.eventTime() / 60000 * 60000, event.dataQuality());
            values.add(new Reading(event.eventTime(), decision.decision() == Decision.ALLOW ? event.value() : null,
                    event.dataQuality(), decision, previous != null && event.eventTime() - previous > 300000,
                    now - event.eventTime() > 300000));
            previous = event.eventTime();
        }
        return new History(field, point.pointId(), "°C", List.copyOf(values), more ? previous : null, "PLATFORM_OBSERVED_AT");
    }

    public Current current(Long userId, Set<String> roles, String equipmentId, String field) {
        String building = access.requireEquipment(userId, roles, equipmentId);
        PointRuntimeConfig point = point(building, equipmentId, field);
        var states = jdbc.query("SELECT field_status,last_valid_at_ms FROM biz_daikin_current_state WHERE equipment_id=? AND building_id=? AND field_name=? ORDER BY last_attempt_at_ms DESC LIMIT 1",
                (rs, row) -> new CurrentState(rs.getString(1), rs.getObject(2, Long.class)), equipmentId, building, field);
        if (states.isEmpty() || states.getFirst().observedAt() == null) {
            return new Current(field, point.pointId(), "°C", null, states.isEmpty() ? "MISSING" : states.getFirst().status(), "PLATFORM_OBSERVED_AT");
        }
        var saved = states.getFirst();
        // 只查询已经完成MySQL检查点的那次厂家观测；同测点其它来源或尚在跨库重试的更新不能冒充当前状态。
        var readings = raw.findPointHistory(building, equipmentId, point.pointId(), saved.observedAt(), saved.observedAt() + 1, null, 1);
        if (readings.isEmpty()) return new Current(field, point.pointId(), "°C", null, "HISTORY_UNAVAILABLE", "PLATFORM_OBSERVED_AT");
        var event = readings.getFirst();
        Resolution decision = quality.resolve(point.pointId(), "POINT_REALTIME_VIEW", event.eventTime() / 60000 * 60000, event.dataQuality());
        return new Current(field, point.pointId(), "°C", new Reading(event.eventTime(),
                decision.decision() == Decision.ALLOW ? event.value() : null, event.dataQuality(), decision, false,
                clock.millis() - event.eventTime() > 300000), saved.status(), "PLATFORM_OBSERVED_AT");
    }

    private PointRuntimeConfig point(String building, String equipment, String field) {
        if (!DaikinTemperatureIngestion.TEMPERATURE_FIELDS.contains(field)) throw invalid();
        var identities = jdbc.queryForList("SELECT identity_value FROM biz_device_identity WHERE equip_id=? AND building_id=? AND identity_type='DAIKIN_UNIT'",
                String.class, equipment, building);
        if (identities.size() != 1) throw missing();
        var point = points.find(new PointAliasKey(building, DaikinTemperatureIngestion.SOURCE_SYSTEM,
                "DAIKIN_UNIT:" + identities.getFirst() + ":" + field)).orElseThrow(DaikinTemperatureQueryService::missing);
        if (!equipment.equals(point.equipId()) || !building.equals(point.buildingId()) || point.isForCalc() != 0
                || !"°C".equals(point.unit()) || !"AI".equals(point.dataType())) throw missing();
        return point;
    }

    private static BusinessException invalid() { return new BusinessException(400, "DAIKIN_QUERY_INVALID", "温度查询参数无效，范围须在最近90天内"); }
    private static BusinessException missing() { return new BusinessException(404, "DAIKIN_TEMPERATURE_NOT_BOUND", "未配置该温度测点"); }
    public record Reading(long observedAt, Double value, int dataQuality, Resolution quality, boolean gapBefore, boolean stale) { }
    public record History(String field, String pointId, String unit, List<Reading> items, Long nextCursor, String timeSource) { }
    public record Current(String field, String pointId, String unit, Reading reading, String fieldStatus, String timeSource) { }
    private record CurrentState(String status, Long observedAt) { }
}
