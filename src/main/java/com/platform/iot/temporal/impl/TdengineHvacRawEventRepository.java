package com.platform.iot.temporal.impl;

import com.platform.config.TdengineProperties;
import com.platform.iot.reliability.RawTelemetryLedger;
import com.platform.iot.reliability.RawTelemetryCodec;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.LateRawMinuteEvidence;
import com.platform.iot.temporal.model.LatestRawReading;
import com.platform.iot.temporal.model.PointMinuteKey;
import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.iot.temporal.model.RawTrendBucket;
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

/** 原始事实由 MySQL 唯一意图仲裁；所有入口只向 TDengine 回放首次冻结快照。 */
@Repository
public class TdengineHvacRawEventRepository implements HvacRawEventRepository {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate template;
    private final TdengineProperties properties;
    private final RawTelemetryLedger ledger;
    private final RawTelemetryCodec codec;
    /**
     * 只缓存本进程已经确认存在的子表，避免每条 MQTT 消息都执行一次 DDL。
     * CREATE 成功后才写入集合；如果 TDengine 暂时不可用，异常会交给上层重试，
     * 不会把一个实际未创建的子表错误地标记为“已存在”。
     */
    private final Set<String> ensuredChildren = ConcurrentHashMap.newKeySet();

    public TdengineHvacRawEventRepository(
            @Qualifier("taosJdbcTemplate") JdbcTemplate template,
            TdengineProperties properties, RawTelemetryLedger ledger, RawTelemetryCodec codec) {
        this.template = template;
        this.properties = properties;
        this.ledger = ledger;
        this.codec = codec;
    }

    @Override
    public RawEventWriteResult upsert(RawTelemetryEvent event) {
        return insertImmutable(event);
    }

    @Override
    public void retainConflict(RawTelemetryEvent event) {ledger.retainIfKnown(event);}

    @Override
    public RawEventWriteResult insertImmutable(RawTelemetryEvent event) {
        var reservation = ledger.reserve(event, () -> original(event));
        if (reservation.expired()) return RawEventWriteResult.EXPIRED;
        if (reservation.conflict()) return RawEventWriteResult.CONFLICT_REJECTED;
        return replayFrozen(reservation.original());
    }

    /** 恢复只读取持久化意图，不重新解析已经变化的设备或测点绑定。 */
    public RawEventWriteResult replayFrozen(RawTelemetryEvent event) { return replayFrozen(event,false); }

    public RawEventWriteResult replayRecovered(RawTelemetryEvent event) { return replayFrozen(event,true); }

    private RawEventWriteResult replayFrozen(RawTelemetryEvent event,boolean recovery) {
        return ledger.storeFrozen(event, () -> {
            RawTelemetryEvent existing = original(event);
            if (existing != null) {
                if (!codec.hash(existing).equals(codec.hash(event))
                        || existing.receivedTime() != event.receivedTime() || existing.late() != event.late()) {
                    throw new IllegalStateException("TDengine original differs from frozen intent");
                }
                if(recovery)ledger.queueRecovered(event);
                return RawEventWriteResult.DUPLICATE;
            }
            String table = qualifiedChild(event.pointId());
            ensureChildTable(event, table);
            template.execute("INSERT INTO " + table
                    + " (ts,received_time,val,data_quality,late_flag,source_system,source_point_code,source_device_id) VALUES ("
                    + event.eventTime() + "," + event.receivedTime() + "," + formatDouble(event.value()) + ","
                    + event.dataQuality() + "," + (event.late() ? 1 : 0) + ","
                    + quote(event.sourceSystem()) + "," + quote(event.sourcePointCode()) + ","
                    + quote(event.sourceDeviceId()) + ")");
            RawTelemetryEvent stored = original(event);
            if (stored == null || !codec.hash(stored).equals(codec.hash(event))) {
                throw new IllegalStateException("TDengine write verification failed");
            }
            if(recovery)ledger.queueRecovered(event);
            return RawEventWriteResult.INSERTED;
        });
    }

    private RawTelemetryEvent original(RawTelemetryEvent event) {
        // 超级表查询同时接管历史自定义子表，不能只检查约定名称而制造第二份原始事实。
        String stable = safeIdentifier(properties.getDatabase()) + "." + safeIdentifier(properties.getStRawEvent());
        List<Map<String, Object>> rows = template.queryForList("SELECT * FROM " + stable
                + " WHERE LOWER(point_id)=" + quote(codec.key(event.pointId())) + " AND ts=" + event.eventTime() + " LIMIT 2");
        if (rows.size() > 1) throw new IllegalStateException("Multiple historical raw identities");
        return rows.isEmpty() ? null : mapEvent(rows.getFirst());
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
        return ledger.effective(template.queryForList(sql).stream().map(this::mapEvent).toList());
    }

