package com.platform.weather;

import com.platform.weather.source.WeatherSourceModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import static com.platform.weather.WeatherModels.*;

/** 周期任务只入持久队列；多实例和重启靠计划时槽幂等。 */
@Component
public class WeatherScheduler {
    private static final Logger log=LoggerFactory.getLogger(WeatherScheduler.class);
    private final Map<String,LocalDate> backfillPlanned=new HashMap<>();
    private final WeatherProperties config;
    private final WeatherRepository repo;
    private final WeatherService service;
    public WeatherScheduler(WeatherProperties config,WeatherRepository repo,WeatherService service) { this.config=config;this.repo=repo;this.service=service; }
    @Scheduled(fixedDelay=60000,initialDelay=15000)
    public void schedule() {
        if(!config.isEnabled())return;
        Instant now=Instant.now();LocalDate today=now.atZone(ZONE).toLocalDate();
        if(config.getTaskActorId()>0)for(Binding b:repo.bindings()) {
            if(!b.enabled()||b.effectiveFrom().isAfter(now)||(b.effectiveTo()!=null&&!b.effectiveTo().isAfter(now)))continue;
            try {
                var roles=service.liveRoles(config.getTaskActorId());service.admin(config.getTaskActorId(),roles,b.buildingId());
                if(config.isOpenMeteoEnabled()) {
                    if(!today.equals(backfillPlanned.get(b.id()))) {
                        for(Binding past:repo.bindings()) {
                            if(!past.buildingId().equals(b.buildingId())||!past.enabled())continue;
                            LocalDate last=past.effectiveTo()==null?today.minusDays(1):past.effectiveTo().atZone(ZONE).toLocalDate().minusDays(1);
                            if(last.isAfter(today.minusDays(1)))last=today.minusDays(1);
                            for(LocalDate from=past.effectiveFrom().atZone(ZONE).toLocalDate();!from.isAfter(last);) {
                                LocalDate to=from.withDayOfMonth(from.lengthOfMonth());if(to.isAfter(last))to=last;
                                submit(past,Source.OPEN_METEO,Product.HISTORY_HOURLY,from,to,"initial:"+to);
                                submit(past,Source.OPEN_METEO,Product.HISTORY_DAILY,from,to,"initial:"+to);
                                from=to.plusDays(1);
                            }
                        }
                        backfillPlanned.put(b.id(),today);
                    }
                    submit(b,Source.OPEN_METEO,Product.CURRENT,today,today,"current:"+now.getEpochSecond()/900);
                    submit(b,Source.OPEN_METEO,Product.FORECAST_HOURLY,today,today.plusDays(6),"forecast:"+now.getEpochSecond()/10800);
                    submit(b,Source.OPEN_METEO,Product.FORECAST_DAILY,today,today.plusDays(6),"forecast:"+now.getEpochSecond()/10800);
                    if(!now.atZone(ZONE).toLocalTime().isBefore(LocalTime.of(0,15))) {
                        LocalDate start=today.minusDays(7);if(start.isBefore(HISTORY_START))start=HISTORY_START;
                        // 历史按每一天解析有效位置，旧地点也得到最近七天修订。
                        for(LocalDate day=start;day.isBefore(today);day=day.plusDays(1)) {
                            Binding past=repo.effective(b.buildingId(),day.atStartOfDay(ZONE).toInstant());
                            if(past==null||!past.enabled())continue;
                            submit(past,Source.OPEN_METEO,Product.HISTORY_HOURLY,day,day,"history:"+today);
                            submit(past,Source.OPEN_METEO,Product.HISTORY_DAILY,day,day,"history:"+today);
                        }
                    }
                }
                if(config.isChinaWeatherEnabled()&&b.cityCode()!=null)submit(b,Source.CHINA_WEATHER,Product.FORECAST_DAILY,today,today.plusDays(6),"forecast:"+now.getEpochSecond()/10800);
            } catch(RuntimeException ex) { log.warn("Weather schedule blocked building={} type={}",b.buildingId(),ex.getClass().getSimpleName()); }
        }
        if(config.isEnergyEnabled()&&!now.atZone(ZONE).toLocalTime().isBefore(LocalTime.of(0,15)))for(var target:config.getEnergyTargets()) {
            try {
                long actor=target.getActorId();var roles=service.liveRoles(actor);service.runner(actor,roles,target.getBuildingId());
                Job j=WeatherService.newJob("ENERGY",target.getBuildingId(),null,null,null,today.minusDays(8),today.minusDays(1),target.getSystemId(),target.getPointId(),actor);
                repo.enqueue(j,"scheduled-energy:"+target.getPointId()+":"+today,List.of(target.getBuildingId(),target.getSystemId(),target.getPointId(),today));
            } catch(RuntimeException ex) { log.warn("Daily energy schedule blocked point={} type={}",target.getPointId(),ex.getClass().getSimpleName()); }
        }
    }
    private void submit(Binding b,Source source,Product product,LocalDate from,LocalDate to,String slot) {
        Job j=WeatherService.newJob("WEATHER",b.buildingId(),b.id(),source,product,from,to,null,null,config.getTaskActorId());
        repo.enqueue(j,"scheduled:"+b.id()+":"+source+":"+product+":"+from+":"+slot,List.of(b.id(),source,product,from,to,slot));
    }
}
