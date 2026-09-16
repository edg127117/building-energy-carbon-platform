package com.platform.iot.daikin.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.monitoring.state.DaikinMonitoringStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 跨存储观测检查点：先保存有界原观测及归属快照，再写TDengine，最后写MySQL状态并清除载荷。
 * 两个数据库没有分布式事务；失败恢复重复使用原观测时间，依靠数值不可覆盖键和状态轮次实现幂等。
 */
@Service
public class DaikinObservationInbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final DaikinMonitoringTargets targets;
    private final DaikinTemperatureIngestion temperature;
    private final DaikinMonitoringStateService state;
    private final DaikinMonitoringProperties properties;
    private final Clock clock;

    @Autowired
    public DaikinObservationInbox(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, TransactionTemplate transaction,
            ObjectMapper mapper, DaikinMonitoringTargets targets, DaikinTemperatureIngestion temperature,
            DaikinMonitoringStateService state, DaikinMonitoringProperties properties) {
        this(jdbc, transaction, mapper, targets, temperature, state, properties, Clock.systemUTC());
    }

    DaikinObservationInbox(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper,
            DaikinMonitoringTargets targets, DaikinTemperatureIngestion temperature,
            DaikinMonitoringStateService state, DaikinMonitoringProperties properties, Clock clock) {
        this.jdbc=jdbc; this.transaction=transaction; this.mapper=mapper; this.targets=targets;
        this.temperature=temperature; this.state=state; this.properties=properties; this.clock=clock;
    }

    /** 调用方须持有该来源的活租约和来源行锁；同设备同计划轮次只保存第一份成功解析观测。 */
    public String stage(DaikinMonitoringTargets.Target target, long round, DaikinDeviceObservation observation) {
        if (!target.key().equals(observation.key())) throw new IllegalArgumentException("DAIKIN_IDENTITY_MISMATCH");
        String id = id(target.pendingId(), round);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_monitor_inbox WHERE observation_id=?", Integer.class, id) > 0) return id;
        // 来源锁在外层获取；全局容量锁只覆盖检查和插入，防止多来源同时越过积压上限。
        jdbc.queryForList("SELECT guard_id FROM biz_daikin_inbox_guard WHERE guard_id=1 FOR UPDATE", Integer.class);
        int pending = jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_monitor_inbox WHERE status<>'DONE'", Integer.class);
        if (pending >= properties.getMaxPendingObservations()) throw new IllegalStateException("DAIKIN_MONITORING_BACKLOG_FULL");
        try {
            String payload = mapper.writeValueAsString(observation);
            if (payload.length() > 65536) throw new IllegalArgumentException("DAIKIN_OBSERVATION_TOO_LARGE");
            jdbc.update("""
                    INSERT INTO biz_daikin_monitor_inbox(observation_id,source_id,pending_id,round_id,observed_at,
                      target_json,observation_json,status,attempts,next_attempt_at,lease_until)
                    VALUES (?,?,?,?,?,?,?,'PENDING',0,0,0)
                    """, id, target.key().sourceId(), target.pendingId(), round, observation.observedAt().toEpochMilli(),
                    mapper.writeValueAsString(new Checkpoint(target, temperature.bindingSnapshot(target))), payload);
            return id;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("DAIKIN_OBSERVATION_ENCODING_FAILED");
        }
    }

    public void replay() {
        List<String> ids = jdbc.queryForList("""
                SELECT observation_id FROM biz_daikin_monitor_inbox
                WHERE (status='PENDING' AND next_attempt_at<=?) OR (status='WRITING' AND lease_until<=?)
                ORDER BY observed_at DESC,observation_id LIMIT ?
                """, String.class, clock.millis(), clock.millis(), properties.getReplayBatchSize());
        for (String id : ids) process(id);
    }

    public void process(String id) {
        var sources = jdbc.queryForList("SELECT source_id FROM biz_daikin_monitor_inbox WHERE observation_id=?", String.class, id);
        if (sources.isEmpty()) return;
        Entry entry = transaction.execute(status -> {
            jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE", String.class, sources.getFirst());
            var rows = jdbc.query("""
                    SELECT source_id,target_json,observation_json,round_id,attempts FROM biz_daikin_monitor_inbox
                    WHERE observation_id=? AND ((status='PENDING' AND next_attempt_at<=?)
                      OR (status='WRITING' AND lease_until<=?)) FOR UPDATE
                    """, (rs, row) -> new Entry(id, rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getLong(4), rs.getInt(5)+1, UUID.randomUUID().toString()), id, clock.millis(), clock.millis());
            if (rows.isEmpty()) return null;
            Entry item = rows.getFirst();
            jdbc.update("""
                    UPDATE biz_daikin_monitor_inbox SET status='WRITING',attempts=?,lease_token=?,lease_until=?
                    WHERE observation_id=?
                    """, item.attempts(), item.token(), clock.millis()+properties.getLeaseSeconds()*1000L, id);
            return item;
        });
        if (entry == null) return;
        try {
            var checkpoint = mapper.readValue(entry.targetJson(), Checkpoint.class);
            var target = checkpoint.target();
            var observation = mapper.readValue(entry.observationJson(), DaikinDeviceObservation.class);
            // 停用或改归属后不把旧身份观测写到当前建筑。旧记录保持原归属，不通过当前映射改写历史。
            if (!targets.find(target.pendingId()).filter(target::equals).isPresent()
                    || !checkpoint.bindings().equals(temperature.bindingSnapshot(target))) {
                finish(entry, null, null);
                return;
            }
            var persisted = temperature.persist(target, observation);
            finish(entry, target, persisted);
        } catch (Exception failure) {
            long delay = Math.min(300000L, properties.getRetrySeconds()*1000L*(1L << Math.min(entry.attempts()-1, 10)));
            // 保留载荷供后续重放，退避有上限；不能用新一轮温度覆盖尚未持久化的旧观测。
            jdbc.update("""
                    UPDATE biz_daikin_monitor_inbox SET status='PENDING',lease_token=NULL,lease_until=0,
                      next_attempt_at=?,error_code='DAIKIN_OBSERVATION_PERSISTENCE_FAILED'
                    WHERE observation_id=? AND lease_token=? AND status='WRITING' AND lease_until>?
                    """, clock.millis()+delay, id, entry.token(), clock.millis());
        }
    }

    private void finish(Entry entry, DaikinMonitoringTargets.Target target, DaikinDeviceObservation observation) {
        transaction.executeWithoutResult(status -> {
            // 与目录、采集轮次统一先锁来源。租约已失效的writer不能修改状态；TDengine重写仍使用相同幂等键。
            jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE", String.class, entry.sourceId());
            int owned = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM biz_daikin_monitor_inbox WHERE observation_id=? AND lease_token=?
                      AND status='WRITING' AND lease_until>?
                    """, Integer.class, entry.id(), entry.token(), clock.millis());
            if (owned != 1) return;
            if (target != null && targets.find(target.pendingId()).filter(target::equals).isPresent()) {
                state.observe(stateTarget(target), entry.round(), observation);
            }
            jdbc.update("""
                    UPDATE biz_daikin_monitor_inbox SET status='DONE',observation_json=NULL,lease_token=NULL,
                      lease_until=0,error_code=? WHERE observation_id=?
                    """, target == null ? "DAIKIN_TARGET_NO_LONGER_ACTIVE" : null, entry.id());
        });
    }

    public static DaikinMonitoringStateService.Target stateTarget(DaikinMonitoringTargets.Target target) {
        return new DaikinMonitoringStateService.Target(target.key().sourceId(), target.pendingId(), target.identityId(),
                target.equipmentId(), target.buildingId(), target.spaceId(), target.systemGroupId(), target.mappingVersion());
    }

    private static String id(String pendingId, long round) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((Objects.requireNonNull(pendingId)+":"+round).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Entry(String id, String sourceId, String targetJson, String observationJson,
                         long round, int attempts, String token) { }

    private record Checkpoint(DaikinMonitoringTargets.Target target,
            java.util.Map<String, com.platform.iot.quality.PointRuntimeConfig> bindings) { }
}