    @Override
    public List<RawTelemetryEvent> findPointHistory(String buildingId, String equipmentId, String pointId,
            long startInclusive, long endExclusive, Long afterExclusive, int limit) {
        return pointHistory(buildingId,equipmentId,pointId,startInclusive,endExclusive,afterExclusive,limit,true);
    }

    @Override
    public List<RawTelemetryEvent> findOriginalPointHistory(String buildingId,String equipmentId,String pointId,
            long startInclusive,long endExclusive,Long afterExclusive,int limit) {
        return pointHistory(buildingId,equipmentId,pointId,startInclusive,endExclusive,afterExclusive,limit,false);
    }

    private List<RawTelemetryEvent> pointHistory(String buildingId,String equipmentId,String pointId,
            long startInclusive,long endExclusive,Long afterExclusive,int limit,boolean effective) {
        if (startInclusive < 0 || startInclusive >= endExclusive || limit < 1 || limit > 1001) {
            throw new IllegalArgumentException("Invalid bounded history query");
        }
        String stable = safeIdentifier(properties.getDatabase()) + "." + safeIdentifier(properties.getStRawEvent());
        String sql = "SELECT * FROM " + stable + " WHERE building_id=" + quote(buildingId)
                + " AND equip_id=" + quote(equipmentId) + " AND point_id=" + quote(pointId)
                + " AND source_system='DAIKIN_V2' AND ts>=" + quote(new Timestamp(startInclusive).toString())
                + " AND ts<" + quote(new Timestamp(endExclusive).toString())
                + (afterExclusive == null ? "" : " AND ts>" + quote(new Timestamp(afterExclusive).toString()))
                + " ORDER BY ts ASC LIMIT " + limit;
        var events=template.queryForList(sql).stream().map(this::mapEvent).toList();
        return effective?ledger.effective(events):events;
    }

    @Override
    public List<RawTelemetryEvent> findMeterPointHistory(String buildingId, String equipmentId, String pointId,
            long startInclusive, long endExclusive, Long afterExclusive, int limit) {
        if (startInclusive < 0 || startInclusive >= endExclusive || limit < 1 || limit > 1001) {
            throw new IllegalArgumentException("Invalid bounded meter history query");
        }
        String stable = safeIdentifier(properties.getDatabase()) + "." + safeIdentifier(properties.getStRawEvent());
        String sql = "SELECT * FROM " + stable + " WHERE building_id=" + quote(buildingId)
                + " AND equip_id=" + quote(equipmentId) + " AND point_id=" + quote(pointId)
                + " AND ts>=" + quote(new Timestamp(startInclusive).toString())
                + " AND ts<" + quote(new Timestamp(endExclusive).toString())
                + (afterExclusive == null ? "" : " AND ts>" + quote(new Timestamp(afterExclusive).toString()))
                + " ORDER BY ts ASC LIMIT " + limit;
        return ledger.effective(template.queryForList(sql).stream().map(this::mapEvent).toList());
    }

