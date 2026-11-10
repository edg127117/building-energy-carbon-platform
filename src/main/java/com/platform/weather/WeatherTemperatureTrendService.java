package com.platform.weather;

import com.platform.framework.exception.BusinessException;
import com.platform.weather.source.WeatherSourceModels.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.platform.weather.WeatherModels.*;
import static com.platform.weather.WeatherTemperatureContracts.*;

/** 历史温度只读投影：复用已发布小时载荷与24小时日均规则，不采集、不补测、不重写日均。 */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=60)
public class WeatherTemperatureTrendService {
    private final WeatherRepository repository;
    private final WeatherService access;
    private final WeatherCodec codec;
    private final Clock clock;
    @Autowired
    public WeatherTemperatureTrendService(WeatherRepository repository,WeatherService access,WeatherCodec codec) {
        this(repository,access,codec,Clock.systemUTC());
    }
    WeatherTemperatureTrendService(WeatherRepository repository,WeatherService access,WeatherCodec codec,Clock clock) {
        this.repository=repository;this.access=access;this.codec=codec;this.clock=clock;
    }
    private record Window(LocalDate from,LocalDate to,boolean year) { }
    private record DayValue(BigDecimal value,int hours,boolean invalid) { }
    private record Snapshot(WeatherTemperatureContracts.Source source,Map<LocalDate,DayValue> days) { }

    public View query(long user,Collection<String> roles,String building,String periodType,String period) {
        access.reader(user,roles,building);
        Window window=window(periodType,period);
        LocalDate today=LocalDate.now(clock.withZone(ZONE));
        Map<String,Snapshot> snapshots=new HashMap<>();
        List<Point> points=new ArrayList<>();int[] sampleCount={0};
        for(LocalDate from=window.from();from.isBefore(window.to());) {
            LocalDate to=window.year()?from.plusMonths(1):from.plusDays(1);
            points.add(point(building,from,to,today,snapshots,sampleCount));from=to;
        }
        var sources=snapshots.values().stream().map(Snapshot::source).sorted(Comparator.comparing(WeatherTemperatureContracts.Source::datasetId)).toList();
        String status=status(points);
        String fingerprint=codec.hash(List.of(building,periodType,period,points,sources.stream().map(s->List.of(s.datasetId(),s.bindingId(),s.locationVersion(),s.contentHash())).toList()));
        return new View(building,periodType,period,ZONE.getId(),"°C","COMPLETE_HISTORY_HOURLY_MEAN",today,status,List.copyOf(points),sources,fingerprint);
    }

