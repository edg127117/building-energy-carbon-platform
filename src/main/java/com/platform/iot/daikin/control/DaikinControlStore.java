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
        return reserve(user, target, request, hash, trace, now, false);
    }

    public Reservation reserve(long user, DaikinControlAccess.Target target, Submit request, String hash, String trace, long now, boolean fast) {
        return tx.execute(status -> {
            // 来源行串行化占用创建；物理键唯一约束继续防止两条平台身份控制同一厂家 URL。
            jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE", target.sourceId());
            Row existing = existing(user, request.idempotencyKey(), hash);
            if (existing != null) return new Reservation(existing, false);
            if (active(target.equipmentId()) != null || jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_slot WHERE physical_key=?",
                    Integer.class, target.physicalKey()) > 0) throw error(409, "BUSY");
            // 同一来源行锁跨实例有效；同时检查历史占用，避免升级前尚未建立控制器槽的记录漏过隔离。
            if (controllerBusy(target)) throw error(409, "CONTROLLER_BUSY");
            if (!fast) requireSourceReady(target.sourceId(), now);
            String id = id();
            jdbc.update("""
                    INSERT INTO biz_daikin_control_operation(operation_id,equipment_id,building_id,operator_id,source_id,
                     physical_key,target_json,action,requested_value,idempotency_key,request_hash,revision,status,
                     created_at,updated_at,next_check_at,trace_id,baseline_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PREPARING',?,?,?,?,?)
                    """, id, target.equipmentId(), target.buildingId(), user, target.sourceId(), target.physicalKey(), encode(target),
                    request.action().name(), request.value(), request.idempotencyKey(), hash, request.revision(), now, now, now + 30_000, trace,
                    fast ? "{\"_executionPolicy\":\"TEST_FAST_V1\"}" : null);
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

    /** 开放配置复用来源锁，避免更换许可使在途核验失效；配置与审计在每台设备事务中提交。 */
    public long openGrant(long actor, DaikinControlAccess.Target target, String relationship, String actions,
                          String reason, String trace, DaikinControlAccess access, long started, java.time.Clock clock) {
        return tx.execute(status -> {
            jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE", target.sourceId());
            jdbc.queryForList("SELECT equip_id FROM biz_equipment WHERE equip_id=? FOR UPDATE", target.equipmentId());
            long now = clock.millis();
            access.requireGrantAdmin(actor);
            access.requireRead(actor, target.equipmentId());
            if (!access.candidate(target.equipmentId()).equals(target)) throw error(409, "REVISION_CHANGED");
            if (now - started >= 30_000) throw error(409, "PREFLIGHT_EXPIRED");
            if (controllerBusy(target)) throw error(409, "CONTROLLER_BUSY");
            requireSourceReady(target.sourceId(), now);
            var previous = jdbc.queryForList("SELECT * FROM biz_daikin_control_grant WHERE equipment_id=? FOR UPDATE", target.equipmentId());
            // 保留旧确认说明与确认人，未选动作继承许可时仍可追溯原依据；摘要先放证据，避免长动作列表挤掉它。
            String beforeSummary = previous.isEmpty() ? null : "evidence=" + previous.getFirst().get("evidence")
                    + ";version=" + previous.getFirst().get("version") + ";confirmedBy=" + previous.getFirst().get("confirmed_by")
                    + ";confirmedAt=" + previous.getFirst().get("confirmed_at")
                    + ";relationship=" + previous.getFirst().get("relationship_hash")
                    + ";actionsHash=" + DaikinControlAccess.hash(java.util.Objects.toString(previous.getFirst().get("approved_actions"), ""));
            long version = previous.isEmpty() ? 1 : ((Number) previous.getFirst().get("version")).longValue();
            String merged = actions;
            boolean airFlow = false;
            boolean same = false;
            if (!previous.isEmpty()) {
                var p = previous.getFirst();
                same = target.identityId().equals(p.get("identity_id")) && target.sourceId().equals(p.get("source_id"))
                        && target.vendorId().equals(p.get("vendor_id")) && target.buildingId().equals(p.get("building_id"))
                        && target.mappingVersion() == ((Number) p.get("mapping_version")).intValue()
                        && relationship.equals(p.get("relationship_hash"));
                // 身份和关系不变时保留未选动作许可；关系变化不继承旧风向或其他动作的确认。
                if (same) {
                    try {
                        var old = json.readTree((String) p.get("approved_actions"));
                        var requested = json.readTree(actions);
                        if (old != null && old.isObject()) {
                            var object = (com.fasterxml.jackson.databind.node.ObjectNode) old;
                            requested.fields().forEachRemaining(entry -> object.set(entry.getKey(), entry.getValue()));
                            merged = object.toString();
                        }
                    } catch (JsonProcessingException | IllegalArgumentException e) { throw error(409, "ACTION_UNCONFIRMED"); }
                    airFlow = flag(p.get("air_flow_confirmed"));
                }
                same = same && flag(p.get("enabled"))
                        && merged.equals(p.get("approved_actions")) && reason.equals(p.get("evidence"));
                if (!same) version++;
            }
            if (previous.isEmpty()) {
                jdbc.update("""
                        INSERT INTO biz_daikin_control_grant(equipment_id,identity_id,source_id,vendor_id,building_id,mapping_version,
                         enabled,air_flow_confirmed,version,confirmed_by,confirmed_at,evidence,relationship_hash,approved_actions)
                        VALUES (?,?,?,?,?,?,TRUE,FALSE,?,?,?,?,?,?)
                        """, target.equipmentId(), target.identityId(), target.sourceId(), target.vendorId(), target.buildingId(),
                        target.mappingVersion(), version, actor, now, reason, relationship, merged);
            } else if (!same) {
                jdbc.update("""
                        UPDATE biz_daikin_control_grant SET identity_id=?,source_id=?,vendor_id=?,building_id=?,mapping_version=?,
                         enabled=TRUE,air_flow_confirmed=?,version=?,confirmed_by=?,confirmed_at=?,evidence=?,relationship_hash=?,approved_actions=?
                        WHERE equipment_id=?
                        """, target.identityId(), target.sourceId(), target.vendorId(), target.buildingId(), target.mappingVersion(),
                        airFlow, version, actor, now, reason, relationship, merged, target.equipmentId());
            }
            audit.append(new AuditEvidence("SYSTEM_SECURITY", target.buildingId(), "USER", actor, "HVAC_CONTROL_GRANT_OPEN",
                    "HVAC_CONTROL_GRANT", target.equipmentId(), Long.toString(version), null, beforeSummary,
                    "reason=" + reason + ";actions=" + actions + ";relationship=" + relationship
                            + ";mergedActionsHash=" + DaikinControlAccess.hash(merged), "SUCCESS", null, trace,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()), auditProperties.getEnvironmentMode(), false));
            return version;
        });
    }

    private static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() == 1;
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
            if (Set.of(Status.REJECTED, Status.NOT_SENT, Status.CLOUD_VERIFIED).contains(next)) releaseSlots(id);
            if (!DaikinControlPolicy.isFastBaseline(row.baseline()) && Set.of("10003", "10008").contains(vendorCode == null ? "" : vendorCode)) {
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
        return markSending(id, before, baseline, actor, now, 300_000);
    }

    public Row markSending(String id, String before, String baseline, long actor, long now, long timeout) {
        return tx.execute(status -> {
            jdbc.update("UPDATE biz_daikin_control_operation SET baseline_json=?,deadline_at=? WHERE operation_id=? AND status='PREPARING'",
                    baseline, now + timeout, id);
            return transition(id, Set.of(Status.PREPARING), Status.SENDING, null, null, before, now + 30_000, null, actor, now);
        });
    }

    /** 单次目标回显即可完成测试新操作；旧记录不迁入此策略，未知响应始终不升级成功。 */
    public Row observeFast(Row claimed, String settings, String observed, String unitStatus, boolean matches,
                           String failure, long requestedAt, long actor, long now) {
        return tx.execute(status -> {
            String id = claimed.view().operationId();
            Row row = jdbc.query("SELECT * FROM biz_daikin_control_operation WHERE operation_id=? FOR UPDATE", this::map, id).getFirst();
            if (!DaikinControlPolicy.isFastBaseline(row.baseline())) throw error(409, "REVISION_CHANGED");
            if (row.leaseVersion() != claimed.leaseVersion() || row.view().status() != claimed.view().status() || row.leaseUntil() <= now)
                return row;
            boolean expired = row.view().deadlineAt() == null || now >= row.view().deadlineAt();
            boolean verified = !expired && matches && failure == null && row.view().status() == Status.ACCEPTED
                    && "10000".equals(row.view().vendorCode());
            Status next = verified ? Status.CLOUD_VERIFIED : expired && row.view().status() == Status.ACCEPTED ? Status.UNCONFIRMED : row.view().status();
            String reason = failure != null ? failure : row.view().status() == Status.UNKNOWN ? "SEND_RESULT_UNKNOWN"
                    : verified ? "FAST_TARGET_READBACK_MATCH" : expired ? "FAST_TARGET_NOT_CONFIRMED" : "FAST_WAITING_READBACK";
            jdbc.update("INSERT INTO biz_daikin_control_sample(sample_id,operation_id,requested_at,read_at,device_sample_at,settings_json,matches_target,reason_code) VALUES(?,?,?,?,NULL,?,?,?)",
                    id(), id, requestedAt, now, settings, matches, reason);
            // 沿用旧列但不声称多次稳定；策略标记持久化在 baseline_json 并通过 API 明确返回。
            jdbc.update("UPDATE biz_daikin_control_operation SET stable_count=?,stable_since=NULL,last_read_at=?,unit_status=?,cloud_stable=FALSE,lease_until=0 WHERE operation_id=?",
                    matches ? 1 : 0, now, unitStatus, id);
            long elapsed = row.sentAt() == null ? 0 : Math.max(0, now - row.sentAt());
            long interval = elapsed < 15_000 ? 2_000 : elapsed < 30_000 ? 5_000 : 10_000;
            Long nextAt = !expired && !verified && next != Status.CLOSED ? Math.min(now + interval, row.view().deadlineAt()) : null;
            return transition(id, Set.of(row.view().status()), next, reason, null, observed, nextAt, null, actor, now);
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
            // 历史人工确认记录重新核验时可能回到 ACCEPTED；保留事件证据，升级不能把它自动解锁。
            boolean autoFinish = stable && !expired && current == Status.ACCEPTED && "10000".equals(row.view().vendorCode())
                    && jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_event WHERE operation_id=? AND status='OBSERVED_STABLE'",
                    Integer.class, id) == 0;
            if (Set.of(Status.ACCEPTED, Status.OBSERVED_STABLE).contains(current))
                next = autoFinish ? Status.CLOUD_VERIFIED
                        : stable && (!expired || current == Status.OBSERVED_STABLE) ? Status.OBSERVED_STABLE
                        : expired ? Status.UNCONFIRMED : Status.ACCEPTED;
            String reason = failure != null ? failure : current == Status.UNKNOWN ? "SEND_RESULT_UNKNOWN"
                    : next == Status.CLOUD_VERIFIED ? "CLOUD_VERIFIED_AUTO_FINISHED"
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
            // 仅明确受理且期限内稳定的正常操作自动结束；未知/超时与历史人工确认记录保留原恢复边界。
            // transition 的占用释放、样本和审计均在本事务中，审计失败不会留下半完成状态。
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

    public record RecoveryEvidence(String settings, int count, Long since, Long lastReadAt, String proof, boolean canRecover) { }

    /** 恢复样本与普通目标相符样本分开；摘要绑定当前管理员、正式身份和旧目标，不借用其他人的证据。 */
    public RecoveryEvidence recoveryEvidence(Row row, DaikinControlAccess.Target identity, long actor, long now) {
        // 毫秒相同仍以恢复证据为准；同刻失败优先使旧证明失效，不能由随机样本 ID 决定结果。
        var samples=jdbc.queryForList("SELECT sample_id,read_at,settings_json,reason_code FROM biz_daikin_control_sample WHERE operation_id=? ORDER BY read_at DESC,CASE WHEN reason_code LIKE 'RECOVERY_FAILED_%' THEN 2 WHEN reason_code LIKE 'RECOVERY_%' THEN 1 ELSE 0 END DESC,sample_id DESC LIMIT 20",
                row.view().operationId());
        if(samples.isEmpty()) return new RecoveryEvidence(null,0,null,null,null,false);
        var head=samples.getFirst(); long last=((Number)head.get("read_at")).longValue();
        // 普通回读不是新基线恢复的采样；不能用它阻止首个恢复读或借用其稳定计数。
        if(!(head.get("reason_code") instanceof String reason) || !reason.startsWith("RECOVERY_"))
            return new RecoveryEvidence(null,0,null,null,null,false);
        String settings=(String)head.get("settings_json");
        String key=recoveryKey(row,identity,actor,settings);
        if(settings==null || !key.equals(head.get("reason_code"))) return new RecoveryEvidence(null,0,null,last,null,false);
        int count=1; long since=last, accepted=last;
        for(int i=1;i<samples.size();i++) {
            var sample=samples.get(i); long at=((Number)sample.get("read_at")).longValue();
            if(!key.equals(sample.get("reason_code")) || !settings.equals(sample.get("settings_json")) || accepted-at>60_000) break;
            if(accepted-at>=30_000) { count++; since=at; accepted=at; }
        }
        boolean stable=count>=3 && last-since>=60_000 && now>=last && now-last<30_000;
        String proof=DaikinControlAccess.hash(key+"|"+head.get("sample_id")+"|"+since+"|"+count);
        return new RecoveryEvidence(settings,count,since,last,proof,stable);
    }

    public RecoveryEvidence observeRecovery(Row claimed, DaikinControlAccess.Target identity, long actor,
                                             String settings, boolean matchesOld, String failure, String trace,
                                             long requestedAt, java.time.Clock clock, DaikinControlAccess access) {
        return tx.execute(status -> {
            Row row=lockRecovery(claimed,identity,actor,requestedAt,clock,access);
            long now=clock.millis();
            String code=failure==null?recoveryKey(row,identity,actor,settings):"RECOVERY_FAILED_"+actor;
            recoverySample(row,settings,matchesOld,code,requestedAt,now);
            jdbc.update("UPDATE biz_daikin_control_operation SET lease_until=0 WHERE operation_id=?",row.view().operationId());
            recoveryAudit(row,actor,now,"HVAC_CONTROL_RECOVERY_READ",settings,failure,trace,null);
            return recoveryEvidence(row,identity,actor,now);
        });
    }

    /** 新基线不是旧命令成功证据；样本、异常关闭、双方占用释放和审计必须一起提交。 */
    public Row finishCurrentRecovery(Row claimed, DaikinControlAccess.Target identity, long actor, String proof,
                                     String settings, boolean matchesOld, String reason, String trace, long requestedAt,
                                     java.time.Clock clock, DaikinControlAccess access) {
        return tx.execute(status -> {
            Row row=lockRecovery(claimed,identity,actor,requestedAt,clock,access); long now=clock.millis();
            var evidence=recoveryEvidence(row,identity,actor,now);
            if(!evidence.canRecover() || !proof.equals(evidence.proof())) throw error(409,"RECOVERY_PROOF_EXPIRED");
            if(!settings.equals(evidence.settings())) throw error(409,"RECOVERY_BASELINE_MISMATCH");
            recoverySample(row,settings,matchesOld,recoveryKey(row,identity,actor,settings),requestedAt,now);
            // 不覆盖旧关闭说明；新的现场恢复说明独立写审计，原请求、旧样本及云端目标稳定标记均保留。
            transition(row.view().operationId(),Set.of(row.view().status()),Status.CLOSED,"RECOVERY_CURRENT_BASELINE_RELEASED",
                    null,row.view().observedValue(),null,row.view().closedReason()==null?reason:null,actor,now);
            releaseSlots(row.view().operationId());
            jdbc.update("UPDATE biz_daikin_control_operation SET lease_until=0 WHERE operation_id=?",row.view().operationId());
            recoveryAudit(row,actor,now,"HVAC_CONTROL_RECOVERY_RELEASE",settings,null,trace,reason);
            return get(row.view().operationId());
        });
    }

    private Row lockRecovery(Row claimed, DaikinControlAccess.Target identity, long actor, long requestedAt,
                             java.time.Clock clock, DaikinControlAccess access) {
        return lockRecovery(claimed, identity, actor, requestedAt, clock, access, false);
    }

    public Row finishTestRecovery(Row claimed, DaikinControlAccess.Target identity, long actor, String settings,
                                  boolean matchesOld, String reason, String trace, long requestedAt,
                                  java.time.Clock clock, DaikinControlAccess access) {
        return tx.execute(status -> {
            Row row = lockRecovery(claimed, identity, actor, requestedAt, clock, access, true);
            long now = clock.millis();
            recoverySample(row, settings, matchesOld, "TEST_CURRENT_STATE_READ", requestedAt, now);
            transition(row.view().operationId(), Set.of(row.view().status()), Status.CLOSED, "TEST_CURRENT_STATE_RELEASED",
                    null, row.view().observedValue(), null, row.view().closedReason() == null ? reason : null, actor, now);
            releaseSlots(row.view().operationId());
            jdbc.update("UPDATE biz_daikin_control_operation SET lease_until=0 WHERE operation_id=?", row.view().operationId());
            recoveryAudit(row, actor, now, "HVAC_CONTROL_TEST_CONTINUE", settings, null, trace, reason);
            return get(row.view().operationId());
        });
    }

    private Row lockRecovery(Row claimed, DaikinControlAccess.Target identity, long actor, long requestedAt,
                             java.time.Clock clock, DaikinControlAccess access, boolean simple) {
        // 与开放和新操作共用来源锁，先锁来源/设备再锁操作，网络读取不占用事务。
        jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",identity.sourceId());
        jdbc.queryForList("SELECT equip_id FROM biz_equipment WHERE equip_id=? FOR UPDATE",identity.equipmentId());
        Row row=jdbc.query("SELECT * FROM biz_daikin_control_operation WHERE operation_id=? FOR UPDATE",this::map,claimed.view().operationId()).getFirst();
        long now=clock.millis();
        if (simple) {
            if (!access.testOpenControls()) throw error(403, "FORBIDDEN");
            DaikinControlRecoveryService.requireTestRecoverable(row);
        } else DaikinControlRecoveryService.requireRecoverable(row,now);
        access.requireGrantAdmin(actor); access.requireRead(actor,identity.equipmentId());
        if(!identity.equals(access.candidate(identity.equipmentId())) || !identity.equals(DaikinControlAccess.identity(row.target()))
                || row.leaseVersion()!=claimed.leaseVersion() || row.view().status()!=claimed.view().status()) throw error(409,"REVISION_CHANGED");
        if(now-requestedAt>=30_000 || now<requestedAt || row.leaseUntil()<=now) throw error(409,"PREFLIGHT_EXPIRED");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_slot WHERE physical_key=? AND operation_id=?",
                Integer.class,identity.physicalKey(),row.view().operationId())!=1) throw error(409,"REVISION_CHANGED");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot WHERE controller_key=? AND operation_id<>?",
                Integer.class,identity.controllerKey(),row.view().operationId())>0) throw error(409,"CONTROLLER_BUSY");
        if (!simple) requireSourceReady(identity.sourceId(),now);
        return row;
    }
    private static String recoveryKey(Row row, DaikinControlAccess.Target identity, long actor, String settings) {
        return "RECOVERY_"+DaikinControlAccess.hash(actor+"|"+row.view().operationId()+"|"+row.target().fingerprint()+"|"+identity.fingerprint()+"|"+settings);
    }
    private void recoverySample(Row row, String settings, boolean matchesOld, String reason, long requested, long now) {
        jdbc.update("INSERT INTO biz_daikin_control_sample(sample_id,operation_id,requested_at,read_at,device_sample_at,settings_json,matches_target,reason_code) VALUES(?,?,?,?,NULL,?,?,?)",
                id(),row.view().operationId(),requested,now,settings,matchesOld,reason);
    }
    private void recoveryAudit(Row row, long actor, long now, String action, String settings, String failure, String trace, String reason) {
        audit.append(new AuditEvidence("SYSTEM_SECURITY",row.buildingId(),"USER",actor,action,"HVAC_CONTROL_OPERATION",row.view().operationId(),
                row.revision(),null,"status="+row.view().status()+";originalAction="+row.view().action()+";originalValue="+row.view().value()+";oldReason="+row.view().closedReason(),
                "reason="+reason+";currentSettings="+settings+";oldTargetUnchanged=true;resultIsRecoveryOnly=true",
                failure==null?"SUCCESS":"FAILED",failure,trace,LocalDateTime.ofInstant(Instant.ofEpochMilli(now),ZoneId.systemDefault()),
                auditProperties.getEnvironmentMode(),false));
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
                null, "status=" + op.status() + ";action=" + op.action() + ";value=" + op.value() + ";observed=" + op.observedValue()
                        + ";stableCount=" + op.stableCount() + ";cloudStable=" + op.cloudStable() + ";reason=" + op.closedReason(),
                // 统一审计表沿用服务端本地 DATETIME 口径；操作表/API 仍保存无歧义的 epoch 毫秒。
                auditResult(op.status()), op.reasonCode(), row.traceId(), LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()),
                auditProperties.getEnvironmentMode(), false));
    }

    // 通用审计沿用 V25 的结果枚举；控制阶段保留在动作名和摘要，不将受理或未知结果称为物理执行结论。
    private static String auditResult(Status status) {
        return switch (status) {
            case REJECTED, NOT_SENT -> "REJECTED";
            case UNKNOWN, UNCONFIRMED -> "FAILED";
            case PREPARING, SENDING, ACCEPTED, OBSERVED_MATCH, OBSERVED_STABLE, CLOUD_VERIFIED, CONFIRMED, CLOSED -> "SUCCESS";
        };
    }

    private Row map(ResultSet rs, int n) throws SQLException {
        Operation view = new Operation(rs.getString("operation_id"), rs.getString("equipment_id"), Action.valueOf(rs.getString("action")),
                rs.getString("requested_value"), Status.valueOf(rs.getString("status")), rs.getString("reason_code"), rs.getString("vendor_code"),
                rs.getString("observed_value"), rs.getLong("created_at"), rs.getLong("updated_at"), rs.getString("closed_reason"),
                rs.getObject("deadline_at", Long.class), rs.getInt("stable_count"), rs.getObject("stable_since", Long.class),
                rs.getObject("last_read_at", Long.class), rs.getString("unit_status"),
                jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_slot WHERE operation_id=?", Integer.class, rs.getString("operation_id")) > 0,
                rs.getBoolean("cloud_stable"), DaikinControlPolicy.isFastBaseline(rs.getString("baseline_json")) ? DaikinControlPolicy.TEST_FAST : "LEGACY_STABLE");
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