    @Override
    public List<LatestRawReading> findLatestByEquipmentPoints(
            String buildingId, String equipmentId, Collection<String> pointIds) {
        if (pointIds.isEmpty()) {
            return List.of();
        }
        if (pointIds.size() > 500) {
            throw new IllegalArgumentException("单台设备查询的测点不能超过 500 个");
        }
        String stable = safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent());
        String ids = pointIds.stream().distinct().map(this::quote)
                .collect(java.util.stream.Collectors.joining(","));
        // building/equip/point 三重条件锁定 MySQL 已确认的当前设备测点。TDengine 3.2.3
        // 的普通 LIMIT 会限制整批结果，因此按完整身份分区后用 LAST_ROW 批量取每点最新行。
        String sql = "SELECT point_id, LAST_ROW(val) AS latest_value,"
                + " LAST_ROW(ts) AS event_time, LAST_ROW(received_time) AS latest_received_time,"
                + " LAST_ROW(data_quality) AS latest_data_quality FROM " + stable
                + " WHERE building_id=" + quote(buildingId)
                + " AND equip_id=" + quote(equipmentId)
                + " AND point_id IN (" + ids + ")"
                + " PARTITION BY point_id, point_code, building_id, system_group_id,"
                + " equip_id, equip_code, family_code, component_code, suffix_code, is_for_calc";
        var rows = template.queryForList(sql);
        if (rows.isEmpty()) return List.of();
        long from = rows.stream().mapToLong(row -> timestamp(row,"event_time").getTime()).min().orElseThrow();
        long to = rows.stream().mapToLong(row -> timestamp(row,"event_time").getTime()).max().orElseThrow()+1;
        var selected = ledger.selections(pointIds, from, to);
        return rows.stream()
                .map(row -> new LatestRawReading(
                        text(row, "point_id"),
                        selected.containsKey(RawTelemetryLedger.identity(text(row, "point_id"), timestamp(row, "event_time").getTime()))
                                ? selected.get(RawTelemetryLedger.identity(text(row, "point_id"), timestamp(row, "event_time").getTime())).selectedValue()
                                : number(row, "latest_value").doubleValue(),
                        timestamp(row, "event_time").getTime(),
                        timestamp(row, "latest_received_time").getTime(),
                        number(row, "latest_data_quality").intValue()))
                .toList();
    }

    @Override
    public List<RawTrendBucket> findEquipmentTrend(
            String buildingId,
            String equipmentId,
            Collection<String> pointIds,
            long startInclusive,
            long endExclusive,
            int intervalSeconds) {
        if (pointIds.isEmpty()) {
            return List.of();
        }
        if (pointIds.size() > 500 || startInclusive < 0 || startInclusive >= endExclusive
                || intervalSeconds < 1 || intervalSeconds > 86_400) {
            throw new IllegalArgumentException("Invalid bounded equipment trend query");
        }
        String stable = safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent());
        String ids = pointIds.stream().distinct().map(this::quote)
                .collect(java.util.stream.Collectors.joining(","));
        // 聚合和质量收口必须在 TDengine 完成，避免大窗口原始事件进入 JVM；
        // MAX(data_quality) 保留窗口内最差质量，防止平均值掩盖受限样本。
        String sql = """
                SELECT point_id,
                       _wstart AS bucket_time,
                       AVG(val) AS average_value,
                       COUNT(*) AS sample_count,
                       MAX(data_quality) AS data_quality
                FROM %s
                WHERE building_id=%s
                  AND equip_id=%s
                  AND point_id IN (%s)
                  AND ts >= %s
                  AND ts < %s
                PARTITION BY point_id
                INTERVAL(%ds)
                ORDER BY point_id,_wstart
                """.formatted(
                stable,
                quote(buildingId),
                quote(equipmentId),
                ids,
                quote(new Timestamp(startInclusive).toString()),
                quote(new Timestamp(endExclusive).toString()),
                intervalSeconds);
        var selected = ledger.selections(pointIds, startInclusive, endExclusive);
        return template.queryForList(sql).stream()
                .map(row -> new RawTrendBucket(
                        text(row, "point_id"),
                        timestamp(row, "bucket_time").getTime(),
                        effectiveAverage(row, selected.values(), intervalSeconds * 1000L),
                        number(row, "data_quality").intValue()))
                .toList();
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
        String cursor = null;
        while (true) {
            var page = findRetentionPoints(cursor, 100, "MQTT_STANDARD_V1");
            for (var point : page) deletePointBefore(point.tableName(), eventTimeExclusive);
            if (page.size() < 100) return;
            cursor = page.getLast().tableName();
        }
    }

    @Override
    public List<RetentionPoint> findRetentionPoints(String afterTableName, int limit, String protectedSource) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid retention point page");
        String stable = safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent());
        String cursor = afterTableName == null ? "" : " WHERE tbname>" + quote(afterTableName);
        List<Map<String, Object>> rows = template.queryForList(
                "SELECT DISTINCT tbname,point_id FROM " + stable + cursor + " ORDER BY tbname LIMIT " + limit);
        return rows.stream().map(row -> {
            String tableName = safeIdentifier(text(row, "tbname"));
            String pointId = text(row, "point_id");
            boolean present = !template.queryForList("SELECT source_system FROM "
                    + safeIdentifier(properties.getDatabase()) + "." + tableName
                    + " WHERE source_system=" + quote(protectedSource) + " LIMIT 1").isEmpty();
            return new RetentionPoint(tableName, pointId, present);
        }).toList();
    }

    @Override
    public void deletePointBefore(String tableName, long eventTimeExclusive) {
        String table = safeIdentifier(properties.getDatabase()) + "." + safeIdentifier(tableName);
        var tags = template.queryForList("SELECT point_id FROM " + table + " LIMIT 1");
        if (tags.isEmpty()) return;
        long safe = ledger.retireBefore(text(tags.getFirst(), "point_id"), eventTimeExclusive);
        template.execute("DELETE FROM " + table + " WHERE ts < " + safe);
    }

    @Override
    public SourceDeletionScan deleteSourceBeforeInBoundedWindow(String sourceSystem, long eventTimeExclusive,
                                                                 long maximumWindowMillis,
                                                                 String afterPointId) {
        if (eventTimeExclusive < 0 || maximumWindowMillis < 1) {
            throw new IllegalArgumentException("Invalid bounded retention window");
        }
        String stable = safeIdentifier(properties.getDatabase()) + "."
                + safeIdentifier(properties.getStRawEvent());
        // TDengine 3.2.3 的 MIN 不接受 TIMESTAMP；FIRST 按主时间轴选择最早的非空时间。
        List<Map<String, Object>> candidates = template.queryForList(
                "SELECT tbname,point_id,FIRST(ts) AS oldest_ts FROM " + stable
                        + " WHERE source_system=" + quote(sourceSystem)
                        + " AND ts<" + eventTimeExclusive
                        + (afterPointId == null ? "" : " AND tbname>" + quote(afterPointId))
                        + " GROUP BY tbname,point_id ORDER BY tbname LIMIT 100");
        String cursor = afterPointId;
        for (Map<String, Object> candidate : candidates) {
            String pointId = text(candidate, "point_id");
            String tableName = safeIdentifier(text(candidate, "tbname"));
            String child = safeIdentifier(properties.getDatabase()) + "." + tableName;
            long start = timestamp(candidate, "oldest_ts").getTime();
            long end = Math.min(eventTimeExclusive, Math.addExact(start, maximumWindowMillis));
            // 原始事件库使用毫秒精度；数值边界避免 JVM 与服务端时区不同造成窗口偏移。
            String range = " WHERE ts >= " + start + " AND ts < " + end;
            // TDengine DELETE 不能可靠按普通列过滤；混合来源时间窗宁可延后，也不能连带删除其他来源。
            if (!template.queryForList("SELECT source_system FROM " + child + range
                    + " AND (source_system IS NULL OR source_system<>" + quote(sourceSystem) + ") LIMIT 1").isEmpty()) {
                cursor = tableName;
                continue;
            }
            long safe = ledger.retireBefore(pointId, end);
            if (safe <= start) continue;
            template.execute("DELETE FROM " + child + " WHERE ts >= " + start + " AND ts < " + safe);
            return new SourceDeletionScan(true, false, cursor);
        }
        boolean endOfScan = candidates.size() < 100;
        return new SourceDeletionScan(!candidates.isEmpty(), endOfScan, endOfScan ? null : cursor);
    }

    /** 前后锚点限定累计量/积分受影响窗口；缺失的一侧保守包含该点已有周期。 */
    public long[] correctionInfluence(String pointId,long time) {
        String stable=safeIdentifier(properties.getDatabase())+"."+safeIdentifier(properties.getStRawEvent());
        String scope=" FROM "+stable+" WHERE point_id="+quote(pointId);
        var before=template.queryForList("SELECT ts"+scope+" AND ts<"+time+" ORDER BY ts DESC LIMIT 1");
        var after=template.queryForList("SELECT ts"+scope+" AND ts>"+time+" ORDER BY ts ASC LIMIT 1");
        return new long[]{before.isEmpty()?0:timestamp(before.getFirst(),"ts").getTime(),
                after.isEmpty()?253402214400000L:timestamp(after.getFirst(),"ts").getTime()};
    }

    private double effectiveAverage(Map<String, Object> row, Collection<RawTelemetryLedger.Selection> selected, long width) {
        long start = timestamp(row, "bucket_time").getTime();
        double delta = selected.stream().filter(s -> s.pointId().equals(text(row, "point_id"))
                && s.eventTime() >= start && s.eventTime() < start + width)
                .mapToDouble(s -> s.selectedValue() - s.originalValue()).sum();
        return number(row, "average_value").doubleValue() + delta / number(row, "sample_count").longValue();
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
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite raw value");
        return Double.toString(value);
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
