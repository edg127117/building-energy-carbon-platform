package com.platform.carbon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 年、月、日展示共享持久快照；计算只由后台执行，在线读取不回退到完整证据扫描。 */
@Service
public class PeriodElectricityQueryService {
    static final String ALGORITHM="ELECTRICITY_DISPLAY_1";
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final CarbonAuthorization authorization;
    private final CarbonDisplayQueryRepository repository;
    private final DisplaySourceRevisionRepository revisions;
    private final DynamicElectricityService dynamic;
    private final DailyElectricityCarbonService daily;
    private final ObjectMapper mapper;
    private final CarbonDisplayQueryCache cache;

    public PeriodElectricityQueryService(CarbonAuthorization authorization,CarbonDisplayQueryRepository repository,
            DisplaySourceRevisionRepository revisions,DynamicElectricityService dynamic,DailyElectricityCarbonService daily,ObjectMapper mapper,CarbonDisplayQueryCache cache) {
        this.authorization=authorization;this.repository=repository;this.revisions=revisions;
        this.dynamic=dynamic;this.daily=daily;this.mapper=mapper;this.cache=cache;
    }

    record Query(String building,String kind,String period) {
        String domain(){return kind.startsWith("DAILY_TREND_")?"DAILY":"DYNAMIC";}
        String type(){return kind.substring("ELECTRICITY_".length());}
        String key(){return CarbonCalculationCore.sha256(ALGORITHM+":"+building+":"+kind+":"+period);}
        LocalDate start(){return switch(kind){case "ELECTRICITY_YEAR"->Year.parse(period).atDay(1);case "ELECTRICITY_DAY","DAILY_TREND_DAY"->LocalDate.parse(period);default->YearMonth.parse(period).atDay(1);};}
        LocalDate end(){return switch(kind){case "ELECTRICITY_YEAR"->start().plusYears(1);case "ELECTRICITY_DAY","DAILY_TREND_DAY"->start().plusDays(1);default->start().plusMonths(1);};}
        Instant from(){return start().atStartOfDay(ZONE).toInstant();}
        Instant to(){return end().atStartOfDay(ZONE).toInstant();}
    }
    record Read(String version,Object value,boolean request,CarbonDisplayQueryRepository.Head observed) { }

    public DynamicElectricityContracts.View dynamic(long user,Collection<String> roles,String building,String type,String period) {
        if(!Set.of("YEAR","MONTH","DAY").contains(Objects.toString(type,"")))throw new BusinessException(400,"PERIOD_DISPLAY_QUERY_INVALID","查询粒度无效");
        Query q=validate(building,"ELECTRICITY_"+type,period);
        var read=read(user,roles,q);
        if(read.value()==null)throw preparing();
        return (DynamicElectricityContracts.View)read.value();
    }
    public DailyElectricityCarbonContracts.View detail(long user,Collection<String> roles,String building,String month) {
        var read=read(user,roles,validate(building,"DAILY_TREND_DETAIL",month));
        if(read.value()==null)throw preparing();
        return (DailyElectricityCarbonContracts.View)read.value();
    }
    public DailyTrendSummaryContracts.View summary(long user,Collection<String> roles,String building,String month) {
        var read=read(user,roles,validate(building,"DAILY_TREND_DETAIL",month),true);
        return read.value()==null?DailyTrendSummaryContracts.preparing(building,month,read.version()):
                (DailyTrendSummaryContracts.View)read.value();
    }

