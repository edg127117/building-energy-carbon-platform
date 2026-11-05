package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.energy.classification.BuildingDailyElectricityReader;
import com.platform.energy.classification.ClassificationContracts;
import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.platform.carbon.DailyElectricityCarbonContracts.*;

/** 只读日序列：最多31次日计量及已存小时结果读取，不触发采集、核算、发布或写入。 */
@Service
public class DailyElectricityCarbonService implements AutoCloseable {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CONTRACT = "DAILY_ELECTRICITY_STATIC_CARBON_V1";
    private final CarbonAuthorization authorization;
    private final BuildingDailyElectricityReader electricity;
    private final HourlyCarbonRepository hourly;
    private final Clock clock;
    private final CarbonProperties properties;
    // 全服务只允许两个读取工作线程；等待队列最多容纳两个额外整月请求的四条读取通道。
    private final ThreadPoolExecutor readers = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),Thread.ofPlatform().daemon().name("daily-trend-read-",0).factory(),new ThreadPoolExecutor.AbortPolicy());

    @org.springframework.beans.factory.annotation.Autowired
    public DailyElectricityCarbonService(CarbonAuthorization authorization, BuildingDailyElectricityReader electricity,
            HourlyCarbonRepository hourly, CarbonProperties properties) {
        this(authorization, electricity, hourly, properties, Clock.systemUTC());
    }
    DailyElectricityCarbonService(CarbonAuthorization authorization, BuildingDailyElectricityReader electricity,
            HourlyCarbonRepository hourly, CarbonProperties properties, Clock clock) {
        this.authorization=authorization;this.electricity=electricity;this.hourly=hourly;this.properties=properties;this.clock=clock;
    }

    // 每个日计量有自己的只读快照；409缺口在该日事务结束后处理，不让一次缺测污染整月事务。
    public View query(long user, Collection<String> roles, String building, String month) {
        YearMonth selected;
        try {
            if (building==null || building.isBlank() || building.length()>32 || month==null || !month.matches("\\d{4}-\\d{2}")) throw new DateTimeException("identity");
            selected=YearMonth.parse(month);
            if(selected.getYear()<2000 || selected.getYear()>2200) throw new DateTimeException("year");
        } catch(DateTimeException exception) { throw new BusinessException(400,"DAILY_TREND_QUERY_INVALID","建筑或月份无效"); }
        authorization.requireReader(user,roles,building);
        var today=LocalDate.now(clock.withZone(ZONE));
        Day[] results=new Day[selected.lengthOfMonth()];
        var deadline=CarbonCalculationDeadline.start(properties.getDailyTrendQueryTimeout());
        var stableRoles=Set.copyOf(roles);
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        var tasks=new ArrayList<Future<?>>();
        try {
            deadline.requireRemaining();
            for(int lane=0;lane<2;lane++) {
                final int first=lane;
                tasks.add(readers.submit(()->{
                    // 每个日事务仍由原只读服务独立建立；线程复用不能携带上一请求的截止时间。
                    try(var ignored=deadline.bind()) {
                        for(int index=first;index<results.length;index+=2) {
                            deadline.requireRemaining();
                            if(cancelled.get() || Thread.currentThread().isInterrupted())throw CarbonCalculationDeadline.timeout();
                            var date=selected.atDay(index+1);
                            results[index]=date.isBefore(today)?readDay(user,stableRoles,building,date):empty(date,"NOT_ENDED");
                            deadline.requireRemaining();
                        }
                    }
                }));
            }
            for(var task:tasks) task.get(deadline.remaining().toNanos(),TimeUnit.NANOSECONDS);
        } catch(RejectedExecutionException exception) {
            throw new BusinessException(503,"DAILY_TREND_BUSY","日趋势查询繁忙，请稍后重试");
        } catch(TimeoutException exception) {
            throw CarbonCalculationDeadline.timeout();
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt();throw CarbonCalculationDeadline.timeout();
        } catch(ExecutionException exception) {
            if(exception.getCause() instanceof RuntimeException failure)throw failure;
            throw new IllegalStateException("日趋势读取失败",exception.getCause());
        } finally {
            cancelled.set(true);
            for(var task:tasks) if(!task.isDone())task.cancel(true);
            readers.purge();
        }
        List<Day> days=Arrays.asList(results);
        long available=days.stream().filter(d->"AVAILABLE".equals(d.electricityStatus()) || "AVAILABLE".equals(d.staticCarbonStatus())).count();
        String state=days.stream().allMatch(d->"AVAILABLE".equals(d.electricityStatus()) && "AVAILABLE".equals(d.staticCarbonStatus()))?"COMPLETE"
                :available>0?"PARTIAL":selected.atDay(1).isBefore(today)?"MISSING":"NOT_STARTED";
        String hash=CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(List.of(CONTRACT,building,month,days))));
        deadline.requireRemaining();
        return new View(building,month,ZONE.getId(),state,"kWh","tCO2e","kgCO2e/kWh","MONITORING",CONTRACT,List.copyOf(days),clock.instant(),hash);
    }

    @jakarta.annotation.PreDestroy
    @Override public void close() { readers.shutdownNow(); }

    private Day readDay(long user,Collection<String> roles,String building,LocalDate date) {
        ClassificationContracts.QueryView nativeDay=null;
        String electricStatus="MISSING",reason="DAILY_QUANTITY_MISSING";
        BigDecimal quantity=null;
        try {
            nativeDay=electricity.read(user,roles,building,date);
            if(!building.equals(nativeDay.buildingId()) || !start(date).equals(nativeDay.startInclusive())
                    || !start(date.plusDays(1)).equals(nativeDay.endExclusive()) || !"KWH".equalsIgnoreCase(nativeDay.unit()))
                throw new BusinessException(503,"DAILY_QUANTITY_SCOPE_MISMATCH","日计量结果范围或单位不一致");
            if("CALCULABLE".equals(nativeDay.status()) && nativeDay.total()!=null && nativeDay.total().signum()>=0) {
                quantity=nativeDay.total();electricStatus="AVAILABLE";reason=null;
            } else {electricStatus="INCOMPLETE";reason="DAILY_QUANTITY_INCOMPLETE";}
        } catch(BusinessException exception) {
            if(exception.getCode()!=409) throw exception;
            electricStatus="BLOCKED";reason=Objects.toString(exception.getErrorCode(),"DAILY_QUANTITY_BLOCKED");
        }
        var stored=hourly.byDate(building,date,null);
        var carbon=staticCarbon(stored,building,date);
        return new Day(date,start(date),start(date.plusDays(1)),quantity,electricStatus,reason,
                nativeDay==null?null:nativeDay.versionId(),nativeDay==null?null:nativeDay.relationVersionId(),
                nativeDay==null?List.of():nativeDay.evidenceIds(),nativeDay==null?null:nativeDay.resultNature(),
                carbon.emission(),carbon.status(),carbon.factor(),carbon.factorStatus(),
                stored==null?null:stored.view().calculationBatchId(),stored==null?null:stored.view().revision(),
                stored==null?null:stored.view().declaredSourceNature(),carbon.versions(),stored==null?null:stored.view().evidenceHash(),carbon.gasBasis());
    }

    private record Carbon(BigDecimal emission,String status,BigDecimal factor,String factorStatus,List<String> versions,String gasBasis) {
        private Carbon(BigDecimal emission,String status,BigDecimal factor,String factorStatus,List<String> versions) {
            this(emission,status,factor,factorStatus,versions,"UNKNOWN");
        }
    }
    private static Carbon unavailable(String state) { return new Carbon(null,state,null,state,List.of()); }
    private static Carbon staticCarbon(HourlyCarbonRepository.Stored stored,String building,LocalDate date) {
        if(stored==null) return unavailable("HOURLY_RESULT_MISSING");
        var view=stored.view();
        if(!building.equals(view.buildingId()) || !date.equals(view.date()) || !ZONE.getId().equals(view.timezoneId())
                || !"KG_CO2E".equals(view.unit()) || !"PURCHASED_ELECTRICITY_SCOPE_2".equals(view.scope()) || !"MONITORING".equals(view.resultNature()))
            throw new BusinessException(503,"DAILY_CARBON_SCOPE_MISMATCH","小时碳结果范围或单位不一致");
        if(!"COMPLETE".equals(view.state()) || view.availableHours()!=24 || view.dueHours()!=24 || view.points()==null
                || view.points().size()!=24 || view.dailyTotal()==null || view.dailyTotal().signum()<0) return unavailable("INCOMPLETE_HOURS");
        JsonNode hours=stored.evidenceTree().path("hours");
        if(!hours.isArray() || hours.size()!=24) return unavailable("FROZEN_FACTOR_UNAVAILABLE");
        var values=new TreeSet<BigDecimal>();var versions=new TreeSet<String>();
        boolean co2Only=true;
        for(int i=0;i<24;i++) {
            var point=view.points().get(i);var trace=hours.get(i);
            if(point.hour()!=i || !"COMPLETE".equals(point.status()) || !start(date).plusSeconds(i*3600L).equals(point.startInclusive())
                    || !point.startInclusive().plusSeconds(3600).equals(point.endExclusive()) || trace.path("hour").asInt(-1)!=i
                    || !"COMPLETE".equals(trace.path("status").asText()) || !Objects.equals(point.evidenceHash(),CarbonCalculationCore.sha256(CarbonEvidence.canonical(trace))))
                return unavailable("INCOMPLETE_HOURS");
            var calculations=trace.path("calculations");
            if(!calculations.isArray() || calculations.isEmpty()) return unavailable("FROZEN_FACTOR_UNAVAILABLE");
            var hourVersions=new TreeSet<String>();
            for(var calculation:calculations) {
                var value=frozenFactor(calculation);
                String id=calculation.path("factorVersionId").asText();
                if(value==null || id.isBlank()) return unavailable("FROZEN_FACTOR_UNAVAILABLE");
                values.add(value);hourVersions.add(id);versions.add(id);
                co2Only &= "GAS_MASS".equals(calculation.path("factor").path("resultBasis").asText());
            }
            if(point.factorVersionIds()==null || !hourVersions.equals(new TreeSet<>(point.factorVersionIds()))) return unavailable("FROZEN_FACTOR_UNAVAILABLE");
        }
        // 同一天不同固定因子不能伪装为单一静态曲线；不平均因子，也不按日电量重新核算碳。
        if(values.size()!=1) return new Carbon(null,"MULTIPLE_FROZEN_FACTORS",null,"MULTIPLE_FROZEN_FACTORS",List.copyOf(versions));
        return new Carbon(view.dailyTotal().movePointLeft(3),"AVAILABLE",values.first(),"AVAILABLE",List.copyOf(versions),co2Only?"CO2_ONLY":"GHG_CO2E");
    }

    private static BigDecimal frozenFactor(JsonNode calculation) {
        var factor=calculation.path("factor");
        if(!"SCOPE_2".equals(factor.path("scopeType").asText()) || !"PURCHASED_ELECTRICITY_LOCATION".equals(factor.path("category").asText())
                || !"ELECTRICITY".equals(factor.path("energyItemCode").asText()) || !"KWH".equals(factor.path("inputUnitCode").asText())) return null;
        var components=new ArrayList<JsonNode>();
        for(var component:factor.path("components")) if("DIRECT_EMISSION_FACTOR".equals(component.path("type").asText())) components.add(component);
        if(components.size()!=1) return null;
        var component=components.getFirst();
        boolean co2="GAS_MASS".equals(factor.path("resultBasis").asText()) && "CO2".equals(factor.path("gasCode").asText())
                && "CO2_ONLY_ELECTRICITY".equals(factor.path("gasCoverage").asText()) && "KG_CO2/KWH".equals(component.path("unit").asText())
                && "CO2".equals(calculation.path("gwp").path("gasCode").asText()) && isOne(calculation.path("gwpValue").asText());
        boolean co2e="CO2E_DIRECT".equals(factor.path("resultBasis").asText()) && "KG_CO2E/KWH".equals(component.path("unit").asText());
        if(!co2 && !co2e) return null;
        try {var value=new BigDecimal(component.path("value").asText());return value.signum()<0?null:value;}
        catch(NumberFormatException exception) {return null;}
    }

    private static Instant start(LocalDate date) {return date.atStartOfDay(ZONE).toInstant();}
    private static boolean isOne(String value) {
        try { return new BigDecimal(value).compareTo(BigDecimal.ONE)==0; }
        catch(NumberFormatException exception) { return false; }
    }
    private static Day empty(LocalDate date,String state) {return new Day(date,start(date),start(date.plusDays(1)),null,state,state,null,null,List.of(),null,null,state,null,state,null,null,null,List.of(),null);}
}
