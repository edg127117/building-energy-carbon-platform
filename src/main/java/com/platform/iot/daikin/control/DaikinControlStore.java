package com.platform.iot.daikin.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.framework.web.PageResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static com.platform.iot.daikin.control.DaikinControlErrors.error;

/** 操作/设备占用/审计同库提交；不在事务中发送 HTTP，发送中记录永远不重新领取为写请求。 */
@Repository
public class DaikinControlStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;

    public DaikinControlStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, TransactionTemplate tx,
                              ObjectMapper json, AuditEvidenceWriter audit, AuditGovernanceProperties auditProperties) {
        this.jdbc = jdbc; this.tx = tx; this.json = json; this.audit = audit; this.auditProperties = auditProperties;
    }

    public record Row(Operation view, long userId, String buildingId, DaikinControlAccess.Target target,
                      String revision, String requestHash, Long sentAt, Long nextCheckAt, int checkCount, String traceId,
                      String baseline, long leaseVersion, long leaseUntil) { }
    public record Reservation(Row row, boolean created) { }

    public Row get(String id) {
        var rows = jdbc.query("SELECT * FROM biz_daikin_control_operation WHERE operation_id=?", this::map, id);
        if (rows.size() != 1) throw error(404, "OPERATION_NOT_FOUND");
        return rows.getFirst();
    }

    public Row existing(long user, String key, String requestHash) {
        var rows = jdbc.query("SELECT * FROM biz_daikin_control_operation WHERE operator_id=? AND idempotency_key=?", this::map, user, key);
        if (rows.isEmpty()) return null;
        if (!rows.getFirst().requestHash().equals(requestHash)) throw error(409, "IDEMPOTENCY_CONFLICT");
        return rows.getFirst();
    }

    public String active(String equipment) {
        var values = jdbc.queryForList("""
                SELECT o.operation_id FROM biz_daikin_control_operation o JOIN biz_daikin_control_slot s
                  ON s.operation_id=o.operation_id WHERE o.equipment_id=? LIMIT 1
                """, String.class, equipment);
        return values.isEmpty() ? null : values.getFirst();
    }

    public boolean controllerBusy(DaikinControlAccess.Target target) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot WHERE controller_key=?",
                Integer.class, target.controllerKey()) > 0) return true;
        return jdbc.query("SELECT o.* FROM biz_daikin_control_operation o JOIN biz_daikin_control_slot s ON s.operation_id=o.operation_id WHERE o.source_id=?",
                this::map, target.sourceId()).stream().anyMatch(row -> java.util.Objects.equals(row.target().controllerId(), target.controllerId()));
    }

    public Reservation reserve(long user, DaikinControlAccess.Target target, Submit request, String hash, String trace, long now) {
        return tx.execute(status -> {
            // 来源行串行化占用创建；物理键唯一约束继续防止两条平台身份控制同一厂家 URL。
            jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE", target.sourceId());
            Row existing = existing(user, request.idempotencyKey(), hash);
            if (existing != null) return new Reservation(existing, false);
            if (active(target.equipmentId()) != null || jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_slot WHERE physical_key=?",
                    Integer.class, target.physicalKey()) > 0) throw error(409, "BUSY");
            // 同一来源行锁跨实例有效；同时检查历史占用，避免升级前尚未建立控制器槽的记录漏过隔离。
            if (controllerBusy(target)) throw error(409, "CONTROLLER_BUSY");
            requireSourceReady(target.sourceId(), now);
            String id = id();
            jdbc.update("""
                    INSERT INTO biz_daikin_control_operation(operation_id,equipment_id,building_id,operator_id,source_id,
                     physical_key,target_json,action,requested_value,idempotency_key,request_hash,revision,status,
                     created_at,updated_at,next_check_at,trace_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PREPARING',?,?,?,?)
                    """, id, target.equipmentId(), target.buildingId(), user, target.sourceId(), target.physicalKey(), encode(target),
                    request.action().name(), request.value(), request.idempotencyKey(), hash, request.revision(), now, now, now + 30_000, trace);
            jdbc.update("INSERT INTO biz_daikin_control_slot(physical_key,operation_id) VALUES (?,?)", target.physicalKey(), id);
            jdbc.update("INSERT INTO biz_daikin_control_controller_slot(controller_key,operation_id) VALUES (?,?)", target.controllerKey(), id);
            Row row = get(id);
            evidence(row, user, now);
            return new Reservation(row, true);
        });
    }

    public void requireSourceReady(String source, long now) {
        var rows = jdbc.queryForList("SELECT paused_until FROM biz_daikin_control_source WHERE source_id=?", Long.class, source);
        if (!rows.isEmpty() && rows.getFirst() > now) throw error(429, "SOURCE_PAUSED");
    }

    public Row transition(String id, Set<Status> expected, Status next, String reason, String vendorCode,
                          String observed, Long nextCheck, String closeReason, long actor, long now) {
        return tx.execute(status -> {
            var row = jdbc.query("SELECT * FROM biz_daikin_control_operation WHERE operation_id=? FOR UPDATE", this::map, id).getFirst();
            if (!expected.contains(row.view().status())) return row;
            jdbc.update("""
                    UPDATE biz_daikin_control_operation SET status=?,reason_code=?,vendor_code=COALESCE(?,vendor_code),
                    observed_value=?,updated_at=?,next_check_at=?,closed_reason=COALESCE(?,closed_reason),
                    sent_at=CASE WHEN ?='SENDING' THEN ? ELSE sent_at END,
                    before_value=CASE WHEN ?='SENDING' THEN ? ELSE before_value END WHERE operation_id=?
                    """, next.name(), reason, vendorCode, observed, now, nextCheck, closeReason,
                    next.name(), now, next.name(), observed, id);
            if (Set.of(Status.REJECTED, Status.NOT_SENT).contains(next)) releaseSlots(id);
            if (Set.of("10003", "10008").contains(vendorCode == null ? "" : vendorCode)) {
                jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE", row.target().sourceId());
                if (jdbc.update("UPDATE biz_daikin_control_source SET paused_until=? WHERE source_id=?", now + 60_000, row.target().sourceId()) == 0)
                    jdbc.update("INSERT INTO biz_daikin_control_source(source_id,paused_until) VALUES (?,?)", row.target().sourceId(), now + 60_000);
            }
            Row changed = get(id); evidence(changed, actor, now); return changed;
        });
    }

    /** CAS 领取只读核验；30秒租约覆盖10秒HTTP超时，多个实例不能重复同时核验同一记录。 */
    public boolean claimCheck(Row row, long now) {
        return jdbc.update("""
                UPDATE biz_daikin_control_operation SET lease_until=?,lease_version=lease_version+1,check_count=check_count+1
                WHERE operation_id=? AND status=? AND next_check_at=? AND next_check_at<=? AND lease_until<=?
                """, now + 30_000, row.view().operationId(), row.view().status().name(), row.nextCheckAt(), now, now) == 1;
    }

    public boolean claimManual(Row row, long now) {
        return jdbc.update("""
                UPDATE biz_daikin_control_operation SET lease_until=?,lease_version=lease_version+1,check_count=check_count+1
                WHERE operation_id=? AND status=? AND lease_version=? AND lease_until<=?
                """, now + 30_000, row.view().operationId(), row.view().status().name(), row.leaseVersion(), now) == 1;
    }

    public Row markSending(String id, String before, String baseline, long actor, long now) {
        return tx.execute(status -> {
            jdbc.update("UPDATE biz_daikin_control_operation SET baseline_json=?,deadline_at=? WHERE operation_id=? AND status='PREPARING'",
                    baseline, now + 300_000, id);
            return transition(id, Set.of(Status.PREPARING), Status.SENDING, null, null, before, now + 30_000, null, actor, now);
        });
    }

    /** 样本、稳定进度及审计原子提交；租约代数阻止超时的旧读取覆盖新核验。 */
    public Row observe(Row claimed, String settings, String observed, String unitStatus, boolean matches,
                       String failure, long requestedAt, long actor, long now) {
        return tx.execute(status -> {
            String id = claimed.view().operationId();
            Row row = jdbc.query("SELECT * FROM biz_daikin_control_operation WHERE operation_id=? FOR UPDATE", this::map, id).getFirst();
            if (row.leaseVersion() != claimed.leaseVersion() || row.view().status() != claimed.view().status()) return row;
            // 不接受同一时刻的重复人工刷新作为连续采样证据；无设备采样时间，结论只能是云端稳定。
            boolean separated = row.view().lastReadAt() == null || now - row.view().lastReadAt() >= 30_000;
            // 调度中断超过两轮后必须重新积累，不能把很久以前的稳定样本用于当前解除隔离。
            boolean continuous = row.view().lastReadAt() != null && now - row.view().lastReadAt() <= 60_000;
            int count = matches ? (!continuous ? 1 : separated ? row.view().stableCount() + 1 : row.view().stableCount()) : 0;
            Long since = matches ? (!continuous || row.view().stableSince() == null ? now : row.view().stableSince()) : null;
            boolean stable = matches && count >= 3 && since != null && now - since >= 60_000
                    && row.sentAt() != null && now - row.sentAt() >= 180_000;
            boolean expired = row.view().deadlineAt() == null || now >= row.view().deadlineAt();
            Status current = row.view().status();
            Status next = current;
            if (Set.of(Status.ACCEPTED, Status.OBSERVED_STABLE).contains(current))
                next = stable && (!expired || current == Status.OBSERVED_STABLE) ? Status.OBSERVED_STABLE
                        : expired ? Status.UNCONFIRMED : Status.ACCEPTED;
            String reason = failure != null ? failure : current == Status.UNKNOWN ? "SEND_RESULT_UNKNOWN"
                    : next == Status.CLOSED ? row.view().reasonCode() : stable ? "CLOUD_STABLE_AWAITING_ONSITE"
                    : expired ? "READBACK_NOT_CONFIRMED" : matches ? "MATCH_OBSERVED" : "READBACK_MISMATCH";
            jdbc.update("""
                    INSERT INTO biz_daikin_control_sample(sample_id,operation_id,requested_at,read_at,device_sample_at,settings_json,matches_target,reason_code)
                    VALUES (?,?,?,?,NULL,?,?,?)
                    """, id(), id, requestedAt, now, settings, matches, reason);
            jdbc.update("""
                    UPDATE biz_daikin_control_operation SET stable_count=?,stable_since=?,last_read_at=?,unit_status=?,cloud_stable=?,lease_until=0
                    WHERE operation_id=?
                    """, count, since, now, unitStatus, stable, id);
            Long nextAt = !expired && !stable && current != Status.CLOSED ? Math.min(now + 30_000, row.view().deadlineAt()) : null;
            return transition(id, Set.of(current), next, reason, null, observed, nextAt, null, actor, now);
        });
    }

    public Row finish(String id, Set<Status> expected, boolean recovery, String reason, long actor, long now) {
        return tx.execute(status -> {
            Row row = jdbc.query("SELECT * FROM biz_daikin_control_operation WHERE operation_id=? FOR UPDATE", this::map, id).getFirst();
            if (row.leaseUntil() > now) throw error(409, "CHECK_BUSY");
            if (!expected.contains(row.view().status()) || !row.view().isolated() || !row.view().cloudStable()
                    || row.view().lastReadAt() == null || now - row.view().lastReadAt() > 30_000) throw error(409, "ONSITE_CONFIRMATION_REQUIRED");
            // 解除不确定隔离与关闭记录是两个动作；原始厂家码和历史事件保留，不声称撤销在途指令。
            Row finished = transition(id, expected, recovery ? Status.CLOSED : Status.CONFIRMED,
                    recovery ? "ISOLATION_RELEASED_" + row.view().status() : "ONSITE_CONFIRMED", null,
                    row.view().observedValue(), null, reason, actor, now);
            releaseSlots(id);
            return get(finished.view().operationId());
        });
    }

    private void releaseSlots(String id) {
        jdbc.update("DELETE FROM biz_daikin_control_slot WHERE operation_id=?", id);
        jdbc.update("DELETE FROM biz_daikin_control_controller_slot WHERE operation_id=?", id);
    }

    public List<Row> due(long now) {
        return jdbc.query("SELECT * FROM biz_daikin_control_operation WHERE next_check_at<=? ORDER BY next_check_at LIMIT 20", this::map, now);
    }

    public PageResponse<Operation> history(String equipment, String building, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw error(400, "INVALID_REQUEST");
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_operation WHERE equipment_id=? AND building_id=?", Long.class, equipment, building);
        var values = jdbc.query("SELECT * FROM biz_daikin_control_operation WHERE equipment_id=? AND building_id=? ORDER BY created_at DESC,operation_id DESC LIMIT ? OFFSET ?",
                this::map, equipment, building, size, (long) (page - 1) * size).stream().map(Row::view).toList();
        return new PageResponse<>(page, size, total, values);
    }

    private void evidence(Row row, long actor, long now) {
        Operation op = row.view();
        jdbc.update("""
                INSERT INTO biz_daikin_control_event(event_id,operation_id,actor_id,status,reason_code,vendor_code,observed_value,operation_time)
                VALUES (?,?,?,?,?,?,?,?)
                """, id(), op.operationId(), actor, op.status().name(), op.reasonCode(), op.vendorCode(), op.observedValue(), now);
        // 不走吞掉写入异常的安全拒绝日志服务：发送意图的审计失败必须一起回滚，禁止实际下发。
        audit.append(new AuditEvidence("SYSTEM_SECURITY", row.buildingId(), actor == 0 ? "SYSTEM" : "USER", actor == 0 ? null : actor,
                "HVAC_CONTROL_" + op.status().name(), "HVAC_CONTROL_OPERATION", op.operationId(), row.revision(), null,
                null, "action=" + op.action() + ";value=" + op.value() + ";observed=" + op.observedValue()
                        + ";stableCount=" + op.stableCount() + ";cloudStable=" + op.cloudStable() + ";reason=" + op.closedReason(),
                // 统一审计表沿用服务端本地 DATETIME 口径；操作表/API 仍保存无歧义的 epoch 毫秒。
                op.status().name(), op.reasonCode(), row.traceId(), LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()),
                auditProperties.getEnvironmentMode(), false));
    }

    private Row map(ResultSet rs, int n) throws SQLException {
        Operation view = new Operation(rs.getString("operation_id"), rs.getString("equipment_id"), Action.valueOf(rs.getString("action")),
                rs.getString("requested_value"), Status.valueOf(rs.getString("status")), rs.getString("reason_code"), rs.getString("vendor_code"),
                rs.getString("observed_value"), rs.getLong("created_at"), rs.getLong("updated_at"), rs.getString("closed_reason"),
                rs.getObject("deadline_at", Long.class), rs.getInt("stable_count"), rs.getObject("stable_since", Long.class),
                rs.getObject("last_read_at", Long.class), rs.getString("unit_status"),
                jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_slot WHERE operation_id=?", Integer.class, rs.getString("operation_id")) > 0,
                rs.getBoolean("cloud_stable"));
        try {
            return new Row(view, rs.getLong("operator_id"), rs.getString("building_id"), json.readValue(rs.getString("target_json"), DaikinControlAccess.Target.class),
                    rs.getString("revision"), rs.getString("request_hash"), rs.getObject("sent_at", Long.class),
                    rs.getObject("next_check_at", Long.class), rs.getInt("check_count"), rs.getString("trace_id"),
                    rs.getString("baseline_json"), rs.getLong("lease_version"), rs.getLong("lease_until"));
        } catch (JsonProcessingException e) { throw new IllegalStateException("控制目标快照无法解析"); }
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("控制快照无效"); } }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