    private Read read(long user,Collection<String> roles,Query q) {
        return read(user,roles,q,false);
    }
    private Read read(long user,Collection<String> roles,Query q,boolean summary) {
        try {return readCurrent(user,roles,q,summary);}
        catch(org.springframework.dao.TransientDataAccessException|org.springframework.transaction.TransactionTimedOutException e) {throw unavailable(q,e);}
        catch(org.springframework.transaction.TransactionSystemException e) {
            // 驱动取消语句后可能关闭连接，回滚异常不得掩盖原始读取超时。
            if(e.getOriginalException() instanceof org.springframework.dao.TransientDataAccessException
                    ||e.getOriginalException() instanceof org.springframework.transaction.TransactionTimedOutException)throw unavailable(q,e);
            throw e;
        }
    }
    private BusinessException unavailable(Query q,RuntimeException cause) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Period read temporarily unavailable: key={}",q.key(),cause);
        return new BusinessException(503,"PERIOD_DISPLAY_READ_BUSY","数据读取暂时繁忙，请稍后重试");
    }
    private Read readCurrent(long user,Collection<String> roles,Query q,boolean summary) {
        authorization.requireReader(user,roles,q.building());
        // 小载荷与指针同快照读取；日明细的大正文在短事务外读取，随后复核同一版本与哈希。
        var snapshot=revisions.readSnapshot(q.building(),domain(q),q.from(),q.to(),()-> {
            String version=version(q,base(q));
            var h=repository.head(q.key());
            if(h!=null&&version.equals(h.source())&&version.equals(h.built())&&"READY".equals(h.state()))
                return new Read(version,summary?decodeSummary(q,h,repository.summary(h,q.period())):q.kind().equals("DAILY_TREND_DETAIL")?h:decode(q,h,repository.payload(h)),false,h);
            if(h!=null&&version.equals(h.source())&&"FAILED".equals(h.state())&&h.errorStatus()!=null&&h.errorStatus()!=403)
                throw new BusinessException(h.errorStatus(),h.error(),"展示结果准备失败，请检查来源或稍后重试");
            // 已登记的同版本任务由后台恢复；轮询不能反复抢占发布锁或重置在途任务。
            boolean request=h==null||!version.equals(h.source())||("FAILED".equals(h.state())&&Objects.equals(h.errorStatus(),403));
            return new Read(version,null,request,h);
        });
        Read read=snapshot.value();
        if(read.value() instanceof CarbonDisplayQueryRepository.Head h) {
            String payload=cache.get(q.key(),h.hash());
            boolean loaded=payload==null;
            if(loaded)payload=repository.onlinePayload(h);
            if(payload==null)throw preparing();
            Object value=decode(q,h,payload);
            boolean current=revisions.readSnapshot(q.building(),domain(q),q.from(),q.to(),()-> {
                var latest=repository.head(q.key());
                return latest!=null&&"READY".equals(latest.state())&&read.version().equals(current(q))
                        &&Objects.equals(h.source(),latest.source())&&Objects.equals(h.built(),latest.built())&&Objects.equals(h.hash(),latest.hash());
            }).value();
            if(!current)throw preparing();
            if(loaded)cache.put(q.key(),h.hash(),payload);
            return new Read(read.version(),value,false,h);
        }
        // 任务登记只做单行比较更新，不争用跨期间的来源发布锁；旧任务仍须通过重建和发布两次来源校验。
        if(read.request())repository.requestPeriod(q.key(),q.building(),q.kind(),encode(q),user,read.version(),System.currentTimeMillis(),read.observed());
        return read;
    }

    void prepare(Query q,long user) {
        var observed=repository.head(q.key());String current=current(q);
        if(observed!=null&&current.equals(observed.source())&&!("FAILED".equals(observed.state())&&Objects.equals(observed.errorStatus(),403)))return;
        repository.requestPeriod(q.key(),q.building(),q.kind(),encode(q),user,current,System.currentTimeMillis(),observed);
    }
    // 原始累计量只影响实际引用它的结果；实时温度等原始事实不能持续撤销仅含已审核电量的展示。
    private String domain(Query q){return q.domain().equals("DAILY")?"DAILY":repository.hasRawSources(q.building(),q.from(),q.to())?"DYNAMIC_WITH_RAW":"DYNAMIC";}
    private String base(Query q){return revisions.version(q.building(),domain(q),q.from(),q.to());}
    String current(Query q){return version(q,base(q));}
    boolean rebuild(CarbonDisplayQueryRepository.Head h,String token,Collection<String> roles) {
        Query q=parse(h);authorization.requireReader(h.user(),roles,q.building());
        String base=base(q);
        if(!h.source().equals(version(q,base))){prepare(q,h.user());return false;}
        Object value;
        if(q.kind().equals("ELECTRICITY_YEAR_MONTH"))value=dynamic.queryYearMonth(h.user(),roles,q.building(),YearMonth.parse(q.period()));
        else if(q.kind().equals("ELECTRICITY_YEAR")) {
            value=assembleYear(q,h.user());
            if(value==null){repository.waitForInputs(h,token,System.currentTimeMillis());return false;}
        }
        else if(q.kind().equals("DAILY_TREND_DAY"))value=daily.prepareDay(h.user(),roles,q.building(),LocalDate.parse(q.period()));
        else if(q.kind().equals("DAILY_TREND_DETAIL")) {
            value=assembleMonth(q,h.user());
            if(value==null){repository.waitForInputs(h,token,System.currentTimeMillis());return false;}
        }else value=dynamic.query(h.user(),roles,q.building(),q.type(),q.period());
        String payload=encode(value);
        String resultHash=CarbonCalculationCore.sha256(q.key()+":"+payload);
        String summary=q.kind().equals("DAILY_TREND_DETAIL")?encode(DailyTrendSummaryContracts.from((DailyElectricityCarbonContracts.View)value,h.source())):null;
        if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8_000_000)
            throw new BusinessException(503,"PERIOD_DISPLAY_CAPACITY","展示结果超过容量，不能截断来源清单");
        boolean[] published={false};
        boolean unchanged=revisions.ifCurrent(q.building(),domain(q),q.from(),q.to(),base,()-> {
            // 时间边界也属于来源：跨日/月的候选不能以旧状态发布。
            if(h.source().equals(version(q,base))) {
                published[0]=repository.publish(h,token,payload,resultHash,System.currentTimeMillis());
                // 明细和摘要在同一个来源守卫事务提交；摘要哈希绑定明细哈希，禁止混用两代结果。
                if(published[0]&&summary!=null)repository.publishSummary(h,q.period(),summary,summaryHash(q,resultHash,summary),System.currentTimeMillis());
            }
        });
        if(!unchanged||!published[0])prepare(q,h.user());
        return published[0];
    }

    /** 升级前已有明细由后台补齐摘要；在线请求不为补齐摘要读取完整证据清单。 */
    void prepareSummary(CarbonDisplayQueryRepository.Head h) {
        if(!h.kind().equals("DAILY_TREND_DETAIL"))return;
        Query q=parse(h);
        if(repository.summary(h,q.period())!=null)return;
        String base=base(q);if(!h.source().equals(version(q,base)))return;
        var detail=(DailyElectricityCarbonContracts.View)decode(q,h,repository.payload(h));
        String payload=encode(DailyTrendSummaryContracts.from(detail,h.source()));
        revisions.ifCurrent(q.building(),domain(q),q.from(),q.to(),base,()-> {
            var current=repository.head(h.key());
            if(current!=null&&"READY".equals(current.state())&&Objects.equals(h.source(),current.source())
                    &&Objects.equals(h.built(),current.built())&&Objects.equals(h.hash(),current.hash()))
                repository.publishSummary(h,q.period(),payload,summaryHash(q,h.hash(),payload),System.currentTimeMillis());
        });
    }
    private String summaryHash(Query q,String detailHash,String payload){return CarbonCalculationCore.sha256(q.key()+":"+detailHash+":"+payload);}
    private DailyTrendSummaryContracts.View decodeSummary(Query q,CarbonDisplayQueryRepository.Head h,CarbonDisplayQueryRepository.Summary summary) {
        if(summary==null)return null;
        if(summary.payload()==null||!Objects.equals(summary.hash(),summaryHash(q,h.hash(),summary.payload())))throw integrity();
        try {
            var v=mapper.readValue(summary.payload(),DailyTrendSummaryContracts.View.class);
            if(!q.building().equals(v.buildingId())||!q.period().equals(v.month())||!h.built().equals(v.sourceVersion())
                    ||!DailyTrendSummaryContracts.CONTRACT.equals(v.contractVersion())||!ZONE.getId().equals(v.timezoneId())
                    ||v.days()==null||v.days().size()!=YearMonth.parse(q.period()).lengthOfMonth())throw integrity();
            return v;
        }catch(JsonProcessingException e){throw integrity();}
    }

    private Object assembleYear(Query q,long user) {
        var points=new ArrayList<DynamicElectricityContracts.Point>();boolean missing=false;
        for(int month=1;month<=12;month++) {
            Query leaf=validate(q.building(),"ELECTRICITY_YEAR_MONTH",YearMonth.of(Integer.parseInt(q.period()),month).toString());
            var h=repository.head(leaf.key());String current=current(leaf);
            if(h==null||!"READY".equals(h.state())||!current.equals(h.source())||!current.equals(h.built())) {
                if(h!=null&&"FAILED".equals(h.state())&&current.equals(h.source())&&h.errorStatus()!=null&&h.errorStatus()!=403)
                    throw new BusinessException(h.errorStatus(),h.error(),"部分月份的来源准备失败");
                prepare(leaf,user);missing=true;
            }else points.add((DynamicElectricityContracts.Point)decode(leaf,h,repository.payload(h)));
        }
        // 各月保留年度算法的原始点及证据身份；全年水位守卫拒绝混入已被来源变更淘汰的月份。
        return missing?null:new DynamicElectricityContracts.View(q.building(),"YEAR",q.period(),ZONE.getId(),"tCO2","kgCO2/kWh","MONITORING",List.copyOf(points));
    }

    private Object assembleMonth(Query q,long user) {
        var days=new ArrayList<DailyElectricityCarbonContracts.Day>();boolean missing=false;
        for(int day=1;day<=YearMonth.parse(q.period()).lengthOfMonth();day++) {
            Query leaf=validate(q.building(),"DAILY_TREND_DAY",YearMonth.parse(q.period()).atDay(day).toString());
            var h=repository.head(leaf.key());String current=current(leaf);
            if(h==null||!"READY".equals(h.state())||!current.equals(h.source())||!current.equals(h.built())) {
                if(h!=null&&"FAILED".equals(h.state())&&current.equals(h.source())&&h.errorStatus()!=null&&h.errorStatus()!=403)
                    throw new BusinessException(h.errorStatus(),h.error(),"部分日期的来源准备失败");
                prepare(leaf,user);missing=true;
            }else days.add((DailyElectricityCarbonContracts.Day)decode(leaf,h,repository.payload(h)));
        }
        return missing?null:daily.assemble(q.building(),YearMonth.parse(q.period()),days);
    }

    Query parse(CarbonDisplayQueryRepository.Head h) {
        try {
            Query q=mapper.readValue(h.query(),Query.class);validate(q.building(),q.kind(),q.period());
            if(!q.key().equals(h.key())||!q.building().equals(h.building())||!q.kind().equals(h.kind()))throw integrity();
            return q;
        }catch(JsonProcessingException e){throw integrity();}
    }
    private Object decode(Query q,CarbonDisplayQueryRepository.Head h,String payload) {
        if(payload==null||!Objects.equals(h.hash(),CarbonCalculationCore.sha256(q.key()+":"+payload)))throw integrity();
        try {
            if(q.kind().equals("ELECTRICITY_YEAR_MONTH")) {
                var point=mapper.readValue(payload,DynamicElectricityContracts.Point.class);
                if(!q.period().equals(point.period())||!q.from().equals(point.startInclusive())||!q.to().equals(point.endExclusive()))throw integrity();
                return point;
            }
            if(q.kind().equals("DAILY_TREND_DAY")) {
                var day=mapper.readValue(payload,DailyElectricityCarbonContracts.Day.class);
                if(!q.start().equals(day.date())||!q.from().equals(day.startInclusive())||!q.to().equals(day.endExclusive()))throw integrity();
                return day;
            }
            if(q.domain().equals("DAILY")) {
                var v=mapper.readValue(payload,DailyElectricityCarbonContracts.View.class);
                if(!q.building().equals(v.buildingId())||!q.period().equals(v.month())||!ZONE.getId().equals(v.timezoneId())
                        ||v.days()==null||v.days().size()!=YearMonth.parse(q.period()).lengthOfMonth())throw integrity();
                return v;
            }
            var v=mapper.readValue(payload,DynamicElectricityContracts.View.class);
            int size=switch(q.type()){case "YEAR"->12;case "MONTH"->YearMonth.parse(q.period()).lengthOfMonth();default->24;};
            if(!q.building().equals(v.buildingId())||!q.period().equals(v.period())||!q.type().equals(v.periodType())
                    ||!ZONE.getId().equals(v.timezoneId())||v.points()==null||v.points().size()!=size)throw integrity();
            return v;
        }catch(JsonProcessingException e){throw integrity();}
    }
    static Query validate(String building,String kind,String period) {
        try {
            if(building==null||!building.matches("[A-Za-z0-9_-]{1,32}")||period==null)throw new DateTimeException("identity");
            int year=switch(kind) {
                case "ELECTRICITY_YEAR" -> {if(!period.matches("\\d{4}"))throw new DateTimeException("year");yield Year.parse(period).getValue();}
                case "ELECTRICITY_MONTH","ELECTRICITY_YEAR_MONTH","DAILY_TREND_DETAIL" -> {if(!period.matches("\\d{4}-\\d{2}"))throw new DateTimeException("month");yield YearMonth.parse(period).getYear();}
                case "ELECTRICITY_DAY","DAILY_TREND_DAY" -> {if(!period.matches("\\d{4}-\\d{2}-\\d{2}"))throw new DateTimeException("day");yield LocalDate.parse(period).getYear();}
                default -> throw new DateTimeException("kind");
            };
            if(year<2000||year>2200)throw new DateTimeException("year");
            return new Query(building,kind,period);
        }catch(DateTimeException|NumberFormatException e){throw new BusinessException(400,"PERIOD_DISPLAY_QUERY_INVALID","建筑或查询期间无效");}
    }
    private String version(Query q,String base) {
        var now=ZonedDateTime.now(ZONE);
        LocalDate start=q.start(),end=q.end();
        String clock=!now.toLocalDate().isBefore(end)?"CLOSED":now.toLocalDate().isBefore(start)?"FUTURE":switch(q.kind()) {
            case "ELECTRICITY_YEAR"->YearMonth.from(now).toString();
            case "ELECTRICITY_DAY"->now.toLocalDate()+":"+now.getHour();
            default->now.toLocalDate().toString();
        };
        return CarbonCalculationCore.sha256(ALGORITHM+":"+base+":"+clock);
    }
    private String encode(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw integrity();}}
    private static BusinessException preparing(){return new BusinessException(503,"PERIOD_DISPLAY_PREPARING","数据正在更新，请稍后重试");}
    private static BusinessException integrity(){return new BusinessException(503,"PERIOD_DISPLAY_INTEGRITY","展示结果完整性校验失败");}
}
