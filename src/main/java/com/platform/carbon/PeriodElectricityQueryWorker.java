package com.platform.carbon;

import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单通道限流恢复年日展示；任务和租约持久化，发现历史月份不依赖浏览器访问。 */
@Component
class PeriodElectricityQueryWorker implements AutoCloseable {
    private final CarbonDisplayQueryRepository repository;
    private final PeriodElectricityQueryService service;
    private final DynamicElectricityRepository results;
    private final CarbonDisplayTaskIdentity identity;
    private final CarbonDisplayQueryProperties properties;
    private final JdbcTemplate jdbc;
    private final DailyEvidenceVerification evidenceVerification;
    private final AtomicBoolean active=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("period-electricity-refresh-",0).factory());
    private long discoverAfter;
    private final java.util.ArrayDeque<ManualCursor> manualDiscovery=new java.util.ArrayDeque<>();
    private record ManualCursor(String building,long actor,long start,String fact) { }
    private record ManualWindow(String fact,long start,long end) { }
    PeriodElectricityQueryWorker(CarbonDisplayQueryRepository repository,PeriodElectricityQueryService service,
            DynamicElectricityRepository results,CarbonDisplayTaskIdentity identity,CarbonDisplayQueryProperties properties,
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,DailyEvidenceVerification evidenceVerification) {
        this.repository=repository;this.service=service;this.results=results;this.identity=identity;this.properties=properties;this.jdbc=jdbc;this.evidenceVerification=evidenceVerification;
    }
    @Scheduled(fixedDelayString="${period-electricity-query.scan-delay-ms:1000}",initialDelayString="${period-electricity-query.initial-delay-ms:15000}")
    public void scan() {
        if(!properties.isEnabled()||!active.compareAndSet(false,true))return;
        try{worker.submit(()->{try{refresh();}finally{active.set(false);}});}catch(RejectedExecutionException e){active.set(false);}
    }
    void refresh() {
        long now=System.currentTimeMillis();
        try {
            // 回填严格有界，不能一次把全年正文装入堆；未完成的期间保持准备中。
            try {
                results.backfillChartProjections(16);
                results.verifyChartProjectionIntegrity(2);
                evidenceVerification.verify(2);
            }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Display evidence recovery failed",e);}
            try {
                if(now>=discoverAfter){discoverAfter=now+60_000;discover();}
                discoverManualPage();
            }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Period source discovery deferred",e);}
            int attempted=0;
            long batchDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            for(var h:repository.duePeriods(now)) {
                String token=null;
                try {
                    var q=service.parse(h);String current=service.current(q);
                    if("READY".equals(h.state())&&current.equals(h.built())){service.prepareSummary(h);repository.checked(h,now);continue;}
                    if(!current.equals(h.source())){service.prepare(q,h.user());h=repository.head(h.key());}
                    token=repository.claim(h,now);if(token==null)continue;
                    service.rebuild(h,token,identity.currentRoles(h.user()));
                }catch(RuntimeException e) {
                    String code=e instanceof BusinessException b&&b.getErrorCode()!=null?b.getErrorCode():"PERIOD_DISPLAY_REFRESH_FAILED";
                    int status=e instanceof BusinessException b?b.getCode():503;
                    if(token!=null&&("DYNAMIC_PROJECTION_PREPARING".equals(code)||e instanceof org.springframework.dao.TransientDataAccessException))
                        repository.waitForInputs(h,token,System.currentTimeMillis());
                    else if(token!=null)repository.failed(h,token,code,status,System.currentTimeMillis());
                    else repository.defer(h,System.currentTimeMillis());
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Period display refresh failed kind={} building={} code={}",h.kind(),h.building(),code,e);
                }
                // 连续处理短任务，避免每个日期都等待下一次扫描；两秒后或四个任务后停止启动新任务。
                // 已开始任务仍受自身查询预算和租约约束，不增加并发，也不截断计算或校验。
                if(++attempted>=4||System.nanoTime()>=batchDeadline)return;
            }
        }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Period display recovery scan failed",e);}
    }
    void discover() {
        var discovered=new java.util.LinkedHashMap<String,Long>();
        var configuredActors=new java.util.HashMap<String,Long>();
        for(var target:properties.getPrewarmTargets()) {
            if(target.actorId()<=0)continue;
            var buildings=jdbc.queryForList("SELECT building_id FROM building WHERE building_code=? AND del_flag=0 ORDER BY building_id LIMIT 2",String.class,target.buildingCode());
            if(buildings.size()==1)configuredActors.put(buildings.getFirst(),target.actorId());
        }
        // DAY 与 HOUR 都是有效发现入口；仅复用发布者身份，绝不自动授予后台权限。
        jdbc.query("""
                SELECT building_id,day_date,created_by FROM biz_carbon_hourly_batch
                UNION SELECT building_id,day_date,created_by FROM biz_carbon_daily_static_batch
                """,rs->{
            String building=rs.getString(1),month=YearMonth.from(rs.getDate(2).toLocalDate()).toString();
            discovered.putIfAbsent(building+"|DAILY_TREND_DETAIL|"+month,rs.getLong(3));
        });
        jdbc.query("SELECT building_id,start_ms,requested_by FROM biz_dynamic_electricity_task",rs->{
            var month=YearMonth.from(Instant.ofEpochMilli(rs.getLong(2)).atZone(PeriodElectricityQueryService.ZONE));
            long actor=rs.getLong(3)>0?rs.getLong(3):configuredActors.getOrDefault(rs.getString(1),0L);
            discovered.putIfAbsent(rs.getString(1)+"|ELECTRICITY_YEAR|"+month.getYear(),actor);
            discovered.putIfAbsent(rs.getString(1)+"|ELECTRICITY_MONTH|"+month,actor);
        });
        // 批次头只负责提供合法发布者。明细范围分批从覆盖索引发现，禁止周期性连接整张正文表。
        if(manualDiscovery.isEmpty())jdbc.query("""
                SELECT building_id,MIN(reviewed_by) FROM biz_energy_manual_batch
                WHERE status='APPROVED' GROUP BY building_id
                """,rs->{
            String building=rs.getString(1);long actor=configuredActors.getOrDefault(building,rs.getLong(2));
            if(actor>0)manualDiscovery.add(new ManualCursor(building,actor,Long.MIN_VALUE,""));
        });
        discovered.forEach((key,actor)->{var parts=key.split("\\|");prepare(parts[0],parts[1],parts[2],actor);});
    }
    void discoverManualPage() {
        var cursor=manualDiscovery.peekFirst();if(cursor==null)return;
        var page=jdbc.query("""
                SELECT /*+ MAX_EXECUTION_TIME(3000) INDEX(r idx_energy_manual_cursor) */
                       r.fact_id,r.start_inclusive,r.end_exclusive
                FROM biz_energy_manual_reading r WHERE r.building_id=?
                  AND (r.start_inclusive>? OR (r.start_inclusive=? AND r.fact_id>?))
                ORDER BY r.start_inclusive,r.fact_id LIMIT 1000
                """,(rs,n)->new ManualWindow(rs.getString(1),rs.getLong(2),rs.getLong(3)),
                cursor.building(),cursor.start(),cursor.start(),cursor.fact());
        var months=new java.util.TreeSet<YearMonth>();
        for(var window:page) {
            var first=YearMonth.from(Instant.ofEpochMilli(window.start()).atZone(PeriodElectricityQueryService.ZONE));
            var last=YearMonth.from(Instant.ofEpochMilli(window.end()-1).atZone(PeriodElectricityQueryService.ZONE));
            for(var month=first;!month.isAfter(last);month=month.plusMonths(1))months.add(month);
        }
        // 索引中的待审核事实只扩大候选月份；正式日计算仍检查批准状态，不会采用这些事实或补造电量。
        for(var month:months)prepare(cursor.building(),"DAILY_TREND_DETAIL",month.toString(),cursor.actor());
        manualDiscovery.removeFirst();
        if(page.size()==1000){var last=page.getLast();manualDiscovery.addFirst(new ManualCursor(cursor.building(),cursor.actor(),last.start(),last.fact()));}
    }
    private void prepare(String building,String kind,String period,long actor) {
        if(actor<=0)return;
        var q=PeriodElectricityQueryService.validate(building,kind,period);
        // 已有任务保留最近合法请求人的身份，发现扫描不能把已撤权的老发布者反复写回。
        if(repository.head(q.key())==null)service.prepare(q,actor);
    }
    @jakarta.annotation.PreDestroy @Override public void close(){worker.shutdownNow();}
}
