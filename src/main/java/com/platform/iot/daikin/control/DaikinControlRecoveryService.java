package com.platform.iot.daikin.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static com.platform.iot.daikin.control.DaikinControlErrors.error;

/** 异常恢复仅依赖只读接口；TEST 一次读取后继续，其他环境保留原超时与现场基线证据。 */
@Service
public class DaikinControlRecoveryService {
    private final DaikinControlAccess access;
    private final DaikinControlStore store;
    private final DaikinControlPolicy policy;
    private final List<DaikinControlGrantReader> readers;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public DaikinControlRecoveryService(DaikinControlAccess access, DaikinControlStore store,
                                       DaikinControlPolicy policy, List<DaikinControlGrantReader> readers) {
        this(access, store, policy, readers, Clock.systemUTC());
    }
    DaikinControlRecoveryService(DaikinControlAccess access, DaikinControlStore store,
                                 DaikinControlPolicy policy, List<DaikinControlGrantReader> readers, Clock clock) {
        this.access=access; this.store=store; this.policy=policy; this.readers=List.copyOf(readers); this.clock=clock;
    }

    public RecoveryCandidate candidate(long actor, String equipment) {
        access.requireGrantAdmin(actor); access.requireRead(actor, equipment);
        String id=store.active(equipment);
        if(id==null) return new RecoveryCandidate(null,false,"NO_ISOLATION",access.testOpenControls());
        var row=store.get(id);
        try { ready(actor,row); return new RecoveryCandidate(row.view(),true,null,access.testOpenControls()); }
        catch(BusinessException e) {
            if("FORBIDDEN".equals(reason(e))) throw e;
            return new RecoveryCandidate(row.view(),false,reason(e),access.testOpenControls());
        }
    }

