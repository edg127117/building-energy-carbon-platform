package com.platform.iot.daikin.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.monitoring.DaikinMonitoringTargets.Target;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * 调用方须在来源租约事务中复核目标后写入。失败只更新尝试信息，成功值与修订独立保留。
 * 尚无历史归属生效时间链，厂家历史统一标为归属未核实，只供单设备详情，禁止参与历史房间汇总。
 */
@Service
public class DaikinRuntimeStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DaikinRuntimeStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void save(Target target, DaikinRuntimePeriod period, String unit,
              DaikinRuntimeClientProvider.Reading reading, String attemptStatus, long now) {
        // 归属或映射改变后保留旧快照，不用新归属覆盖历史值；查询还需过滤当前建筑与映射。
        String id = hash(target.key().sourceId(), target.identityId(), target.equipmentId(), target.buildingId(),
                String.valueOf(target.mappingVersion()), period.granularity().name(),
                String.valueOf(period.startMillis()), String.valueOf(period.endMillis()));
        var old = jdbc.query("SELECT metrics_json,period_complete,revision_no FROM biz_daikin_runtime_value WHERE value_id=? FOR UPDATE",
                (rs, row) -> new Previous(rs.getString(1), rs.getBoolean(2), rs.getInt(3)), id);
        if (old.isEmpty()) {
            jdbc.update("""
                    INSERT INTO biz_daikin_runtime_value(value_id,identity_id,source_id,equipment_id,building_id,
                      space_id,system_group_id,mapping_version,granularity,period_start_ms,period_end_ms,
                      statistics_zone,unit,last_attempt_at_ms,last_attempt_status)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, target.identityId(), target.key().sourceId(), target.equipmentId(), target.buildingId(),
                    target.spaceId(), target.systemGroupId(), target.mappingVersion(), period.granularity().name(),
                    period.startMillis(), period.endMillis(), period.zone().getId(), unit, now, attemptStatus);
        }
        if (reading == null || reading.status() != DaikinRuntimeClientProvider.Status.PRESENT) {
            jdbc.update("UPDATE biz_daikin_runtime_value SET last_attempt_at_ms=?,last_attempt_status=? WHERE value_id=?",
                    now, attemptStatus, id);
            return;
        }
        String json;
        try {
            var canonical = new TreeMap<String, java.math.BigDecimal>();
            reading.metrics().forEach((key, value) -> canonical.put(key, value.stripTrailingZeros()));
            json = mapper.writeValueAsString(canonical);
        } catch (JsonProcessingException error) { throw new IllegalArgumentException("DAIKIN_RUNTIME_INVALID_METRICS", error); }
        boolean complete = reading.complete() && period.endMillis() <= now;
        Previous previous = old.isEmpty() ? new Previous(null, false, 0) : old.getFirst();
        int revision = previous.revision();
        if (!json.equals(previous.json()) || complete != previous.complete()) {
            revision++;
            jdbc.update("""
                    INSERT INTO biz_daikin_runtime_revision(revision_id,value_id,revision_no,identity_id,source_id,
                      equipment_id,building_id,space_id,system_group_id,mapping_version,granularity,period_start_ms,
                      period_end_ms,statistics_zone,unit,metrics_json,period_complete,ownership_verified,observed_at_ms)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)
                    """, hash(id, String.valueOf(revision)), id, revision, target.identityId(), target.key().sourceId(),
                    target.equipmentId(), target.buildingId(), target.spaceId(), target.systemGroupId(), target.mappingVersion(),
                    period.granularity().name(), period.startMillis(), period.endMillis(), period.zone().getId(),
                    unit, json, complete, now);
        }
        jdbc.update("""
                UPDATE biz_daikin_runtime_value SET metrics_json=?,period_complete=?,revision_no=?,last_success_at_ms=?,
                  last_attempt_at_ms=?,last_attempt_status='PRESENT' WHERE value_id=?
                """, json, complete, revision, now, now, id);
    }

    static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Previous(String json, boolean complete, int revision) { }
}
