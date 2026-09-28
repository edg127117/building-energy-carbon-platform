package com.platform.weather;

import com.platform.framework.exception.BusinessException;
import com.platform.weather.energy.DailyElectricityAdapter;
import com.platform.weather.source.*;
import com.platform.weather.source.WeatherSourceModels.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.platform.weather.WeatherModels.*;

/** 有界后台执行；数据库通道租约将每来源并发限制扩展到多实例。 */
@Component
public class WeatherWorker {
    private static final Logger log=LoggerFactory.getLogger(WeatherWorker.class);
    private final WeatherProperties config;
    private final WeatherRepository repo;
    private final WeatherService service;
    private final WeatherSourceClient source;
    private final WeatherTimeseries timeseries;
    private final DailyElectricityAdapter energy;
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,AtomicBoolean> busy=Map.of("OPEN_METEO",new AtomicBoolean(),"CHINA_WEATHER",new AtomicBoolean(),"ENERGY",new AtomicBoolean());
    public WeatherWorker(WeatherProperties config,WeatherRepository repo,WeatherService service,WeatherSourceClient source,WeatherTimeseries timeseries,DailyElectricityAdapter energy) {
        this.config=config;this.repo=repo;this.service=service;this.source=source;this.timeseries=timeseries;this.energy=energy;
    }
    @Scheduled(fixedDelay=2000,initialDelay=10000)
    public void tick() {
        if(!config.isEnabled())return;
        for(String lane:busy.keySet()) {
            if(!enabled(lane)||!busy.get(lane).compareAndSet(false,true))continue;
            workers.submit(()->{
                try { Job job=repo.claim(lane,System.currentTimeMillis());if(job!=null)execute(job); }
                catch(RuntimeException ex) { log.warn("Weather worker failed lane={} type={}",lane,ex.getClass().getSimpleName()); }
                finally { busy.get(lane).set(false); }
            });
        }
    }
    boolean enabled(String lane) { return switch(lane) { case "ENERGY" -> config.isEnergyEnabled();case "OPEN_METEO" -> config.isOpenMeteoEnabled();default -> config.isChinaWeatherEnabled(); }; }
    void execute(Job job) {
        AtomicBoolean leaseLost=new AtomicBoolean();
        var renewal=heartbeat.scheduleAtFixedRate(()->{
            try { if(!repo.renew(job,System.currentTimeMillis()))leaseLost.set(true); }
            catch(RuntimeException ex) { leaseLost.set(true); }
        },30,30,TimeUnit.SECONDS);
        String stage="AUTHORIZE";
        try {
            service.authorizeJob(job);
            if(job.source()!=null && job.product()!=Product.HISTORY_HOURLY && job.product()!=Product.HISTORY_DAILY
                    && !job.start().equals(LocalDate.now(ZONE))) {
                repo.finish(job,"CANCELLED","FORECAST_WINDOW_EXPIRED");return;
            }
            if(job.kind().equals("ENERGY")) {
                stage="ENERGY_CALCULATE";
                var values=energy.calculate(job.actorId(),service.liveRoles(job.actorId()),job.buildingId(),job.systemId(),job.pointId(),job.start(),job.end());
                Map<LocalDate,DailyElectricityAdapter.DailyResult> results=new LinkedHashMap<>();
                values.forEach(v->results.put(v.day(),v));
                if(leaseLost.get())return;
                service.authorizeJob(job);
                var current=energy.validateObject(job.actorId(),service.liveRoles(job.actorId()),job.buildingId(),job.systemId(),job.pointId());
                if(!current.valid()||values.stream().anyMatch(v->v.energyKwh()!=null&&!Objects.equals(v.relationVersion(),current.relationVersion())))
                    throw new BusinessException(409,"RELATION_CHANGED_DURING_CALCULATION","计量关系变化，请重新计算");
                stage="ENERGY_SAVE";
                repo.saveEnergy(job,results);
            } else {
                stage="DATASET_LOAD";
                Dataset data=repo.dataset(job.datasetId());
                if(data==null) {
                    Binding b=repo.binding(job.bindingId());
                    if(b==null||!b.enabled())throw new BusinessException(409,"LOCATION_DISABLED","位置已停用");
                    stage="SOURCE_FETCH";
                    FetchResult result=source.fetch(new Request(job.source(),job.product(),b.latitude(),b.longitude(),b.cityCode(),job.start(),job.end()));
                    stage="SOURCE_VALIDATE";
                    validateResult(job,result);
                    if(leaseLost.get())return;
                    stage="DATASET_PREPARE";
                    data=repo.prepare(job,result);
                }
                stage="TIMESERIES_WRITE_VERIFY";
                timeseries.write(data);
                if(leaseLost.get())return;
                stage="DATASET_PUBLISH";
                service.authorizeJob(job);repo.publish(job,data);
            }
        } catch(WeatherSourceException ex) {
            completeFailure(job,ex.isRetryable(),ex.getCode().name(),ex.getRetryAfterMillis()==null?0:ex.getRetryAfterMillis());
        } catch(BusinessException ex) {
            completeFailure(job,false,ex.getErrorCode()==null?"AUTHORIZATION_OR_CONFIGURATION_CHANGED":ex.getErrorCode(),0);
        } catch(RuntimeException ex) {
            diagnose(job,stage,ex);
            completeFailure(job,true,"STORAGE_OR_EXECUTION_FAILED",0);
        } finally { renewal.cancel(false); }
    }
    /** 保留定位所需的结构化信息；驱动消息可能含连接凭据、SQL 或载荷，不能原样写入日志。 */
    private void diagnose(Job job,String stage,RuntimeException error) {
        Throwable root=error;
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        java.sql.SQLException sql=null;
        for(Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause()) {
            root=cause;
            if(cause instanceof java.sql.SQLException s)sql=s;
        }
        Set<String> safeCodes=Set.of("WEATHER_WRITE_VERIFICATION_FAILED","WEATHER_SERIALIZATION_FAILED",
                "WEATHER_PAYLOAD_INVALID","WEATHER_PAYLOAD_TOO_LARGE","WEATHER_SAMPLE_TOO_LARGE",
                "DUPLICATE_WEATHER_TIME","SOURCE_IDENTITY_MISMATCH","LEASE_LOST",
                "INVALID_SQL_IDENTIFIER","INVALID_DATASET_ID");
        String code=safeCodes.contains(error.getMessage()==null?"":error.getMessage())?error.getMessage():"UNCLASSIFIED";
        String sqlState=sql==null?null:sql.getSQLState();
        if(sqlState!=null&&!sqlState.matches("[A-Z0-9]{5}"))sqlState="UNKNOWN";
        String frame=root.getStackTrace().length==0?"UNKNOWN":root.getStackTrace()[0].toString();
        log.warn("Weather execution failed id={} source={} product={} attempt={} stage={} type={} rootType={} code={} sqlState={} vendorCode={} frame={}",
                job.id(),job.source(),job.product(),job.attempts(),stage,error.getClass().getSimpleName(),root.getClass().getSimpleName(),code,
                sqlState,sql==null?null:sql.getErrorCode(),frame);
    }
    private void completeFailure(Job job,boolean retry,String reason,long after) {
        try { repo.finish(job,retry&&job.attempts()<3?"RETRY_WAIT":retry?"FAILED":"BLOCKED",reason,after); }
        catch(RuntimeException ex) { log.warn("Weather task completion deferred id={} type={}",job.id(),ex.getClass().getSimpleName()); }
    }
    static void validateResult(Job job,FetchResult result) {
        if(result.source()!=job.source()||result.product()!=job.product()||result.samples().isEmpty())throw new IllegalArgumentException("SOURCE_IDENTITY_MISMATCH");
        Set<Instant> seen=new HashSet<>();
        for(Sample s:result.samples()) {
            var date=s.time().atZone(ZONE).toLocalDate();
            if(!seen.add(s.time())||date.isBefore(job.start())||date.isAfter(job.end()))throw new WeatherSourceException(WeatherSourceException.Code.STRUCTURE_ERROR);
        }
    }
    @Scheduled(fixedDelay=3600000,initialDelay=60000)
    public void cleanup() {
        if(!config.isEnabled())return;
        try {
            for(Dataset d:repo.cleanupCandidates(System.currentTimeMillis())) {
                timeseries.drop(d);repo.cleaned(d.id());
            }
        } catch(RuntimeException ex) { log.warn("Weather retention deferred type={}",ex.getClass().getSimpleName()); }
    }
    @PreDestroy public void close() { workers.shutdownNow();heartbeat.shutdownNow(); }
}
