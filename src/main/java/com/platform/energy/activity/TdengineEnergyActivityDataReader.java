package com.platform.energy.activity;

import com.platform.config.TdengineProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Repository
/**
 * 从 TDengine 原始事件超级表实现多能源活动数据的有界读取。
 *
 * <p>查询同时约束建筑、测点、事件时间和 seek 游标，避免跨建筑泄漏及 offset 深分页。
 * 原始表以“测点 + 事件时间”保存当前行，没有重复次数和冲突修正历史；这些限制由上层
 * 响应元数据显式公开，适配器不会推断不存在的证据。</p>
 */
public class TdengineEnergyActivityDataReader implements EnergyActivityDataReader {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate template;
    private final TdengineProperties properties;

    public TdengineEnergyActivityDataReader(
            @Qualifier("taosJdbcTemplate") JdbcTemplate template,
            TdengineProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public RawEventPage readRawEvents(
            String buildingId,
            Set<String> pointIds,
            long fromInclusive,
            long toExclusive,
            Cursor after,
            int limit) {
        if (pointIds == null || pointIds.isEmpty()) {
            return new RawEventPage(List.of(), false, null);
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("活动数据读取 limit 必须在 1 到 500 之间");
        }
        String seek = after == null ? "" : " AND (ts > "
                + quote(new Timestamp(after.eventTime()).toString())
                + " OR (ts = " + quote(new Timestamp(after.eventTime()).toString())
                + " AND point_id > " + quote(after.pointId()) + "))";
        String sql = """
                SELECT ts,received_time,val,data_quality,late_flag,
                       source_system,source_point_code,source_device_id,
                       point_id,point_code,building_id
                FROM %s
                WHERE building_id=%s
                  AND point_id IN (%s)
                  AND ts >= %s
                  AND ts < %s%s
                ORDER BY ts,point_id
                LIMIT %d
                """.formatted(
                stable(),
                quote(buildingId),
                pointIds.stream().sorted().map(TdengineEnergyActivityDataReader::quote)
                        .collect(Collectors.joining(",")),
                quote(new Timestamp(fromInclusive).toString()),
                quote(new Timestamp(toExclusive).toString()),
                seek,
                limit + 1);
        List<RawEvent> loaded = template.queryForList(sql).stream()
                .map(this::map).toList();
        boolean truncated = loaded.size() > limit;
        List<RawEvent> page = truncated
                ? new ArrayList<>(loaded.subList(0, limit)) : loaded;
        Cursor next = truncated && !page.isEmpty()
                ? new Cursor(page.getLast().eventTime(), page.getLast().pointId()) : null;
        return new RawEventPage(page, truncated, next);
    }

    @Override
    public RawEvent readLatestAtOrBefore(String buildingId, String pointId, long atInclusive) {
        String sql = """
                SELECT ts,received_time,val,data_quality,late_flag,
                       source_system,source_point_code,source_device_id,
                       point_id,point_code,building_id
                FROM %s
                WHERE building_id=%s AND point_id=%s AND ts <= %s
                ORDER BY ts DESC
                LIMIT 1
                """.formatted(stable(), quote(buildingId), quote(pointId),
                quote(new Timestamp(atInclusive).toString()));
        List<RawEvent> rows = template.queryForList(sql).stream().map(this::map).toList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private RawEvent map(Map<String, Object> row) {
        return new RawEvent(
                text(row, "point_id"),
                text(row, "point_code"),
                text(row, "building_id"),
                text(row, "source_system"),
                text(row, "source_point_code"),
                text(row, "source_device_id"),
                number(row, "val").doubleValue(),
                timestamp(row, "ts").getTime(),
                timestamp(row, "received_time").getTime(),
                number(row, "data_quality").intValue(),
                number(row, "late_flag").intValue() == 1);
    }

    private String stable() {
        return safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent());
    }

    private static String safeIdentifier(String value) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalStateException("Invalid TDengine identifier");
        }
        return value;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static Object value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value : row.get(key.toUpperCase(Locale.ROOT));
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : value.toString();
    }

    private static Number number(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalStateException("TDengine numeric column missing: " + key);
    }

    private static Timestamp timestamp(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime());
        }
        throw new IllegalStateException("TDengine timestamp column missing: " + key);
    }
}