    /** 测试异常只做一次最新读取；关闭异常不把旧请求升级成功，也不重发任何命令。 */
    public Operation continueCurrent(long actor, String id, Close body, String trace) {
        if (!access.testOpenControls()) throw error(403, "FORBIDDEN");
        if (body == null || body.reason() == null || body.reason().strip().length() < 5 || body.reason().length() > 500
                || body.reason().chars().anyMatch(Character::isISOControl)) throw error(400, "INVALID_REQUEST");
        var row = store.get(id); access.requireAccount(actor); access.requireRead(actor, row.view().equipmentId());
        if (!row.view().isolated() && row.view().status() == Status.CLOSED
                && "TEST_CURRENT_STATE_RELEASED".equals(row.view().reasonCode())) return row.view();
        var target = ready(actor, row);
        if (!store.claimManual(row, clock.millis())) throw error(409, "CHECK_BUSY");
        var claimed = store.get(id); long started = clock.millis();
        var available = readers.stream().filter(r -> r.supports(target.sourceId())).toList();
        if (available.size() != 1) throw error(503, "PROVIDER_UNAVAILABLE");
        DaikinControlGateway.Snapshot snapshot;
        try { snapshot = available.getFirst().read(target); }
        catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) { throw error(503, "RECOVERY_READ_UNAVAILABLE"); }
        String settings = policy.testSettings(target, snapshot);
        // 恢复本次动作只要求其当前字段有效；不支持温度的内机也能恢复风速等动作的异常。
        String observed = policy.testObserved(settings, claimed.view().action());
        if (observed == null)
            throw error(409, "READBACK_INVALID");
        boolean matchesOld = policy.testMatches(settings, claimed.view().action(), claimed.view().value());
        return store.finishTestRecovery(claimed, target, actor, settings, observed, snapshot.unit().path("unitStatus").asText(),
                matchesOld, body.reason().strip(), trace, started, clock, access).view();
    }

    public RecoveryProgress observe(long actor, String id, RecoveryBaseline body, String trace) {
        validate(body);
        var row=store.get(id); var target=ready(actor,row);
        var previous=store.recoveryEvidence(row,target,actor,clock.millis());
        if(previous.lastReadAt()!=null && clock.millis()-previous.lastReadAt()<30_000)
            return progress(id,previous,"RECOVERY_SAMPLE_INTERVAL");
        if(!store.claimManual(row,clock.millis())) throw error(409,"CHECK_BUSY");
        var claimed=store.get(id); long started=clock.millis();
        String settings=null, failure=null;
        try { settings=read(target,claimed,body); }
        catch(BusinessException e) { failure=reason(e); }
        catch(RuntimeException e) { failure="RECOVERY_READ_UNAVAILABLE"; }
        boolean matchesOld=settings!=null && policy.matches(claimed.baseline(),settings,claimed.view().action(),claimed.view().value());
        var evidence=store.observeRecovery(claimed,target,actor,settings,matchesOld,failure,trace,started,clock,access);
        return progress(id,evidence,failure==null ? evidence.canRecover()?null:"RECOVERY_PENDING":failure);
    }

    public Operation complete(long actor, String id, RecoverCurrentBaseline body, String trace) {
        if(body==null || !body.onsiteConfirmed() || body.proof()==null || !body.proof().matches("[a-f0-9]{64}")
                || body.reason()==null || body.reason().strip().length()<5 || body.reason().length()>500
                || body.reason().chars().anyMatch(Character::isISOControl)) throw error(400,"INVALID_REQUEST");
        var baseline=new RecoveryBaseline(body.onOff(),body.temperature()); validate(baseline);
        access.requireGrantAdmin(actor);
        var row=store.get(id); access.requireRead(actor,row.view().equipmentId());
        if(!row.buildingId().equals(access.requireRead(actor,row.view().equipmentId()))) throw error(409,"REVISION_CHANGED");
        if(!row.view().isolated() && row.view().status()==Status.CLOSED
                && "RECOVERY_CURRENT_BASELINE_RELEASED".equals(row.view().reasonCode())) return row.view();
        var target=ready(actor,row);
        var evidence=store.recoveryEvidence(row,target,actor,clock.millis());
        if(!evidence.canRecover() || !body.proof().equals(evidence.proof())) throw error(409,"RECOVERY_PROOF_EXPIRED");
        if(!store.claimManual(row,clock.millis())) throw error(409,"CHECK_BUSY");
        var claimed=store.get(id); long started=clock.millis();
        String settings;
        try {
            settings=read(target,claimed,baseline);
            if(!settings.equals(evidence.settings())) throw error(409,"RECOVERY_BASELINE_MISMATCH");
        } catch(RuntimeException e) {
            String failure=e instanceof BusinessException b?reason(b):"RECOVERY_READ_UNAVAILABLE";
            store.observeRecovery(claimed,target,actor,null,false,failure,trace,started,clock,access);
            throw error(409,failure);
        }
        boolean matchesOld=policy.matches(claimed.baseline(),settings,claimed.view().action(),claimed.view().value());
        return store.finishCurrentRecovery(claimed,target,actor,body.proof(),settings,matchesOld,
                body.reason().strip(),trace,started,clock,access).view();
    }

    private DaikinControlAccess.Target ready(long actor, DaikinControlStore.Row row) {
        access.requireGrantAdmin(actor);
        if(!row.buildingId().equals(access.requireRead(actor,row.view().equipmentId()))) throw error(409,"REVISION_CHANGED");
        if (access.testOpenControls()) requireTestRecoverable(row); else requireRecoverable(row,clock.millis());
        var target=access.candidate(row.view().equipmentId());
        if(!target.equals(DaikinControlAccess.identity(row.target()))) throw error(409,"REVISION_CHANGED");
        if(!access.testOpenControls() && (row.target().relationshipHash()==null || !row.target().relationshipHash().matches("[a-f0-9]{64}")))
            throw error(409,"LINKED_DEVICE_UNCONFIRMED");
        if (!access.testOpenControls()) store.requireSourceReady(target.sourceId(),clock.millis());
        return target;
    }

    static void requireRecoverable(DaikinControlStore.Row row, long now) {
        if(!row.view().isolated() || !Set.of(Status.UNKNOWN,Status.UNCONFIRMED,Status.CLOSED).contains(row.view().status())
                || row.sentAt()==null || row.view().deadlineAt()==null || now<row.view().deadlineAt())
            throw error(409,"RECOVERY_NOT_ELIGIBLE");
    }

    static void requireTestRecoverable(DaikinControlStore.Row row) {
        if (!row.view().isolated() || !Set.of(Status.UNKNOWN,Status.UNCONFIRMED,Status.CLOSED).contains(row.view().status()) || row.sentAt() == null)
            throw error(409, "RECOVERY_NOT_ELIGIBLE");
    }

    private String read(DaikinControlAccess.Target identity, DaikinControlStore.Row row, RecoveryBaseline expected) {
        var available=readers.stream().filter(r->r.supports(identity.sourceId())).toList();
        if(available.size()!=1) throw error(503,"PROVIDER_UNAVAILABLE");
        var old=row.target();
        var target=new DaikinControlAccess.Target(identity.equipmentId(),identity.buildingId(),identity.identityId(),
                identity.sourceId(),identity.siteId(),identity.controllerId(),identity.unitId(),identity.vendorId(),identity.pendingId(),
                identity.mappingVersion(),old.grantVersion(),old.airFlowConfirmed(),old.relationshipHash(),old.approvedActions(),old.evidence());
        var snapshot=available.getFirst().read(target);
        String settings=policy.settings(target,snapshot);
        try {
            var value=json.readTree(settings);
            if(!expected.onOff().equals(value.path("ON_OFF").asText()) || expected.temperature()!=Integer.parseInt(value.path("TEMPERATURE").asText())
                    || !("on".equals(expected.onOff())?"operating":"stopped").equals(value.path("unitStatus").asText()))
                throw error(409,"RECOVERY_BASELINE_MISMATCH");
        } catch(com.fasterxml.jackson.core.JsonProcessingException | NumberFormatException e) { throw error(409,"READBACK_INVALID"); }
        return settings;
    }

    private RecoveryProgress progress(String id, DaikinControlStore.RecoveryEvidence e, String reason) {
        try {
            var value=e.settings()==null?json.createObjectNode():json.readTree(e.settings());
            Integer temperature=value.hasNonNull("TEMPERATURE")?Integer.valueOf(value.path("TEMPERATURE").asText()):null;
            return new RecoveryProgress(id,value.path("ON_OFF").asText(null),temperature,value.path("MODE").asText(null),
                    value.path("FAN_SPEED").asText(null),value.path("AIR_FLOW").asText(null),value.path("unitStatus").asText(null),
                    e.count(),e.since(),e.lastReadAt(),e.canRecover(),e.canRecover()?e.proof():null,reason);
        } catch(com.fasterxml.jackson.core.JsonProcessingException | NumberFormatException ex) { throw new IllegalStateException("恢复证据无法解析",ex); }
    }
    private static void validate(RecoveryBaseline r) {
        if(r==null || r.onOff()==null || !Set.of("on","off").contains(r.onOff())
                || r.temperature()==null || r.temperature()<16 || r.temperature()>32) throw error(400,"INVALID_REQUEST");
    }
    private static String reason(BusinessException e) { return e.getErrorCode().replaceFirst("^HVAC_CONTROL_",""); }
}
