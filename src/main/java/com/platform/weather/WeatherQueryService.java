package com.platform.weather;

import com.platform.framework.exception.BusinessException;
import com.platform.weather.source.WeatherSourceModels.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.platform.weather.WeatherModels.*;

/** 只读已发布不可变载荷；逐日位置版本隔离，预报整批选择，缺失值不补零。 */
@Service
public class WeatherQueryService {
    private final WeatherRepository repo;
    private final WeatherService access;
    public WeatherQueryService(WeatherRepository repo,WeatherService access) { this.repo=repo;this.access=access; }
    private Binding binding(String building,Instant at) { return repo.effective(building,at); }
    private static LocalDate day(Sample s) { return s.time().atZone(ZONE).toLocalDate(); }
    private static Dataset first(List<Dataset> values) { return values.isEmpty()?null:values.getFirst(); }
    static boolean completeDay(Sample s) {
        return s!=null && s.values().get("temperature_2m_max")!=null && s.values().get("temperature_2m_min")!=null
                && (s.values().get("weather_code")!=null || !s.text().getOrDefault("weather","").isBlank());
    }
    private boolean failed(Binding b,Source s,Product p) {
        Job j=repo.latestJob(b.id(),s,p);
        return j!=null && Set.of("FAILED","BLOCKED","RETRY_WAIT").contains(j.state());
    }
    private boolean eligible(Dataset d,LocalDate today,Instant now) {
        if(d==null || d.fetchedAt().isBefore(now.minus(Duration.ofHours(6))))return false;
        Map<LocalDate,Sample> byDay=new HashMap<>();
        for(Sample s:d.result().samples())if(byDay.put(day(s),s)!=null)return false;
        for(int i=0;i<7;i++)if(!completeDay(byDay.get(today.plusDays(i))))return false;
        return true;
    }
    public Overview overview(long user,Collection<String> roles,String building) {
        access.reader(user,roles,building);
        Instant now=Instant.now();Binding b=binding(building,now);
        if(b==null || !b.enabled())return new Overview(b,series(b,null,Product.CURRENT,now),forecast(b,now));
        Dataset current=first(repo.latest(b.id(),Source.OPEN_METEO,Product.CURRENT));
        return new Overview(b,series(b,current,Product.CURRENT,now),forecast(b,now));
    }
    WeatherSeries series(Binding b,Dataset d,Product p,Instant now) {
        if(b==null || !b.enabled())return new WeatherSeries(b,Source.OPEN_METEO,p,"NOT_CONFIGURED","NOT_CHECKED",null,null,null,List.of());
        String update=failed(b,Source.OPEN_METEO,p)?"FAILED":d==null?"NOT_CHECKED":"FRESH";
        if(d!=null && !update.equals("FAILED")) {
            Instant valid=p==Product.CURRENT?d.result().samples().stream().map(Sample::time).max(Comparator.naturalOrder()).orElse(Instant.EPOCH):d.fetchedAt();
            long hours=p==Product.CURRENT?1:p==Product.FORECAST_HOURLY?6:36;
            if(valid.isBefore(now.minus(Duration.ofHours(hours))))update="OVERDUE";
        }
        String status=d==null?"MISSING":d.result().samples().stream().anyMatch(s->s.values().values().stream().anyMatch(Objects::isNull))?"PARTIAL":"READY";
        return new WeatherSeries(b,Source.OPEN_METEO,p,status,update,d==null?null:d.fetchedAt(),d==null?null:d.id(),Provenance.of(d),d==null?List.of():d.result().samples());
    }
    Forecast forecast(Binding b,Instant now) {
        LocalDate today=now.atZone(ZONE).toLocalDate();
        Dataset selected=null;String reason=null;String status="MISSING";
        if(b!=null && b.enabled()) {
            Dataset china=first(repo.latest(b.id(),Source.CHINA_WEATHER,Product.FORECAST_DAILY));
            Dataset om=first(repo.latest(b.id(),Source.OPEN_METEO,Product.FORECAST_DAILY));
            if(eligible(china,today,now)&&!failed(b,Source.CHINA_WEATHER,Product.FORECAST_DAILY)) { selected=china;status="READY"; }
            else {
                reason=b.cityCode()==null?"REGION_NOT_CONFIGURED":failed(b,Source.CHINA_WEATHER,Product.FORECAST_DAILY)?"PREFERRED_UPDATE_FAILED":"PREFERRED_MISSING_STALE_OR_PARTIAL";
                if(eligible(om,today,now)&&!failed(b,Source.OPEN_METEO,Product.FORECAST_DAILY)) { selected=om;status="READY"; }
                else { selected=china!=null?china:om;status=selected==null?"MISSING":"PARTIAL";reason="NO_ELIGIBLE_FORECAST"; }
            }
        } else status="NOT_CONFIGURED";
        List<DaySlot> slots=new ArrayList<>();
        for(int i=0;i<7;i++) {
            LocalDate date=today.plusDays(i);Sample sample=selected==null?null:selected.result().samples().stream().filter(s->day(s).equals(date)).findFirst().orElse(null);
            slots.add(new DaySlot(date,sample));
        }
        return new Forecast(Source.CHINA_WEATHER,selected==null?null:selected.source(),reason,status,selected==null?"NOT_CHECKED":failed(b,selected.source(),Product.FORECAST_DAILY)?"FAILED":selected.fetchedAt().isBefore(now.minus(Duration.ofHours(6)))?"OVERDUE":"FRESH",selected==null?null:selected.fetchedAt(),selected==null?null:selected.id(),selected!=null&&selected.source()==Source.OPEN_METEO?"GRID":b==null?null:b.coverage(),Provenance.of(selected),slots);
    }
    public List<WeatherSeries> hourly(long user,Collection<String> roles,String building,Product product,Instant from,Instant to) {
        access.reader(user,roles,building);
        if(from==null||to==null||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0
                ||from.isBefore(HISTORY_START.atStartOfDay(ZONE).toInstant())
                ||(product!=Product.HISTORY_HOURLY&&product!=Product.FORECAST_HOURLY))
            throw new BusinessException(400,"INVALID_HOURLY_RANGE","小时查询产品或范围无效");
        List<WeatherSeries> result=new ArrayList<>();
        for(LocalDate date=from.atZone(ZONE).toLocalDate();!date.isAfter(to.minusNanos(1).atZone(ZONE).toLocalDate());date=date.plusDays(1)) {
            Binding b=binding(building,date.atStartOfDay(ZONE).toInstant());
            Dataset d=b==null?null:repo.head(b.id(),Source.OPEN_METEO,product,date);
            WeatherSeries base=series(b,d,product,Instant.now());
            LocalDate selectedDay=date;
            var samples=base.samples().stream().filter(s->day(s).equals(selectedDay)&&!s.time().isBefore(from)&&s.time().isBefore(to)).toList();
            int expected=0;
            for(int hour=0;hour<24;hour++) {
                Instant time=date.atTime(hour,0).atZone(ZONE).toInstant();
                if(!time.isBefore(from)&&time.isBefore(to))expected++;
            }
            String state=base.status();
            if(!state.equals("NOT_CONFIGURED")&&samples.size()<expected)state=samples.isEmpty()?"MISSING":"PARTIAL";
            result.add(new WeatherSeries(base.location(),base.source(),product,state,base.updateStatus(),base.fetchedAt(),base.datasetId(),base.provenance(),samples));
        }
        return result;
    }
    public record DailyWeather(LocalDate date,Binding location,Sample sourceDaily,Double meanTemperature,int temperatureHours,
            Double meanHumidity,int humidityHours,String status,String hourlyDatasetId,String dailyDatasetId,Provenance hourlyProvenance,Provenance dailyProvenance) {}
    static record Mean(Double value,int hours) {}
    static Mean mean(List<Sample> samples,LocalDate day,String field) {
        Map<Integer,Double> hours=new HashMap<>();
        for(Sample s:samples) {
            var t=s.time().atZone(ZONE);
            if(t.toLocalDate().equals(day)&&t.getMinute()==0&&t.getSecond()==0&&t.getNano()==0&&s.values().get(field)!=null)
                if(hours.putIfAbsent(t.getHour(),s.values().get(field))!=null)return new Mean(null,hours.size());
        }
        return new Mean(hours.size()==24?hours.values().stream().mapToDouble(Double::doubleValue).average().orElseThrow():null,hours.size());
    }
    public List<DailyWeather> daily(long user,Collection<String> roles,String building,LocalDate start,LocalDate end) {
        access.reader(user,roles,building);WeatherService.range(start,end,366);
        List<DailyWeather> result=new ArrayList<>();
        for(LocalDate date=start;!date.isAfter(end);date=date.plusDays(1)) {
            Binding b=binding(building,date.atStartOfDay(ZONE).toInstant());
            Dataset h=b==null?null:repo.head(b.id(),Source.OPEN_METEO,Product.HISTORY_HOURLY,date);
            Dataset d=b==null?null:repo.head(b.id(),Source.OPEN_METEO,Product.HISTORY_DAILY,date);
            Mean t=mean(h==null?List.of():h.result().samples(),date,"temperature_2m");
            Mean rh=mean(h==null?List.of():h.result().samples(),date,"relative_humidity_2m");
            LocalDate target=date;
            Sample sd=d==null?null:d.result().samples().stream().filter(s->day(s).equals(target)).findFirst().orElse(null);
            String state=b==null?"NOT_CONFIGURED":h==null&&d==null?"MISSING":t.value()!=null&&rh.value()!=null&&completeDay(sd)?"READY":"PARTIAL";
            result.add(new DailyWeather(date,b,sd,t.value(),t.hours(),rh.value(),rh.hours(),state,h==null?null:h.id(),d==null?null:d.id(),Provenance.of(h),Provenance.of(d)));
        }
        return result;
    }
    public Comparison comparison(long user,Collection<String> roles,String building,String system,String point,LocalDate start,LocalDate end,String metric) {
        access.reader(user,roles,building);
        if(!Set.of("temperature","humidity").contains(metric))throw new BusinessException(400,"INVALID_METRIC","仅支持温度或湿度对照");
        access.validateEnergyObject(user,roles,building,system,point);
        var weather=daily(user,roles,building,start,end);var energy=repo.energy(building,system,point,start,end);
        return new Comparison(building,system,point,metric,weather.stream().map(d->{
            Double value=metric.equals("temperature")?d.meanTemperature():d.meanHumidity();
            int count=metric.equals("temperature")?d.temperatureHours():d.humidityHours();
            return new ComparisonDay(d.date(),energy.get(d.date()),value,metric.equals("temperature")?"°C":"%",count,value==null?"MISSING":"READY",d.hourlyDatasetId());
        }).toList());
    }
}
