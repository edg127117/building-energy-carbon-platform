package com.platform.iot.temporal.impl;

import com.platform.config.TdengineProperties;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.LateRawMinuteEvidence;
import com.platform.iot.temporal.model.PointMinuteKey;
import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 使用专用 TDengine {@link JdbcTemplate} 保存和读取 HVAC 逐条真实事件。
 *
 * <p>每个标准测点使用独立子表，设备采集时间 {@code ts} 是子表内唯一键；相同
 * 时间戳再次写入会更新旧行，因此仓储先比较值与来源身份，区分完全重复和“最后上报
 * 生效”的冲突更新。分钟冻结从超级表按半开区间批量取样，迟到恢复则在 TDengine
 * 侧按测点和分钟分组，避免把大窗口原始样本全部拉回 JVM。</p>
 */
@Repository
public class TdengineHvacRawEventRepository implements HvacRawEventRepository {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate template;
    private final TdengineProperties properties;
    /**
     * 只缓存本进程已经确认存在的子表，避免每条 MQTT 消息都执行一次 DDL。
     * CREATE 成功后才写入集合；如果 TDengine 暂时不可用，异常会交给上层重试，
     * 不会把一个实际未创建的子表错误地标记为“已存在”。
     */
    private final Set<String> ensuredChildren = ConcurrentHashMap.newKeySet();

    public TdengineHvacRawEventRepository(
            @Qualifier("taosJdbcTemplate") JdbcTemplate template,
            TdengineProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    /**
     * 先确保带完整标准身份标签的测点子表存在，再按事件时间执行幂等写入。
     * 首条事件不能先查询不存在的子表，否则会把正常首报误判为存储失败。
     */
    @Override
    public RawEventWriteResult upsert(RawTelemetryEvent event) {
        return write(event, true);
    }

    @Override
    public RawEventWriteResult insertImmutable(RawTelemetryEvent event) {
        return write(event, false);
    }

    private RawEventWriteResult write(RawTelemetryEvent event, boolean overwriteConflict) {
        String table = qualifiedChild(event.pointId());
        ensureChildTable(event, table);
        Timestamp eventTimestamp = new Timestamp(event.eventTime());
        List<Map<String, Object>> existing = template.queryForList(
                "SELECT val,source_system,source_point_code,source_device_id FROM "
                        + table + " WHERE ts=" + quote(eventTimestamp.toString()) + " LIMIT 1");
        if (!existing.isEmpty()) {
            Map<String, Object> currentRow = existing.getFirst();
            double current = number(currentRow, "val").doubleValue();
            if (Double.compare(current, event.value()) == 0
                    && event.sourceSystem().equals(text(currentRow, "source_system"))
                    && event.sourcePointCode().equals(text(currentRow, "source_point_code"))
                    && event.sourceDeviceId().equals(text(currentRow, "source_device_id"))) {
                return RawEventWriteResult.DUPLICATE;
            }
            if (!overwriteConflict) {
                return RawEventWriteResult.CONFLICT_UPDATED;
            }
        }

        // 子表已经由 ensureChildTable 建立，这里只写普通数据列。
        // 对相同事件时间的冲突值，TDengine 3 会覆盖旧行，从而实现“最后一次上报生效”。
        String sql = "INSERT INTO " + table
                + " (ts,received_time,val,data_quality,late_flag,"
                + "source_system,source_point_code,source_device_id) VALUES ("
                + quote(eventTimestamp.toString()) + ","
                + quote(new Timestamp(event.receivedTime()).toString()) + ","
                + formatDouble(event.value()) + ","
                + event.dataQuality() + ","
                + (event.late() ? 1 : 0) + ","
                + quote(event.sourceSystem()) + ","
                + quote(event.sourcePointCode()) + ","
                + quote(event.sourceDeviceId()) + ")";
        template.execute(sql);
        return existing.isEmpty()
                ? RawEventWriteResult.INSERTED : RawEventWriteResult.CONFLICT_UPDATED;
    }

    /**
     * 为首次出现的标准测点创建子表，并只在 DDL 成功后缓存“已确认存在”。
     * TDengine 暂时不可用时异常交给接入层重试，不能污染本进程缓存。
     */
    private void ensureChildTable(RawTelemetryEvent event, String table) {
        if (ensuredChildren.contains(table)) {
            return;
        }

        String stable = safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent());
        String createSql = "CREATE TABLE IF NOT EXISTS " + table + " USING " + stable
                + " (point_id,point_code,building_id,system_group_id,equip_id,equip_code,"
                + "family_code,component_code,suffix_code,is_for_calc) TAGS ("
                + quote(event.pointId()) + ","
                + quote(event.pointCode()) + ","
                + quote(event.buildingId()) + ","
                + nullableQuote(event.systemGroupId()) + ","
                + nullableQuote(event.equipId()) + ","
                + nullableQuote(event.equipCode()) + ","
                + quote(event.familyCode()) + ","
                + nullableQuote(event.componentCode()) + ","
                + quote(event.suffixCode()) + ","
                + event.isForCalc() + ")";
        template.execute(createSql);
        ensuredChildren.add(table);
    }

