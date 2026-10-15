package com.platform.iot.daikin.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.control.DaikinControlAccess;
import com.platform.iot.daikin.control.DaikinControlGrantReader;
import com.platform.iot.daikin.mapping.DaikinDevicePageDecoder;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.monitoring.state.DaikinMonitoringStateService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 按需只读状态核验：同进程同设备合并，两个worker共享既有厂家会话限流；不提供控制发送能力。 */
@Service
public class DaikinFastMonitoringService {
    static final long INTERVAL = 5_000;
    static final long WATCH_TTL = 15_000;
    static final long FOLLOW_WINDOW = 180_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final DaikinMonitoringProperties properties;
    private final DaikinControlAccess access;
    private final List<DaikinControlGrantReader> readers;
    private final DaikinMonitoringTargets targets;
    private final DaikinMonitoringStateService state;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<WatchKey, Watch> watches = new ConcurrentHashMap<>();
    private final Map<String, Poll> polls = new ConcurrentHashMap<>();
    private final AtomicInteger running = new AtomicInteger();
    private final ExecutorService workers = Executors.newFixedThreadPool(2,
            task -> Thread.ofPlatform().daemon().name("daikin-fast-monitoring").unstarted(task));
    private final DaikinDevicePageDecoder decoder = new DaikinDevicePageDecoder(
            DaikinDevicePageDecoder.FieldPolicy.unconfirmed());

