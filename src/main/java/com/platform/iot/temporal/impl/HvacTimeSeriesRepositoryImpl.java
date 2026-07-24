package com.platform.iot.temporal.impl;

import com.platform.config.TdengineProperties;
import com.platform.iot.temporal.HvacTimeSeriesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * HVAC 分钟汇总和性能指标查询仓储。
 *
 * <p>逐条事件写入和分钟聚合已经拆到专用仓储；本类只保留后续 COP
 * 需要的正式分钟查询与指标读写，避免重新引入 JVM 内存分钟桶。</p>
 */
@Slf4j
@Service
public class HvacTimeSeriesRepositoryImpl implements HvacTimeSeriesRepository {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate template;
    private final TdengineProperties properties;

    public HvacTimeSeriesRepositoryImpl(
            @Qualifier("taosJdbcTemplate") JdbcTemplate template,
            TdengineProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public void insertIndicator(String indicatorId, String indicatorCode, String buildingId,
                                String equipId, String systemGroupId, double value) {
        String stable = qualified(properties.getStIndicatorMinute());
        String child = qualified(properties.getStIndicatorMinute() + "_" + indicatorId);
        String sql = "INSERT INTO " + child + " USING " + stable
                + " (indicator_id,indicator_code,building_id,system_group_id,equip_id)"
                + " TAGS (" + quote(indicatorId) + "," + quote(indicatorCode) + ","
                + quote(buildingId) + ","
                + nullableQuote(systemGroupId) + "," + nullableQuote(equipId) + ") VALUES ("
                + quote(new Timestamp(System.currentTimeMillis()).toString()) + ","
                + String.format(Locale.ROOT, "%.12f", value) + ")";
        try {
            template.execute(sql);
        } catch (Exception exception) {
            log.error("指标写入失败: indicatorCode={}, value={}, error={}",
                    indicatorCode, value, exception.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> queryRawData(String pointId, long startMs, long endMs) {
        String sql = "SELECT ts, COALESCE(avg_val,val) AS val, data_quality, "
                + "min_val, max_val, sample_count, first_received_time, "
                + "last_received_time, finalized_at FROM "
                + qualified(properties.getStRawMinute() + "_" + pointId)
                + " WHERE ts >= " + quote(new Timestamp(startMs).toString())
                + " AND ts <= " + quote(new Timestamp(endMs).toString())
                + " ORDER BY ts";
        return query(sql, "分钟测点", pointId);
    }

    @Override
    public Map<String, Object> queryLatestRawData(String pointId) {
        String sql = "SELECT ts, COALESCE(avg_val,val) AS val, data_quality, "
                + "min_val, max_val, sample_count, first_received_time, "
                + "last_received_time, finalized_at FROM "
                + qualified(properties.getStRawMinute() + "_" + pointId)
                + " ORDER BY ts DESC LIMIT 1";
        List<Map<String, Object>> rows = query(sql, "最新分钟测点", pointId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public List<Map<String, Object>> queryIndicator(String indicatorId, long startMs, long endMs) {
        String sql = "SELECT ts, val FROM "
                + qualified(properties.getStIndicatorMinute() + "_" + indicatorId)
                + " WHERE ts >= " + quote(new Timestamp(startMs).toString())
                + " AND ts <= " + quote(new Timestamp(endMs).toString())
                + " ORDER BY ts";
        return query(sql, "指标", indicatorId);
    }

    @Override
    public Map<String, Object> queryLatestIndicator(String indicatorId) {
        String sql = "SELECT ts, val FROM "
                + qualified(properties.getStIndicatorMinute() + "_" + indicatorId)
                + " ORDER BY ts DESC LIMIT 1";
        List<Map<String, Object>> rows = query(sql, "最新指标", indicatorId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Map<String, Object>> query(String sql, String type, String code) {
        try {
            return template.queryForList(sql);
        } catch (Exception exception) {
            log.warn("{}查询异常: code={}, error={}", type, code, exception.getMessage());
            return Collections.emptyList();
        }
    }

    private String qualified(String table) {
        return safe(properties.getDatabase()) + "." + safe(table);
    }

    private String safe(String value) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("非法TDengine标识符: " + value);
        }
        return value;
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String nullableQuote(String value) {
        return value == null ? "NULL" : quote(value);
    }
}
