package com.platform.iot.daikin.monitoring.state;

import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.model.DaikinFieldValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 大金类型化状态的内部持久化边界。轮次控制、字段新鲜度、变化事件和异常实例在同一MySQL事务内提交；
 * 本服务不发起厂家请求，也不承担公网鉴权，调用者必须先解析可信的正式设备身份和归属。
 */
@Service
public class DaikinMonitoringStateService {
    private static final long STALE_AFTER_MS = 5 * 60 * 1000L;
    private static final ZoneId STATISTICS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> TEMPERATURE_FIELDS = Set.of("roomTemp", "temperature");
    private static final Set<String> RUNTIME_FIELDS = Set.of(
            "onOff", "mode", "unitStatus", "fanSpeed", "airflowDirection",
            "roomTemp", "temperature", "errorType", "errorCode",
            "inCommunicationError", "inEquipmentError", "inMantenanceMode", "isFilterDirty",
            "controller.isConnectionUp", "controller.inForcedStop", "compressorOnOff");
    private static final Set<String> EVENT_FIELDS = Set.of(
            "onOff", "mode", "unitStatus", "fanSpeed", "airflowDirection", "errorType", "errorCode",
            "inCommunicationError", "inEquipmentError", "inMantenanceMode", "isFilterDirty",
            "controller.isConnectionUp", "controller.inForcedStop", "compressorOnOff");
    private static final Map<String, BooleanException> BOOLEAN_EXCEPTIONS = Map.of(
            "inCommunicationError", new BooleanException("VENDOR_COMMUNICATION", true),
            "inEquipmentError", new BooleanException("VENDOR_EQUIPMENT", true),
            "inMantenanceMode", new BooleanException("VENDOR_MAINTENANCE", true),
            "isFilterDirty", new BooleanException("FILTER_MAINTENANCE", true),
            "controller.isConnectionUp", new BooleanException("CONTROLLER_COMMUNICATION", false));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public DaikinMonitoringStateService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                        TransactionTemplate transaction) {
        this(jdbc, transaction, Clock.systemUTC());
    }

    DaikinMonitoringStateService(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 注册本轮计划目标；目标从停用恢复时重设过期计时起点，但保留历史有效观测和异常实例。 */
    public void registerTarget(Target target, long plannedAt) {
        requireTarget(target);
        requireTime(plannedAt);
        transaction.executeWithoutResult(status -> registerLocked(target, plannedAt));
    }

    /** 停用只排除后续过期扫描；既有开放异常需要真实有效观测才能恢复。 */
    public void deactivateTarget(String identityId) {
        requireId(identityId, "identityId");
        transaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE biz_daikin_monitoring_target SET active=0 WHERE identity_id=?", identityId));
    }

    /** 与设备级新鲜度共用白名单，供调度器判断本轮是否包含有效运行观测。 */
    public static boolean hasValidRuntimeFields(DaikinDeviceObservation observation) {
        Objects.requireNonNull(observation, "observation");
        return observation.fields().entrySet().stream().anyMatch(entry ->
                RUNTIME_FIELDS.contains(entry.getKey())
                        && entry.getValue().status() == DaikinFieldValue.Status.PRESENT);
    }

    public void observe(Target target, long roundId, DaikinDeviceObservation observation) {
        requireTarget(target);
        requireRound(roundId);
        Objects.requireNonNull(observation, "observation");
        if (!target.sourceId().equals(observation.key().sourceId())) {
            throw new IllegalArgumentException("观测来源与可信目标不一致");
        }
        long observedAt = observation.observedAt().toEpochMilli();
        requireTime(observedAt);
        transaction.executeWithoutResult(status -> observeLocked(target, roundId, observation, observedAt));
    }

    public void recordFailure(String sourceId, long roundId) {
        recordSourceResult(sourceId, roundId, false);
    }

    public void recordSuccess(String sourceId, long roundId) {
        recordSourceResult(sourceId, roundId, true);
    }

    /** 返回本批实际扫描并确认过期的目标数，limit仅限制单事务处理规模。 */
    public int scanStale(long now, int limit) {
        return scanStale(now, limit, target -> true);
    }

    /** 每个过期候选在写异常前复核实时启用状态，避免分页清理停用目标时先给未扫描目标生成假异常。 */
    public int scanStale(long now, int limit, java.util.function.Predicate<Target> eligible) {
        requireTime(now);
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit超出范围");
        Integer result = transaction.execute(status -> scanStaleLocked(now, limit, eligible));
        return result == null ? 0 : result;
    }

    private void observeLocked(Target target, long roundId, DaikinDeviceObservation observation,
                               long observedAt) {
        TargetRow registered = targetForUpdate(target.identityId());
        if (registered == null || !registered.active()) {
            throw new IllegalStateException("大金监测目标未注册或已停用");
        }
        if (!registered.sourceId().equals(target.sourceId())) {
            throw new IllegalArgumentException("目标来源不可变");
        }
        if (registered.lastRoundId() != null && roundId < registered.lastRoundId()) return;

        updateTargetSnapshot(target);
        boolean hasFreshRuntimeObservation = false;
        List<String> priorFieldNames = currentFieldNamesForUpdate(target.identityId());
        List<Map.Entry<String, DaikinFieldValue>> fields = observation.fields().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey)).toList();
        for (Map.Entry<String, DaikinFieldValue> entry : fields) {
            String fieldName = requireField(entry.getKey());
            DaikinFieldValue incoming = Objects.requireNonNull(entry.getValue(), "fieldValue");
            CurrentField current = currentFieldForUpdate(target.identityId(), fieldName);
            if (current != null && roundId <= current.roundId()) continue;
            if (incoming.status() == DaikinFieldValue.Status.PRESENT) {
                // 轮次更新不代表页解析时间更新；迟到的成功值不能回退正式值、新鲜度或恢复异常。
                if (current != null && current.lastValidAt() != null
                        && observedAt < current.lastValidAt()) continue;
                if ("onOff".equals(fieldName)) recordObservedRuntime(target, current, incoming,
                        observedAt, registered.firstPlannedAt());
                if (current != null && current.lastValidAt() != null
                        && Objects.equals(current.buildingId(), target.buildingId())
                        && EVENT_FIELDS.contains(fieldName)
                        && !Objects.equals(current.normalizedValue(), incoming.normalizedValue())) {
                    insertEvent(target, fieldName, roundId, current, incoming, observedAt);
                }
                upsertPresent(target, fieldName, roundId, incoming, observedAt, current == null);
                hasFreshRuntimeObservation |= RUNTIME_FIELDS.contains(fieldName);
                applyBooleanException(target, fieldName, roundId, incoming.normalizedValue(), observedAt);
            } else {
                upsertUnavailable(target, fieldName, roundId, incoming.status(), incoming.rawJson(),
                        observedAt, current == null);
            }
        }
        for (String fieldName : priorFieldNames) {
            if (observation.fields().containsKey(fieldName)) continue;
            CurrentField current = currentFieldForUpdate(target.identityId(), fieldName);
            if (current != null && roundId > current.roundId()) {
                upsertUnavailable(target, fieldName, roundId, DaikinFieldValue.Status.MISSING,
                        null, observedAt, false);
            }
        }

        Long currentValidAt = registered.lastValidAt();
        Long nextValidAt = currentValidAt;
        if (hasFreshRuntimeObservation && (nextValidAt == null || observedAt > nextValidAt)) {
            nextValidAt = observedAt;
        }
        jdbc.update("UPDATE biz_daikin_monitoring_target SET last_round_id=?,last_valid_at_ms=? WHERE identity_id=?",
                roundId, nextValidAt, target.identityId());
        // 延迟持久化可补历史，但已过期的原观测不能把当前过期实例短暂恢复成正常。
        if (hasFreshRuntimeObservation && nextValidAt != null && nextValidAt >= clock.millis() - STALE_AFTER_MS) {
            recover(activeKey("DEVICE_STALE", target), observedAt, roundId);
        }
    }

    private void insertEvent(Target target, String fieldName, long roundId, CurrentField current,
                             DaikinFieldValue incoming, long observedAt) {
        boolean afterGap = observedAt - current.lastValidAt() > STALE_AFTER_MS;
        jdbc.update("""
                INSERT INTO biz_daikin_state_event
                  (identity_id,field_name,round_id,source_id,pending_id,equipment_id,building_id,space_id,
                   system_group_id,mapping_version,before_raw_json,before_normalized_value,after_raw_json,
                   after_normalized_value,previous_observed_at_ms,observed_at_ms,after_gap)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, target.identityId(), fieldName, roundId, target.sourceId(), target.pendingId(),
                target.equipmentId(), target.buildingId(), target.spaceId(), target.systemGroupId(),
                target.mappingVersion(), current.rawJson(), current.normalizedValue(), incoming.rawJson(),
                incoming.normalizedValue(), current.lastValidAt(), observedAt, afterGap ? 1 : 0);
    }

    private void recordObservedRuntime(Target target, CurrentField previous,
                                       DaikinFieldValue incoming, long observedAt, long activationAt) {
        if (!Set.of("on", "off").contains(incoming.normalizedValue()) || previous == null
                || previous.lastValidAt() == null || !"PRESENT".equals(previous.fieldStatus())
                || !Set.of("on", "off").contains(previous.normalizedValue())
                || !target.buildingId().equals(previous.buildingId())
                || target.mappingVersion() != previous.mappingVersion()) return;
        long from = previous.lastValidAt();
        long elapsed = observedAt - from;
        // 只把相邻有效观测之间的短间隔归于前一次状态；停用、搬迁和长间断都不推算开机时间。
        if (from < activationAt || elapsed <= 0 || elapsed > STALE_AFTER_MS) return;
        boolean wasOn = "on".equals(previous.normalizedValue());
        while (from < observedAt) {
            long dayStart = Instant.ofEpochMilli(from).atZone(STATISTICS_ZONE)
                    .toLocalDate().atStartOfDay(STATISTICS_ZONE).toInstant().toEpochMilli();
            long nextDay = Instant.ofEpochMilli(from).atZone(STATISTICS_ZONE)
                    .toLocalDate().plusDays(1).atStartOfDay(STATISTICS_ZONE).toInstant().toEpochMilli();
            long until = Math.min(observedAt, nextDay);
            long covered = until - from;
            long on = wasOn ? covered : 0;
            int updated = jdbc.update("""
                    UPDATE biz_daikin_observed_runtime_day SET on_ms=on_ms+?,covered_ms=covered_ms+?
                    WHERE identity_id=? AND building_id=? AND mapping_version=? AND day_start_ms=?
                    """, on, covered, target.identityId(), target.buildingId(), target.mappingVersion(), dayStart);
            if (updated == 0) jdbc.update("""
                    INSERT INTO biz_daikin_observed_runtime_day
                      (identity_id,building_id,mapping_version,day_start_ms,on_ms,covered_ms)
                    VALUES (?,?,?,?,?,?)
                    """, target.identityId(), target.buildingId(), target.mappingVersion(), dayStart, on, covered);
            from = until;
        }
    }

    private void upsertPresent(Target target, String fieldName, long roundId, DaikinFieldValue incoming,
                               long observedAt, boolean insert) {
        if (insert) {
            insertCurrent(target, fieldName, roundId, incoming.rawJson(), incoming.normalizedValue(),
                    incoming.status(), observedAt, incoming.rawJson(), observedAt);
            return;
        }
        jdbc.update("""
                UPDATE biz_daikin_current_state SET source_id=?,pending_id=?,equipment_id=?,building_id=?,
                  space_id=?,system_group_id=?,mapping_version=?,raw_json=?,normalized_value=?,field_status=?,
                  last_valid_at_ms=?,last_attempt_raw_json=?,last_attempt_at_ms=?,last_attempt_building_id=?,
                  last_attempt_space_id=?,last_attempt_system_group_id=?,last_attempt_mapping_version=?,
                  last_round_id=? WHERE identity_id=? AND field_name=?
                """, target.sourceId(), target.pendingId(), target.equipmentId(), target.buildingId(),
                target.spaceId(), target.systemGroupId(), target.mappingVersion(), incoming.rawJson(),
                incoming.normalizedValue(), incoming.status().name(), observedAt, incoming.rawJson(), observedAt,
                target.buildingId(), target.spaceId(), target.systemGroupId(), target.mappingVersion(), roundId,
                target.identityId(), fieldName);
    }

    private void upsertUnavailable(Target target, String fieldName, long roundId,
                                   DaikinFieldValue.Status status, String attemptRawJson,
                                   long observedAt, boolean insert) {
        if (insert) {
            insertCurrent(target, fieldName, roundId, null, null, status, null,
                    attemptRawJson, observedAt);
            return;
        }
        // 正式值及其归属保持成对不变；本轮候选、状态和归属单独记录，避免搬迁后泄露旧值。
        jdbc.update("""
                UPDATE biz_daikin_current_state SET field_status=?,last_attempt_raw_json=?,last_attempt_at_ms=?,
                  last_attempt_building_id=?,last_attempt_space_id=?,last_attempt_system_group_id=?,
                  last_attempt_mapping_version=?,last_round_id=?
                WHERE identity_id=? AND field_name=?
                """, status.name(), attemptRawJson, observedAt, target.buildingId(), target.spaceId(),
                target.systemGroupId(), target.mappingVersion(), roundId, target.identityId(), fieldName);
    }

    private void insertCurrent(Target target, String fieldName, long roundId, String rawJson,
                               String normalizedValue, DaikinFieldValue.Status status,
                               Long lastValidAt, String attemptRawJson, long lastAttemptAt) {
        jdbc.update("""
                INSERT INTO biz_daikin_current_state
                  (identity_id,field_name,source_id,pending_id,equipment_id,building_id,space_id,system_group_id,
                   mapping_version,raw_json,normalized_value,field_status,last_valid_at_ms,last_attempt_raw_json,
                   last_attempt_at_ms,last_attempt_building_id,last_attempt_space_id,last_attempt_system_group_id,
                   last_attempt_mapping_version,last_round_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, target.identityId(), fieldName, target.sourceId(), target.pendingId(), target.equipmentId(),
                target.buildingId(), target.spaceId(), target.systemGroupId(), target.mappingVersion(), rawJson,
                normalizedValue, status.name(), lastValidAt, attemptRawJson, lastAttemptAt, target.buildingId(),
                target.spaceId(), target.systemGroupId(), target.mappingVersion(), roundId);
    }

    private void applyBooleanException(Target target, String fieldName, long roundId,
                                       String normalizedValue, long observedAt) {
        BooleanException definition = BOOLEAN_EXCEPTIONS.get(fieldName);
        if (definition == null) return;
        boolean asserted = Boolean.toString(definition.opensWhenTrue()).equalsIgnoreCase(normalizedValue);
        String key = activeKey(definition.type(), target);
        if (asserted) openOrTouch(key, definition.type(), "DEVICE", target, fieldName, observedAt, roundId);
        else recover(key, observedAt, roundId);
    }

    private void recordSourceResult(String sourceId, long roundId, boolean success) {
        requireId(sourceId, "sourceId");
        requireRound(roundId);
        transaction.executeWithoutResult(status -> {
            SourceResult row = sourceResultForUpdate(sourceId);
            if (row == null) {
                jdbc.update("INSERT INTO biz_daikin_source_result(source_id,last_result_round_id,consecutive_failure_rounds) VALUES (?,?,?)",
                        sourceId, roundId, success ? 0 : 1);
                row = new SourceResult(roundId, success ? 0 : 1);
            } else if (row.roundId() != null && roundId <= row.roundId()) {
                return;
            } else {
                int failures = success ? 0 : row.failures() + 1;
                jdbc.update("UPDATE biz_daikin_source_result SET last_result_round_id=?,consecutive_failure_rounds=? WHERE source_id=?",
                        roundId, failures, sourceId);
                row = new SourceResult(roundId, failures);
            }
            String key = sourceActiveKey(sourceId);
            long now = clock.millis();
            if (success) recover(key, now, roundId);
            else if (row.failures() >= 3) openOrTouch(key, "SOURCE_FETCH", "SOURCE", null,
                    null, now, roundId, sourceId);
        });
    }

    private int scanStaleLocked(long now, int limit, java.util.function.Predicate<Target> eligible) {
        long cutoff = now - STALE_AFTER_MS;
        List<Target> targets = jdbc.query("""
                SELECT source_id,pending_id,identity_id,equipment_id,building_id,space_id,system_group_id,mapping_version
                FROM biz_daikin_monitoring_target t
                WHERE t.active=1 AND CASE
                  WHEN t.last_valid_at_ms IS NULL OR t.first_planned_at_ms>t.last_valid_at_ms THEN t.first_planned_at_ms
                  ELSE t.last_valid_at_ms END < ?
                  AND NOT EXISTS (
                    SELECT 1 FROM biz_daikin_exception_instance x
                    WHERE x.exception_type='DEVICE_STALE' AND x.identity_id=t.identity_id
                      AND x.active_key IS NOT NULL)
                ORDER BY t.identity_id LIMIT ?
                """, (rs, row) -> new Target(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8)), cutoff, limit);
        int opened = 0;
        for (Target target : targets) {
            // 候选查询与采集提交可能交错；锁定目标后重查最新时间，不能依据旧候选生成过期异常。
            TargetRow current = targetForUpdate(target.identityId());
            if (current == null || !current.active()
                    || !Objects.equals(current.buildingId(), target.buildingId())
                    || current.mappingVersion() != target.mappingVersion()
                    || Math.max(current.firstPlannedAt(), current.lastValidAt() == null ? 0 : current.lastValidAt()) >= cutoff) continue;
            if (!eligible.test(target)) {
                deactivateTarget(target.identityId());
                continue;
            }
            openOrTouch(activeKey("DEVICE_STALE", target), "DEVICE_STALE", "DEVICE", target,
                    null, now, null);
            opened++;
        }
        return opened;
    }

    private void openOrTouch(String key, String type, String scope, Target target, String fieldName,
                             long detectedAt, Long roundId) {
        openOrTouch(key, type, scope, target, fieldName, detectedAt, roundId,
                target == null ? null : target.sourceId());
    }

    private void openOrTouch(String key, String type, String scope, Target target, String fieldName,
                             long detectedAt, Long roundId, String sourceId) {
        var existing = jdbc.queryForList(
                "SELECT exception_id FROM biz_daikin_exception_instance WHERE active_key=? FOR UPDATE",
                Long.class, key);
        if (!existing.isEmpty()) {
            jdbc.update("UPDATE biz_daikin_exception_instance SET last_detected_at_ms=?,last_round_id=? WHERE active_key=?",
                    detectedAt, roundId, key);
            return;
        }
        jdbc.update("""
                INSERT INTO biz_daikin_exception_instance
                  (active_key,exception_type,scope_type,source_id,identity_id,field_name,pending_id,equipment_id,
                   building_id,space_id,system_group_id,mapping_version,first_detected_at_ms,last_detected_at_ms,
                   recovered_at_ms,last_round_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?)
                """, key, type, scope, sourceId, target == null ? null : target.identityId(), fieldName,
                target == null ? null : target.pendingId(), target == null ? null : target.equipmentId(),
                target == null ? null : target.buildingId(), target == null ? null : target.spaceId(),
                target == null ? null : target.systemGroupId(), target == null ? null : target.mappingVersion(),
                detectedAt, detectedAt, roundId);
    }

    private void recover(String key, long recoveredAt, Long roundId) {
        jdbc.update("""
                UPDATE biz_daikin_exception_instance SET active_key=NULL,recovered_at_ms=?,last_round_id=?
                WHERE active_key=?
                """, recoveredAt, roundId, key);
    }

    private void registerLocked(Target target, long plannedAt) {
        TargetRow existing = targetForUpdate(target.identityId());
        if (existing == null) {
            jdbc.update("""
                    INSERT INTO biz_daikin_monitoring_target
                      (identity_id,source_id,pending_id,equipment_id,building_id,space_id,system_group_id,
                       mapping_version,active,first_planned_at_ms,last_valid_at_ms,last_round_id)
                    VALUES (?,?,?,?,?,?,?,?,1,?,NULL,NULL)
                    """, target.identityId(), target.sourceId(), target.pendingId(), target.equipmentId(),
                    target.buildingId(), target.spaceId(), target.systemGroupId(), target.mappingVersion(), plannedAt);
            return;
        }
        if (!existing.sourceId().equals(target.sourceId())) throw new IllegalArgumentException("目标来源不可变");
        boolean ownershipChanged = !Objects.equals(existing.buildingId(), target.buildingId())
                || !Objects.equals(existing.spaceId(), target.spaceId())
                || !Objects.equals(existing.systemGroupId(), target.systemGroupId())
                || existing.mappingVersion() != target.mappingVersion();
        long nextPlannedAt = existing.active() && !ownershipChanged
                ? existing.firstPlannedAt() : plannedAt;
        jdbc.update("""
                UPDATE biz_daikin_monitoring_target SET pending_id=?,equipment_id=?,building_id=?,space_id=?,
                  system_group_id=?,mapping_version=?,active=1,first_planned_at_ms=? WHERE identity_id=?
                """, target.pendingId(), target.equipmentId(), target.buildingId(), target.spaceId(),
                target.systemGroupId(), target.mappingVersion(), nextPlannedAt, target.identityId());
    }

    private void updateTargetSnapshot(Target target) {
        jdbc.update("""
                UPDATE biz_daikin_monitoring_target SET pending_id=?,equipment_id=?,building_id=?,space_id=?,
                  system_group_id=?,mapping_version=? WHERE identity_id=?
                """, target.pendingId(), target.equipmentId(), target.buildingId(), target.spaceId(),
                target.systemGroupId(), target.mappingVersion(), target.identityId());
    }

    private TargetRow targetForUpdate(String identityId) {
        List<TargetRow> rows = jdbc.query("""
                SELECT source_id,active,first_planned_at_ms,last_valid_at_ms,last_round_id,
                       building_id,space_id,system_group_id,mapping_version
                FROM biz_daikin_monitoring_target WHERE identity_id=? FOR UPDATE
                """, (rs, row) -> new TargetRow(rs.getString(1), rs.getInt(2) == 1, rs.getLong(3),
                nullableLong(rs, 4), nullableLong(rs, 5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getInt(9)), identityId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<String> currentFieldNamesForUpdate(String identityId) {
        return jdbc.queryForList("""
                SELECT field_name FROM biz_daikin_current_state WHERE identity_id=? FOR UPDATE
                """, String.class, identityId);
    }

    private CurrentField currentFieldForUpdate(String identityId, String fieldName) {
        List<CurrentField> rows = jdbc.query("""
                SELECT raw_json,normalized_value,last_valid_at_ms,last_round_id,building_id,
                       mapping_version,field_status
                FROM biz_daikin_current_state WHERE identity_id=? AND field_name=? FOR UPDATE
                """, (rs, row) -> new CurrentField(rs.getString(1), rs.getString(2),
                nullableLong(rs, 3), rs.getLong(4), rs.getString(5), rs.getInt(6), rs.getString(7)),
                identityId, fieldName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SourceResult sourceResultForUpdate(String sourceId) {
        List<SourceResult> rows = jdbc.query("""
                SELECT last_result_round_id,consecutive_failure_rounds FROM biz_daikin_source_result
                WHERE source_id=? FOR UPDATE
                """, (rs, row) -> new SourceResult(nullableLong(rs, 1), rs.getInt(2)), sourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Long nullableLong(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }

    private static String activeKey(String type, Target target) {
        return "DAIKIN|" + type + "|" + target.sourceId() + "|" + target.identityId();
    }

    private static String sourceActiveKey(String sourceId) {
        return "DAIKIN|SOURCE_FETCH|" + sourceId;
    }

    private static void requireTarget(Target target) {
        Objects.requireNonNull(target, "target");
        requireId(target.sourceId(), "sourceId");
        requireId(target.identityId(), "identityId");
        requireId(target.equipmentId(), "equipmentId");
        requireId(target.buildingId(), "buildingId");
        if (target.mappingVersion() < 1) throw new IllegalArgumentException("mappingVersion无效");
    }

    private static String requireField(String fieldName) {
        requireId(fieldName, "fieldName");
        if (fieldName.length() > 100) throw new IllegalArgumentException("fieldName超长");
        return fieldName;
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "无效");
    }

    private static void requireRound(long roundId) {
        if (roundId < 0) throw new IllegalArgumentException("roundId无效");
    }

    private static void requireTime(long time) {
        if (time < 0) throw new IllegalArgumentException("时间无效");
    }

    public record Target(String sourceId, String pendingId, String identityId, String equipmentId,
                         String buildingId, String spaceId, String systemGroupId, int mappingVersion) { }

    private record TargetRow(String sourceId, boolean active, long firstPlannedAt,
                             Long lastValidAt, Long lastRoundId, String buildingId, String spaceId,
                             String systemGroupId, int mappingVersion) { }
    private record CurrentField(String rawJson, String normalizedValue, Long lastValidAt, long roundId,
                                String buildingId, int mappingVersion, String fieldStatus) { }
    private record SourceResult(Long roundId, int failures) { }
    private record BooleanException(String type, boolean opensWhenTrue) { }
}