    @Autowired
    public DaikinFastMonitoringService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            TransactionTemplate transaction, DaikinMonitoringProperties properties, DaikinControlAccess access,
            List<DaikinControlGrantReader> readers, DaikinMonitoringTargets targets,
            DaikinMonitoringStateService state, ObjectMapper mapper) {
        this(jdbc, transaction, properties, access, readers, targets, state, mapper, Clock.systemUTC());
    }

    DaikinFastMonitoringService(JdbcTemplate jdbc, TransactionTemplate transaction,
            DaikinMonitoringProperties properties, DaikinControlAccess access, List<DaikinControlGrantReader> readers,
            DaikinMonitoringTargets targets, DaikinMonitoringStateService state, ObjectMapper mapper, Clock clock) {
        this.jdbc=jdbc; this.transaction=transaction; this.properties=properties; this.access=access;
        this.readers=List.copyOf(readers); this.targets=targets; this.state=state; this.mapper=mapper; this.clock=clock;
    }

    /** 心跳只登记关注意图，不把HTTP应答当成厂家读取成功；没有读取客户端时退回分钟监测。 */
    public synchronized WatchStatus watch(long user, String equipment, String watchId) {
        requireWatchId(watchId);
        access.requireRead(user, equipment);
        if (!properties.isEnabled() || readers.isEmpty()) return new WatchStatus(false, INTERVAL, 0);
        DaikinControlAccess.Target target;
        try { target=access.candidate(equipment); }
        catch (com.platform.framework.exception.BusinessException unavailable) { return new WatchStatus(false, INTERVAL, 0); }
        if (reader(target) == null) return new WatchStatus(false, INTERVAL, 0);
        long now=clock.millis();
        watches.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        WatchKey key=new WatchKey(user,equipment,watchId);
        if (!watches.containsKey(key) && (watches.size() >= 512
                || watches.keySet().stream().filter(k -> k.user()==user).count() >= 32))
            throw new com.platform.framework.exception.BusinessException(429,"DAIKIN_MONITORING_WATCH_LIMIT","快速状态关注数量已达上限");
        watches.put(key,new Watch(user,target,now+WATCH_TTL,key));
        return new WatchStatus(true,INTERVAL,now+WATCH_TTL);
    }

    public void unwatch(long user, String equipment, String watchId) {
        requireWatchId(watchId);
        access.requireRead(user,equipment);
        watches.remove(new WatchKey(user,equipment,watchId));
    }

    @Scheduled(fixedDelayString = "${daikin.monitoring.fast-schedule-delay-ms:1000}")
    public synchronized void schedule() {
        if (!properties.isEnabled() || readers.isEmpty()) return;
        Map<String,List<Watch>> wanted=desired();
        polls.entrySet().removeIf(entry -> !wanted.containsKey(entry.getKey()) && !entry.getValue().inFlight);
        var ordered=new ArrayList<>(wanted.entrySet());
        wanted.keySet().forEach(key -> polls.computeIfAbsent(key, ignored -> new Poll()));
        ordered.sort(Comparator.comparingLong(entry -> polls.get(entry.getKey()).nextAt));
        for (var entry : ordered) {
            if (running.get() >= 2) break;
            Poll poll=polls.get(entry.getKey());
            synchronized (poll) {
                if (poll.inFlight || poll.nextAt > clock.millis()) continue;
                poll.inFlight=true;
            }
            running.incrementAndGet();
            try { workers.execute(() -> {
                try { refresh(entry.getValue()); poll.failures=0; poll.nextAt=clock.millis()+INTERVAL; }
                catch (RuntimeException failure) {
                    poll.failures=Math.min(poll.failures+1,4);
                    poll.nextAt=clock.millis()+Math.min(60_000,INTERVAL*(1L<<poll.failures));
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("空调快速状态读取失败，退避后重试: equipmentId={}",entry.getKey());
                } finally { poll.inFlight=false; running.decrementAndGet(); }
            }); }
            catch (java.util.concurrent.RejectedExecutionException stopped) { poll.inFlight=false; running.decrementAndGet(); }
        }
    }

    /** 已完成操作只在原完成时间后的三分钟内跟踪；重启不延长窗口，不重新发送任何命令。 */
    Map<String,List<Watch>> desired() {
        long now=clock.millis();
        watches.entrySet().removeIf(entry -> entry.getValue().expiresAt()<=now);
        Map<String,List<Watch>> result=new HashMap<>();
        watches.values().forEach(w -> result.computeIfAbsent(w.target().equipmentId(), key -> new ArrayList<>()).add(w));
        jdbc.query("""
                SELECT operator_id,target_json,updated_at FROM biz_daikin_control_operation
                WHERE status='CLOUD_VERIFIED' AND sent_at IS NOT NULL AND updated_at>? ORDER BY updated_at DESC LIMIT 100
                """, rs -> {
            try {
                var saved=mapper.readValue(rs.getString("target_json"),DaikinControlAccess.Target.class);
                if (saved==null || saved.equipmentId()==null || saved.equipmentId().isBlank()) return;
                var target=identity(saved);
                result.computeIfAbsent(target.equipmentId(), key -> new ArrayList<>())
                        .add(new Watch(rs.getLong("operator_id"),target,rs.getLong("updated_at")+FOLLOW_WINDOW,null));
            } catch (java.io.IOException malformed) { /* 旧身份快照不可解析时不猜测设备或延长跟踪。 */ }
        },now-FOLLOW_WINDOW);
        return result;
    }

    void refresh(List<Watch> owners) {
        if (!properties.isEnabled()) return;
        Watch selected=validOwner(owners);
        if (selected==null) return;
        var target=selected.target();
        // 在途控制已有专属回读，监测让出预算；完成后由关注或三分钟跟踪继续取数。
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_daikin_control_operation o
                WHERE o.next_check_at IS NOT NULL AND o.deadline_at>?
                  AND (EXISTS (SELECT 1 FROM biz_daikin_control_slot s WHERE s.operation_id=o.operation_id AND s.physical_key=?)
                    OR EXISTS (SELECT 1 FROM biz_daikin_control_controller_slot s WHERE s.operation_id=o.operation_id AND s.controller_key=?))
                """,Integer.class,clock.millis(),target.physicalKey(),target.controllerKey())>0) return;
        DaikinControlGrantReader reader=reader(target);
        if (reader==null) return;
        long requestedAt=clock.millis();
        var snapshot=reader.read(target);
        var observation=decoder.decodeStateSnapshot(new DaikinDeviceKey(target.sourceId(),target.siteId(),
                target.controllerId(),DaikinDeviceKey.Kind.INDOOR,target.unitId()),target.vendorId(),
                snapshot.unit(),snapshot.controller(),Instant.ofEpochMilli(requestedAt));
        var trusted=targets.find(target.pendingId()).filter(t -> t.equipmentId().equals(target.equipmentId())
                && t.identityId().equals(target.identityId()) && t.buildingId().equals(target.buildingId())
                && t.mappingVersion()==target.mappingVersion() && t.key().equals(observation.key())).orElse(null);
        if (trusted==null) return;
        transaction.executeWithoutResult(status -> {
            // 与常规采集使用相同锁顺序，网络IO在事务外；提交前再次复核权限、关注有效期及实时归属。
            jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",String.class,target.sourceId());
            Watch current=validOwner(owners);
            if (!properties.isEnabled() || current==null || !current.target().equals(target)
                    || !targets.find(target.pendingId()).filter(trusted::equals).isPresent()) return;
            var stateTarget=DaikinObservationInbox.stateTarget(trusted);
            state.registerTarget(stateTarget,requestedAt);
            state.observeFast(stateTarget,observation);
        });
    }

    private Watch validOwner(List<Watch> owners) {
        for (Watch owner : owners) {
            if (owner.key()!=null) {
                Watch current=watches.get(owner.key());
                if (current==null || !current.target().equals(owner.target())) continue;
                owner=current;
            }
            if (owner.expiresAt()<=clock.millis()) continue;
            try {
                access.requireRead(owner.user(),owner.target().equipmentId());
                if (owner.target().equals(access.candidate(owner.target().equipmentId()))) return owner;
            } catch (com.platform.framework.exception.BusinessException denied) { /* 撤权、停用或迁移立即终止该关注。 */ }
        }
        return null;
    }

    private DaikinControlGrantReader reader(DaikinControlAccess.Target target) {
        return readers.stream().filter(value -> value.supports(target.sourceId())).findFirst().orElse(null);
    }

    private static DaikinControlAccess.Target identity(DaikinControlAccess.Target t) {
        return new DaikinControlAccess.Target(t.equipmentId(),t.buildingId(),t.identityId(),t.sourceId(),t.siteId(),
                t.controllerId(),t.unitId(),t.vendorId(),t.pendingId(),t.mappingVersion(),0,false,null,null,null);
    }

    private static void requireWatchId(String id) {
        if (id==null || !id.matches("[A-Za-z0-9_-]{8,100}"))
            throw new com.platform.framework.exception.BusinessException(400,"DAIKIN_MONITORING_INVALID_WATCH","关注标识无效");
    }

    @PreDestroy
    void stop() { workers.shutdownNow(); }

    public record WatchRequest(String watchId) { }
    public record WatchStatus(boolean enabled,long intervalMillis,long expiresAt) { }
    record WatchKey(long user,String equipment,String watchId) { }
    record Watch(long user,DaikinControlAccess.Target target,long expiresAt,WatchKey key) { }
    private static final class Poll { volatile long nextAt; volatile boolean inFlight; int failures; }
}