    private Point point(String building,LocalDate from,LocalDate to,LocalDate today,Map<String,Snapshot> snapshots,int[] sampleCount) {
        int expected=Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(from,to)*24);
        if(from.isAfter(today))return result(from,to,null,"NOT_STARTED",0,expected,List.of("PERIOD_NOT_STARTED"),List.of());
        int hours=0,configured=0,completeDays=0;BigDecimal sum=BigDecimal.ZERO;
        Set<String> datasets=new TreeSet<>(),bindings=new TreeSet<>(),reasons=new TreeSet<>();
        // 当前日及未来日不参与历史统计；未结束月仍可返回已结束日期的覆盖数，但不出月均值。
        for(LocalDate day=from;day.isBefore(to)&&day.isBefore(today);day=day.plusDays(1)) {
            Binding binding=repository.effective(building,day.atStartOfDay(ZONE).toInstant());
            if(binding==null||!binding.enabled()) {reasons.add("LOCATION_NOT_CONFIGURED");continue;}
            if(!building.equals(binding.buildingId()))throw conflict("天气位置不属于所选建筑");
            configured++;bindings.add(binding.id());
            String id=repository.headId(binding.id(),com.platform.weather.source.WeatherSourceModels.Source.OPEN_METEO,Product.HISTORY_HOURLY,day);
            if(id==null) {reasons.add("HISTORY_HOURLY_MISSING");continue;}
            Snapshot snapshot=snapshots.get(id);
            if(snapshot==null) {
                if(snapshots.size()>=400)throw limit();
                snapshot=load(id,binding,sampleCount);snapshots.put(id,snapshot);
            }
            if(!binding.id().equals(snapshot.source().bindingId())||binding.version()!=snapshot.source().locationVersion())throw conflict("历史指针与位置版本不一致");
            datasets.add(id);DayValue value=snapshot.days().getOrDefault(day,new DayValue(null,0,false));
            hours+=value.hours();
            if(value.invalid())reasons.add("INVALID_OR_DUPLICATE_HOURS");
            if(value.value()!=null) {sum=sum.add(value.value());completeDays++;}else reasons.add("TEMPERATURE_HOURS_INCOMPLETE");
        }
        if(bindings.size()>1)reasons.add("MULTIPLE_LOCATION_VERSIONS");
        if(to.isAfter(today)) {reasons.add("PERIOD_NOT_ENDED");return result(from,to,null,"IN_PROGRESS",hours,expected,List.copyOf(reasons),List.copyOf(datasets));}
        int days=expected/24;
        if(completeDays==days)return result(from,to,sum.divide(BigDecimal.valueOf(days),MathContext.DECIMAL128),"READY",hours,expected,List.copyOf(reasons),List.copyOf(datasets));
        String status=configured==0?"NOT_CONFIGURED":hours==0?"MISSING":"PARTIAL";
        return result(from,to,null,status,hours,expected,List.copyOf(reasons),List.copyOf(datasets));
    }
    private Snapshot load(String id,Binding binding,int[] sampleCount) {
        Dataset d=repository.dataset(id);
        if(d==null||!id.equals(d.id())||!binding.id().equals(d.bindingId())||!"PUBLISHED".equals(d.state())
                ||d.source()!=com.platform.weather.source.WeatherSourceModels.Source.OPEN_METEO||d.product()!=Product.HISTORY_HOURLY
                ||d.result()==null||d.result().source()!=d.source()||d.result().product()!=d.product())throw conflict("温度历史指针或已发布载荷身份不一致");
        if(d.result().samples().size()>10000||(sampleCount[0]+=d.result().samples().size())>100000)throw limit();
        Map<LocalDate,List<Sample>> byDay=new HashMap<>();
        for(Sample sample:d.result().samples()) {
            if(sample==null||sample.time()==null)throw conflict("历史温度样本缺少时间");
            byDay.computeIfAbsent(sample.time().atZone(ZONE).toLocalDate(),key->new ArrayList<>()).add(sample);
        }
        Map<LocalDate,DayValue> days=new HashMap<>();
        byDay.forEach((day,samples)-> {
            Map<Integer,Integer> hourCounts=new HashMap<>();
            boolean invalid=samples.stream().anyMatch(s->s.values().get("temperature_2m")!=null&&!Double.isFinite(s.values().get("temperature_2m")));
            for(Sample s:samples) {
                var time=s.time().atZone(ZONE);Double temperature=s.values().get("temperature_2m");
                if(time.toLocalTime().getMinute()==0&&time.getSecond()==0&&time.getNano()==0&&temperature!=null&&Double.isFinite(temperature))hourCounts.merge(time.getHour(),1,Integer::sum);
            }
            invalid|=hourCounts.values().stream().anyMatch(count->count>1);
            int validHours=(int)hourCounts.values().stream().filter(count->count==1).count();
            var mean=WeatherQueryService.mean(samples,day,"temperature_2m");
            invalid|=mean.value()!=null&&!Double.isFinite(mean.value());
            BigDecimal value=!invalid&&validHours==24&&mean.value()!=null?BigDecimal.valueOf(mean.value()):null;
            days.put(day,new DayValue(value,validHours,invalid));
        });
        var source=new WeatherTemperatureContracts.Source(id,binding.id(),binding.version(),d.source().name(),d.product().name(),d.fetchedAt(),codec.hash(d.result()),Provenance.of(d));
        return new Snapshot(source,Map.copyOf(days));
    }
    private static Point result(LocalDate from,LocalDate to,BigDecimal value,String state,int actual,int expected,List<String> reasons,List<String> datasets) {
        return new Point(from,from.atStartOfDay(ZONE).toInstant(),to.atStartOfDay(ZONE).toInstant(),value,state,actual,expected,reasons,datasets);
    }
    private static String status(List<Point> points) {
        if(points.stream().allMatch(p->"READY".equals(p.status())))return "READY";
        var closed=points.stream().filter(p->!Set.of("IN_PROGRESS","NOT_STARTED").contains(p.status())).toList();
        if(closed.isEmpty())return points.stream().allMatch(p->"NOT_STARTED".equals(p.status()))?"NOT_STARTED":"IN_PROGRESS";
        if(closed.stream().allMatch(p->"READY".equals(p.status())))return "IN_PROGRESS";
        if(closed.stream().allMatch(p->"NOT_CONFIGURED".equals(p.status())))return "NOT_CONFIGURED";
        if(closed.stream().allMatch(p->"MISSING".equals(p.status())))return "MISSING";
        return "PARTIAL";
    }
    private static Window window(String type,String period) {
        try {
            LocalDate from,to;boolean year="YEAR".equals(type);
            if(year&&period!=null&&period.matches("\\d{4}")) {from=Year.parse(period).atDay(1);to=from.plusYears(1);}
            else if("MONTH".equals(type)&&period!=null&&period.matches("\\d{4}-\\d{2}")) {from=YearMonth.parse(period).atDay(1);to=from.plusMonths(1);}
            else throw new IllegalArgumentException();
            if(from.isBefore(HISTORY_START)||from.getYear()>2200)throw new IllegalArgumentException();
            return new Window(from,to,year);
        } catch(DateTimeException|IllegalArgumentException e) {throw new BusinessException(400,"INVALID_TEMPERATURE_PERIOD","仅支持2024至2200年的明确年或月期间");}
    }
    private static BusinessException conflict(String message){return new BusinessException(409,"WEATHER_TEMPERATURE_EVIDENCE_INVALID",message);}
    private static BusinessException limit(){return new BusinessException(409,"WEATHER_TEMPERATURE_LIMIT_EXCEEDED","温度查询超过400数据集、单批10000或总计100000样本限制，不截断返回");}
}
