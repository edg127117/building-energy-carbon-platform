package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.platform.carbon.HourlyMonitoringCalculator.ZONE;

/** 页面读取已发布的小时摘要；首次读取只登记任务，不在请求线程调用计算或天气来源。 */
@Service
public class HourlyMonitoringService {
    private final CarbonAuthorization authorization;
    private final HourlyMonitoringRepository repository;
    private final HourlyMonitoringCalculator calculator;
    private final CarbonDisplayQueryProperties properties;
    HourlyMonitoringService(CarbonAuthorization authorization,HourlyMonitoringRepository repository,
            HourlyMonitoringCalculator calculator,CarbonDisplayQueryProperties properties) {
        this.authorization=authorization;this.repository=repository;this.calculator=calculator;this.properties=properties;
    }
    public HourlyMonitoringContracts.View query(long user,Collection<String> roles,String building,LocalDate date) {
        validate(building,date);authorization.requireReader(user,roles,building);
        long configured=repository.configuredActor(building,properties);
        repository.register(building,date,configured>0?configured:user);
        var now=Instant.now();var snapshot=repository.snapshot(building,date,now,calculator.policy());
        var start=date.atStartOfDay(ZONE).toInstant();List<HourlyMonitoringContracts.Point> points=new ArrayList<>();
        for(int hour=0;hour<24;hour++) {
            var from=start.plusSeconds(hour*3600L);
            if(!from.isBefore(now)){points.add(HourlyMonitoringContracts.empty(hour,from,"NOT_ENDED",null));continue;}
            var row=snapshot.results().get(from.toEpochMilli());
            if(row==null||!row.version().equals(snapshot.versions().get(from.toEpochMilli()))) {
                points.add(HourlyMonitoringContracts.empty(hour,from,"PREPARING",null));continue;
            }
            var point=repository.decode(row);
            points.add(point);
        }
        return new HourlyMonitoringContracts.View(building,date,ZONE.getId(),now,List.copyOf(points));
    }
    public JsonNode evidence(long user,Collection<String> roles,String building,String id) {
        if(id==null||!id.matches("[a-f0-9]{64}"))throw new BusinessException(400,"HOURLY_MONITOR_INVALID","证据标识无效");
        authorization.requireReader(user,roles,building);return repository.evidence(building,id);
    }
    private static void validate(String building,LocalDate date) {
        if(building==null||!building.matches("[A-Za-z0-9_-]{1,32}")||date==null||date.getYear()<2000||date.getYear()>2200)
            throw new BusinessException(400,"HOURLY_MONITOR_INVALID","建筑或日期无效");
    }
}
