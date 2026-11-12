package com.platform.carbon;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.platform.carbon.HourlyMonitoringCalculator.ZONE;

/** 当天与历史各占一个执行通道，避免历史补齐挡住实时更新；持久租约负责重启恢复。 */
@Component
class HourlyMonitoringWorker implements AutoCloseable {
    private final HourlyMonitoringRepository repository;
    private final HourlyMonitoringCalculator calculator;
    private final CarbonDisplayTaskIdentity identities;
    private final CarbonDisplayQueryProperties properties;
    private final AtomicBoolean currentActive=new AtomicBoolean(),historyActive=new AtomicBoolean();
    private final ExecutorService executor=Executors.newFixedThreadPool(2,Thread.ofPlatform().daemon().name("hourly-monitoring-",0).factory());
    private long discoverAfter;
    // 每个通道由对应active保护；游标只决定扫描位置，重启仍由持久租约恢复实际任务。
    private HourlyMonitoringRepository.Target currentCursor,historyCursor;
    HourlyMonitoringWorker(HourlyMonitoringRepository repository,HourlyMonitoringCalculator calculator,
            CarbonDisplayTaskIdentity identities,CarbonDisplayQueryProperties properties) {
        this.repository=repository;this.calculator=calculator;this.identities=identities;this.properties=properties;
    }
    @Scheduled(fixedDelayString="${hourly-monitoring.scan-delay-ms:500}",initialDelayString="${hourly-monitoring.initial-delay-ms:15000}")
    public void scan() {
        if(!properties.isEnabled())return;
        for(boolean currentDay:List.of(true,false)) {
            var active=currentDay?currentActive:historyActive;
            if(!active.compareAndSet(false,true))continue;
            try{executor.submit(()->{try{refresh(currentDay);}finally{active.set(false);}});}catch(RejectedExecutionException e){active.set(false);}
        }
    }
    void refresh(boolean currentDay) {
        try {
            long now=System.currentTimeMillis();
            if(currentDay&&now>=discoverAfter) {
                discoverAfter=now+10_000;var today=LocalDate.now(ZONE);
                for(var target:properties.getPrewarmTargets())if(target.actorId()>0) {
                    var buildings=repository.configuredBuildings(target.buildingCode());
                    if(buildings.size()==1) {
                        repository.registerConfigured(buildings.getFirst(),today,target.actorId());
                        repository.registerConfigured(buildings.getFirst(),today.minusDays(1),target.actorId());
                    }
                }
            }
            long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            var page=repository.due(now,currentDay,currentDay?currentCursor:historyCursor);
            if(page.isEmpty()){cursor(currentDay,null);return;}
            int visited=0;
            for(var target:page) {
                if(visited>0&&System.nanoTime()>=stop)break;
                cursor(currentDay,target);visited++;
                var observed=Instant.now();
                var snapshot=repository.snapshot(target.building(),target.date(),observed,calculator.policy());
                // 高频检查只读版本；空闲目标不领取/释放租约，避免持续提交挤占原始写入与年日恢复。
                if(candidates(target,snapshot,observed).isEmpty())continue;
                String token=UUID.randomUUID().toString();if(!repository.claim(target,token,System.currentTimeMillis()))continue;
                long delay=1000;
                try{refresh(target,token,snapshot,observed);}catch(RuntimeException e){
                    delay=10_000;LoggerFactory.getLogger(getClass()).warn("Hourly monitoring refresh failed building={} date={} type={}",target.building(),target.date(),e.getClass().getSimpleName(),e);
                }finally{repository.release(target,token,delay);}
                if(System.nanoTime()>=stop)break;
            }
            if(visited==page.size()&&page.size()<8)cursor(currentDay,null);
        }catch(RuntimeException e){LoggerFactory.getLogger(getClass()).warn("Hourly monitoring scan failed",e);}
    }
    void refresh(HourlyMonitoringRepository.Target target,String token) {
        identities.currentRoles(target.actor());var now=Instant.now();
        var snapshot=repository.snapshot(target.building(),target.date(),now,calculator.policy());
        refresh(target,token,snapshot,now);
    }
    private void cursor(boolean currentDay,HourlyMonitoringRepository.Target value) {
        if(currentDay)currentCursor=value;else historyCursor=value;
    }
    private List<Instant> candidates(HourlyMonitoringRepository.Target target,HourlyMonitoringRepository.Snapshot snapshot,Instant now) {
        var start=target.date().atStartOfDay(ZONE).toInstant();
        var candidates=new ArrayList<Instant>();
        for(int hour=0;hour<24;hour++) {
            var from=start.plusSeconds(hour*3600L);if(!from.isBefore(now))continue;
            var row=snapshot.results().get(from.toEpochMilli());
            if(row==null||!row.version().equals(snapshot.versions().get(from.toEpochMilli())))candidates.add(from);
            else if(row.updatedAt()+10_000<=now.toEpochMilli()) {
                try {
                    var point=repository.decode(row);
                    if(List.of(point.electricityStatus(),point.staticStatus(),point.dynamicStatus()).contains("FAILED"))candidates.add(from);
                }catch(com.platform.framework.exception.BusinessException e){candidates.add(from);}
            }
        }
        // 新小时及当前累计优先；已完成小时不会因另一个小时上报而进入候选。
        candidates.sort(Comparator.reverseOrder());
        return candidates;
    }
    private void refresh(HourlyMonitoringRepository.Target target,String token,HourlyMonitoringRepository.Snapshot snapshot,Instant now) {
        var roles=identities.currentRoles(target.actor());
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);int attempted=0;
        for(var from:candidates(target,snapshot,now)) {
            HourlyMonitoringCalculator.Calculated result;
            try {
                String sourceKind=repository.sourceKind(target.building(),from,from.plusSeconds(3600).isAfter(now)?now:from.plusSeconds(3600));
                result=calculator.calculate(target.actor(),roles,target.building(),from,sourceKind,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            }catch(RuntimeException e) {
                if(e instanceof com.platform.framework.exception.BusinessException business&&(business.getCode()==401||business.getCode()==403))throw e;
                // 暂时失败保存为失败摘要并重试；不能永久留在准备中，也不伪造零电量。
                var evidence=CarbonEvidence.object().put("failureType",e.getClass().getSimpleName());
                result=new HourlyMonitoringCalculator.Calculated(HourlyMonitoringContracts.empty(from.atZone(ZONE).getHour(),from,"FAILED","HOURLY_MONITOR_RETRY"),evidence);
                LoggerFactory.getLogger(getClass()).warn("Hourly calculation failed building={} hour={}",target.building(),from,e);
            }
            calculator.authorize(target.actor(),identities.currentRoles(target.actor()),target.building());
            repository.publish(target,token,from.toEpochMilli(),snapshot.versions().get(from.toEpochMilli()),calculator.policy(),result);
            if(++attempted>=4||System.nanoTime()>=stop)break;
        }
    }
    @jakarta.annotation.PreDestroy @Override public void close(){executor.shutdownNow();}
}
