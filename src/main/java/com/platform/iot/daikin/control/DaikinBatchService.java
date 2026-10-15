package com.platform.iot.daikin.control;

import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static com.platform.iot.daikin.control.DaikinBatchContracts.*;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static com.platform.iot.daikin.control.DaikinControlErrors.error;

/** 浏览器仅提交共同动作；后台有界执行，重启/租约恢复只核对既有操作，不重复外部副作用。 */
@Service
public class DaikinBatchService {
    private final DaikinBatchStore store;
    private final DaikinControlService controls;
    private final DaikinControlAccess access;
    private final DaikinControlPolicy policy;
    private final DaikinControlProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("hvac-batch-",0).factory());
    private final ExecutorService readers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("hvac-batch-preview-",0).factory());
    private final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
    private final Semaphore previewPermit = new Semaphore(1);

    @Autowired
    public DaikinBatchService(DaikinBatchStore store, DaikinControlService controls, DaikinControlAccess access,
                               DaikinControlPolicy policy, DaikinControlProperties properties, @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this(store,controls,access,policy,properties,jdbc,Clock.systemUTC());
    }
    DaikinBatchService(DaikinBatchStore store, DaikinControlService controls, DaikinControlAccess access,
                       DaikinControlPolicy policy, DaikinControlProperties properties, JdbcTemplate jdbc, Clock clock) {
        this.store=store; this.controls=controls; this.access=access; this.policy=policy;
        this.properties=properties; this.jdbc=jdbc; this.clock=clock;
    }

    public Preview preview(long user,PreviewRequest request) {
        if(request==null || request.equipmentIds()==null || request.equipmentIds().isEmpty() || request.equipmentIds().size()>100
                || request.equipmentIds().stream().anyMatch(s->s==null || !s.matches("[A-Za-z0-9_-]{1,32}"))
                || new HashSet<>(request.equipmentIds()).size()!=request.equipmentIds().size()) throw error(400,"INVALID_REQUEST");
        // 先检查整组授权，不能以逐台错误响应泄露其他建筑设备的能力或存在性。
        for(String equipment:request.equipmentIds()) access.requireControl(user,equipment);
        if(!previewPermit.tryAcquire()) throw error(429,"PREVIEW_BUSY");
        try {
            var futures=request.equipmentIds().stream().sorted().map(e->CompletableFuture.supplyAsync(()->inspect(user,e),readers)).toList();
            List<PreparedItem> items=futures.stream().map(CompletableFuture::join).toList();
            // 分页式能力读取可能跨越撤权时刻；返回整组结果之前再次复核全部范围。
            for(String equipment:request.equipmentIds()) access.requireControl(user,equipment);
            long expires=clock.millis()+120_000; String id=DaikinBatchStore.id();
            store.savePreview(new SavedPreview(id,user,expires,items));
            return new Preview(id,expires,items.stream().map(PreparedItem::view).toList());
        } finally { previewPermit.release(); }
    }
    private PreparedItem inspect(long user,String equipment) {
        try {
            var inspection=controls.inspectBatch(user,equipment);
            var target=inspection.target(); var snapshot=inspection.snapshot();
            String relationship=policy.relationshipHash(snapshot);
            boolean known=relationship!=null && snapshot.unit().path("isGroupSlave").isBoolean()
                    && Set.of("Master","Slave").contains(snapshot.unit().path("masterSlaveFlag").asText());
            var impacted=new ArrayList<String>();
            var impactedTargets=new ArrayList<DaikinControlAccess.Target>();
            var impactedLabels=new LinkedHashMap<String,String>();
            if(known) {
                for(var id:snapshot.unit().path("masterSlaveIds")) {
                    if(id.asText().equals(target.vendorId())) continue;
                    var related=jdbc.queryForList("""
                            SELECT e.equip_id FROM biz_daikin_directory d JOIN biz_pending_device p ON p.pending_id=d.pending_id
                            JOIN biz_device_identity i ON i.identity_id=p.bound_identity_id JOIN biz_equipment e ON e.equip_id=i.equip_id
                            WHERE d.source_id=? AND d.controller_id=? AND d.equipment_id=? AND d.missing=0 AND e.del_flag=0
                            AND p.status='BOUND' AND i.status=1 AND e.building_id=i.building_id
                            """,String.class,target.sourceId(),target.controllerId(),id.asText());
                    if(related.size()!=1) { known=false; break; }
                    try { access.requireControl(user,related.getFirst()); impactedTargets.add(access.target(related.getFirst())); }
                    catch(BusinessException e) { known=false; break; }
                    impacted.add(related.getFirst());
                    String relatedId=related.getFirst();
                    var labels=jdbc.queryForList("SELECT equip_name,equip_code FROM biz_equipment WHERE equip_id=? AND del_flag=0",relatedId);
                    String name=Objects.toString(labels.getFirst().get("equip_name"),"");
                    String code=Objects.toString(labels.getFirst().get("equip_code"),"");
                    impactedLabels.put(relatedId,name.isBlank()?(code.isBlank()?relatedId:code):name);
                }
            }
            if(snapshot.unit().path("isGroupSlave").asBoolean() && impacted.isEmpty()) known=false;
            boolean finalKnown=known;
            boolean groupSlave=snapshot.unit().path("isGroupSlave").asBoolean();
            var actions=inspection.options().actions().stream().map(a->(Set.of(Action.MODE,Action.ON_OFF).contains(a.action()) && !finalKnown
                    || groupSlave && !Set.of(Action.MODE,Action.ON_OFF).contains(a.action()))
                    ?new ActionOption(a.action(),false,"LINKED_SCOPE_UNKNOWN",List.of(),null,null):a).toList();
            return new PreparedItem(new PreviewItem(equipment,inspection.options().revision(),null,actions,
                    known?impacted.stream().distinct().sorted().toList():List.of(),known,known?Map.copyOf(impactedLabels):Map.of()),target,relationship,known?List.copyOf(impactedTargets):List.of());
        } catch(RuntimeException e) {
            String reason=e instanceof BusinessException business?DaikinBatchStore.reason(business):"PREFLIGHT_UNAVAILABLE";
            return new PreparedItem(new PreviewItem(equipment,null,reason,Arrays.stream(Action.values()).map(a->new ActionOption(a,false,reason,List.of(),null,null)).toList(),List.of(),false,Map.of()),null,null,List.of());
        }
    }

    public Batch submit(long user,SubmitBatch request,String trace) {
        validate(request); access.requireAccount(user);
        String hash=DaikinControlAccess.hash(store.encode(request));
        String old=store.existing(user,request.idempotencyKey(),hash);
        if(old!=null) return get(user,old);
        if(!properties.isEnabled()) throw error(503,"DISABLED");
        var preview=store.preview(user,request.previewId(),clock.millis());
        if(Set.of(Action.MODE,Action.ON_OFF).contains(request.action())) {
            var selected=preview.items().stream().map(p->p.view().equipmentId()).collect(java.util.stream.Collectors.toSet());
            boolean affectsOthers=preview.items().stream().filter(p->supports(p,request)).anyMatch(p->p.view().impactedEquipmentIds().stream().anyMatch(e->!selected.contains(e)));
            if(affectsOthers && !request.confirmLinkedImpact()) throw error(409,"LINKED_CONFIRMATION_REQUIRED");
        }
        String id=store.create(user,request,hash,preview,trace,clock,access,policy,
                access.testOpenControls() && request.action()==Action.ON_OFF);
        return get(user,id);
    }
    public Batch get(long user,String id) { return store.get(user,id,access,clock.millis()); }
    public PageResponse<Batch> history(long user,int page,int size) { return store.history(user,page,size,access,clock.millis()); }
    public Batch cancel(long user,String id) { return store.cancel(user,id,access,clock); }

    private boolean supports(PreparedItem item,SubmitBatch request) {
        if(item.target()==null || item.view().reasonCode()!=null) return false;
        try { policy.validate(request.action(),request.value(),item.view().actions()); return true; }
        catch(BusinessException e) { return false; }
    }

    @Scheduled(fixedDelayString="${daikin.control.batch-delay-ms:1000}")
    public void tick() {
        if(!properties.isEnabled()) return;
        try {
            store.synchronize(clock);
            for(var candidate:store.candidates()) {
                if(active.get()>=4) break;
                var work=store.claim(candidate,clock,4);
                if(work==null) continue;
                active.incrementAndGet();
                workers.execute(()->{try { dispatch(work); } finally { active.decrementAndGet(); }});
            }
        } catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("批量控制扫描未完成，持久化队列保留",e); }
    }
    void dispatch(DaikinBatchStore.Work work) {
        try {
            // 排队期间撤权、归属、许可或关系变化均禁止发送；派发只使用预检保存的原设备和值。
            access.requireControl(work.user(),work.item().equipmentId());
            if(!work.target().equals(access.target(work.item().equipmentId()))) throw error(409,"REVISION_CHANGED");
            var operation=controls.submitBatch(work.user(),work.item().equipmentId(),new Submit(work.action(),work.value(),work.operationKey(),work.revision()),
                    work.trace(),work.relationshipHash(),()->{store.requireDispatch(work,clock); requireTargets(work);},work.item().itemId());
            store.dispatched(work,operation,clock);
        } catch(RuntimeException e) {
            store.failDispatch(work,e instanceof BusinessException business?DaikinBatchStore.reason(business):"DISPATCH_RESULT_UNAVAILABLE",clock);
        }
    }
    private void requireTargets(DaikinBatchStore.Work work) {
        access.requireControl(work.user(),work.item().equipmentId());
        if(!work.target().equals(access.target(work.item().equipmentId()))) throw error(409,"REVISION_CHANGED");
        if(Set.of(Action.MODE,Action.ON_OFF).contains(work.action())) for(var target:work.impactedTargets()) {
            access.requireControl(work.user(),target.equipmentId());
            if(!target.equals(access.target(target.equipmentId()))) throw error(409,"LINKED_SCOPE_CHANGED");
        }
    }
    private static void validate(SubmitBatch r) {
        if(r==null || r.previewId()==null || !r.previewId().matches("[a-f0-9]{32}") || r.action()==null
                || r.value()==null || r.value().isBlank() || r.value().length()>40
                || r.idempotencyKey()==null || !r.idempotencyKey().matches("[A-Za-z0-9_-]{8,100}")) throw error(400,"INVALID_REQUEST");
    }
    @PreDestroy public void stop() { workers.shutdown(); readers.shutdown(); }
}