    @Override
    public List<RawTelemetryEvent> findWindow(
            long startInclusive, long endExclusive, boolean includeLate) {
        String stable = safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent());
        // 直接查询超级表会一次返回这个分钟内所有子表的数据及其标签，
        // 避免“19个测点 × 多个分钟”逐一访问TDengine。
        String sql = "SELECT ts, received_time, val, data_quality, late_flag, "
                + "source_system, source_point_code, source_device_id, "
                + "point_id, point_code, building_id, system_group_id, equip_id, equip_code, "
                + "family_code, component_code, suffix_code, is_for_calc FROM "
                + stable
                + " WHERE ts >= " + quote(new Timestamp(startInclusive).toString())
                + " AND ts < " + quote(new Timestamp(endExclusive).toString())
                + (includeLate ? "" : " AND late_flag=0");
        return template.queryForList(sql).stream().map(this::mapEvent).toList();
    }

    @Override
    public List<LateRawMinuteEvidence> findLateMinuteEvidence(
            long startInclusive,
            long endExclusive,
            Long afterMinuteStart,
            String afterPointId,
            int limit) {
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        String stable = safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent());
        // late_flag、分钟分组和 LIMIT 都在 TDengine 执行，避免把 24 小时原始样本
        // 全量拉回 JVM 后再过滤。恢复使用本轮 now 作为接收水位，不对 TIMESTAMP
        // 执行 MAX，从而兼容项目使用的 TDengine 3.2.3。
        if ((afterMinuteStart == null) != (afterPointId == null)) {
            throw new IllegalArgumentException(
                    "迟到证据游标的分钟和测点必须同时提供");
        }
        if (afterPointId != null && afterPointId.isBlank()) {
            throw new IllegalArgumentException("afterPointId 不能为空白");
        }
        String seek = afterMinuteStart == null ? "" : """
                  WHERE minute_start > %s
                     OR (minute_start = %s AND point_id > %s)
                """.formatted(
                quote(new Timestamp(afterMinuteStart).toString()),
                quote(new Timestamp(afterMinuteStart).toString()),
                quote(afterPointId));
        // 外层 seek 游标让每轮 LIMIT 100 可以持续前进；扫描到末尾后服务会安全回绕。
        String sql = """
                SELECT point_id, building_id, minute_start
                FROM (
                    SELECT point_id, FIRST(building_id) AS building_id,
                           _wstart AS minute_start
                    FROM %s
                    WHERE ts >= %s
                      AND ts < %s
                      AND late_flag = 1
                    PARTITION BY point_id
                    INTERVAL(1m)
                ) grouped_late
                %s
                ORDER BY minute_start, point_id
                LIMIT %d
                """.formatted(
                stable,
                quote(new Timestamp(startInclusive).toString()),
                quote(new Timestamp(endExclusive).toString()),
                seek,
                limit);
        return template.queryForList(sql).stream()
                .map(row -> new LateRawMinuteEvidence(
                        text(row, "point_id"),
                        text(row, "building_id"),
                        timestamp(row, "minute_start").getTime()))
                .toList();
    }

    @Override
    public Set<PointMinuteKey> findLateEvidenceKeys(
            Collection<PointMinuteKey> candidates) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        if (candidates.size() > 100) {
            throw new IllegalArgumentException("迟到证据核验每批不能超过 100 个分钟");
        }
        String stable = safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent());
        String predicates = candidates.stream()
                .distinct()
                .map(key -> "(point_id=" + quote(key.pointId())
                        + " AND ts >= "
                        + quote(new Timestamp(key.minuteStart()).toString())
                        + " AND ts < "
                        + quote(new Timestamp(
                        key.minuteStart() + 60_000L).toString()) + ")")
                .collect(java.util.stream.Collectors.joining(" OR "));
        // TDengine 的 INTERVAL 窗口查询必须至少包含一个聚合函数；COUNT 只用于让
        // “测点 + 分钟”证据去重查询合法化，调用方仍只读取 point_id 和 minute_start。
        String sql = """
                SELECT point_id, _wstart AS minute_start,
                       COUNT(*) AS evidence_count
                FROM %s
                WHERE late_flag = 1
                  AND (%s)
                PARTITION BY point_id
                INTERVAL(1m)
                ORDER BY minute_start, point_id
                """.formatted(stable, predicates);
        Set<PointMinuteKey> keys = new LinkedHashSet<>();
        template.queryForList(sql).forEach(row -> keys.add(
                new PointMinuteKey(
                        text(row, "point_id"),
                        timestamp(row, "minute_start").getTime())));
        return Set.copyOf(keys);
    }

    @Override
    public void deleteBefore(long eventTimeExclusive) {
        String stable = safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent());
        template.execute("DELETE FROM " + stable + " WHERE ts < "
                + quote(new Timestamp(eventTimeExclusive).toString()));
    }

    private RawTelemetryEvent mapEvent(Map<String, Object> row) {
        return new RawTelemetryEvent(
                text(row, "point_id"),
                text(row, "point_code"),
                text(row, "source_system"),
                text(row, "source_point_code"),
                text(row, "source_device_id"),
                text(row, "building_id"),
                nullableText(row, "system_group_id"),
                nullableText(row, "equip_id"),
                nullableText(row, "equip_code"),
                text(row, "family_code"),
                nullableText(row, "component_code"),
                text(row, "suffix_code"),
                number(row, "val").doubleValue(),
                timestamp(row, "ts").getTime(),
                timestamp(row, "received_time").getTime(),
                number(row, "data_quality").intValue(),
                number(row, "is_for_calc").intValue(),
                number(row, "late_flag").intValue() == 1
        );
    }

    private String qualifiedChild(String pointId) {
        return safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent() + "_" + pointId);
    }

    private String safeIdentifier(String value) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("非法TDengine标识符: " + value);
        }
        return value;
    }

    private String quote(String value) {
        if (value == null) throw new IllegalArgumentException("TDengine字符串值不能为空");
        return "'" + value.replace("'", "''") + "'";
    }

    private String nullableQuote(String value) {
        return value == null ? "NULL" : quote(value);
    }

    private String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.12f", value);
    }

    private Object value(Map<String, Object> row, String key) {
        Object result = row.get(key);
        if (result == null) result = row.get(key.toUpperCase(Locale.ROOT));
        return result;
    }

    private Number number(Map<String, Object> row, String key) {
        return (Number) value(row, key);
    }

    private Timestamp timestamp(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value instanceof Timestamp timestamp) return timestamp;
        if (value instanceof java.util.Date date) return new Timestamp(date.getTime());
        return Timestamp.valueOf(value.toString());
    }

    private String text(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : value.toString();
    }

    private String nullableText(Map<String, Object> row, String key) {
        return text(row, key);
    }
}
