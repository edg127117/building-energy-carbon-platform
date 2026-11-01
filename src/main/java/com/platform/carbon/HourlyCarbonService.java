package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.carbon.CarbonModels.*;
import com.platform.carbon.HourlyCarbonContracts.*;
import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.MDC;
import com.platform.audit.TraceContext;

import static com.platform.carbon.CarbonErrors.*;

/** 独立的小时监测命令/查询；读取不触发计算，来源中断不发布覆盖既有结果的失败修订。 */
@Service
public class HourlyCarbonService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final CarbonAuthorization authorization;
    private final HourlyCarbonInputService inputs;
    private final HourlyCarbonRuleService rules;
    private final HourlyCarbonRepository repository;
    private final CarbonProperties properties;
    private final Semaphore workers;

    public HourlyCarbonService(CarbonAuthorization authorization,HourlyCarbonInputService inputs,
            HourlyCarbonRuleService rules,HourlyCarbonRepository repository,CarbonProperties properties) {
        this.authorization=authorization;this.inputs=inputs;this.rules=rules;this.repository=repository;
        this.properties=properties;this.workers=new Semaphore(properties.getMaximumConcurrentCalculations(),true);
    }

    public View calculate(long user,Collection<String> roles,Request request) {
        validate(request);
        authorization.requireCalculationRunner(user,roles,request.buildingId());
        String requestHash=hash(CarbonEvidence.tree(request));
        var replay=repository.replay(request.buildingId(),request.idempotencyKey(),requestHash);
        if(replay!=null)return replay.view();
        var deadline=CarbonCalculationDeadline.start(properties.getCalculationTimeout());
        return bounded(deadline,()->{
            try(var ignored=deadline.bind()) {
                return compute(user,roles,request,requestHash,deadline);
            }
        });
    }

    public View query(long user,Collection<String> roles,String building,LocalDate date,Integer revision) {
        validateIdentity(building,date);
        if(revision!=null&&revision<1)throw error(400,VALIDATION_FAILED,"修订号必须为正整数");
        authorization.requireReader(user,roles,building);
        var stored=repository.byDate(building,date,revision);
        if(stored!=null)return stored.view();
        if(revision!=null)throw error(404,"HOURLY_CARBON_NOT_FOUND","小时碳结果修订不存在");
        Instant now=Instant.now();List<Point> points=new ArrayList<>();int due=0;
        for(int hour=0;hour<24;hour++) {
            Instant from=start(date).plusSeconds(hour*3600L),to=from.plusSeconds(3600);
            boolean ended=!to.isAfter(now);if(ended)due++;
            points.add(empty(hour,from,to,ended?"MISSING":"NOT_DUE",ended?"RESULT_NOT_CALCULATED":"HOUR_NOT_ENDED",
                    ended?"尚未生成该日小时结果":"小时尚未结束"));
        }
        return new View(building,date,ZONE.getId(),"KG_CO2E","PURCHASED_ELECTRICITY_SCOPE_2",null,null,null,
                "MONITORING","MISSING",null,null,null,null,"RECEIVED_TIME_CUTOFF",due,0,null,List.copyOf(points),now,null);
    }

    public JsonNode evidence(long user,Collection<String> roles,String batchId) {
        if(batchId==null||!batchId.matches("[a-zA-Z0-9_-]{1,64}"))throw error(400,VALIDATION_FAILED,"小时批次编码无效");
        var stored=repository.byId(batchId);
        if(stored==null)throw error(404,"HOURLY_CARBON_NOT_FOUND","小时碳结果批次不存在");
        authorization.requireReader(user,roles,stored.view().buildingId());
        return CarbonEvidence.parse(stored.evidenceJson());
    }

    private View compute(long user,Collection<String> roles,Request request,String requestHash,CarbonCalculationDeadline deadline) {
        Instant start=start(request.date());
        int due=(int)Math.max(0,Math.min(24,Duration.between(start,request.calculationAsOf()).toSeconds()/3600));
        Instant readTo=start.plusSeconds(due*3600L);
        var capture=new HourlyCarbonInputService.Capture();
        var evidence=CarbonEvidence.object();evidence.put("schemaVersion",1);
        evidence.put("calculationVersion","HOURLY_PURCHASED_ELECTRICITY_V1");
        evidence.put("activityWatermarkMeaning","RECEIVED_TIME_CUTOFF");
        evidence.put("sourceNatureAssurance","CALLER_DECLARATION_NOT_FIELD_ACCEPTANCE");
        evidence.set("request",CarbonEvidence.tree(request));
        var hourEvidence=evidence.putArray("hours");
        List<Point> points=new ArrayList<>();BigDecimal exactDay=BigDecimal.ZERO;int available=0;
        for(int hour=0;hour<24;hour++) {
            deadline.requireRemaining();
            Instant from=start.plusSeconds(hour*3600L),to=from.plusSeconds(3600);
            if(hour>=due) { points.add(empty(hour,from,to,"NOT_DUE","HOUR_NOT_ENDED","小时尚未结束"));continue; }
            var trace=CarbonEvidence.object();trace.put("hour",hour);
            try {
                var input=inputs.read(user,roles,request,from,to,start,readTo,capture,deadline);
                trace.set("inputs",input.evidence());var calculations=trace.putArray("calculations");
                Set<String> factors=new TreeSet<>(),formulas=new TreeSet<>(),gwps=new TreeSet<>(),rounding=new TreeSet<>(),gases=new TreeSet<>();
                BigDecimal exact=BigDecimal.ZERO,quantity=BigDecimal.ZERO;
                if(input.activities().isEmpty())throw error(409,ACTIVITY_INCOMPLETE,"小时外购电力来源为空");
                for(var value:input.activities()) {
                    deadline.requireRemaining();
                    if(value.quantityKwh()==null||value.quantityKwh().signum()<0)throw error(409,ACTIVITY_INCOMPLETE,"小时电量无效");
                    String sourceHash=hash(value.evidence());
                    var upstream=CarbonEvidence.object();upstream.put("projectEnergyPathway","PURCHASED_ELECTRICITY");
                    upstream.put("hourInputHash",hash(input.evidence()));
                    var activity=new ActivitySegment(sourceHash,request.buildingId(),PeriodType.HOUR,from,to,ZONE.getId(),
                            value.energyItemCode(),value.quantityKwh(),"KWH","OBSERVED","COMPLETE",ResultNature.MONITORING,sourceHash,upstream);
                    var calculated=rules.calculate(activity,UsageNature.valueOf(request.factorUsageNature()));
                    exact=exact.add(calculated.exactKgCo2e());quantity=quantity.add(value.quantityKwh());
                    factors.add(calculated.factorVersionId());formulas.add(calculated.formulaVersionId());
                    if(calculated.gwpVersionId()!=null)gwps.add(calculated.gwpVersionId());
                    rounding.add(calculated.roundingPolicyVersionId());gases.add(calculated.gasCoverage());
                    calculations.add(calculated.evidence());
                }
                trace.put("status","COMPLETE");trace.put("exactKgCo2e",exact.toPlainString());
                String pointHash=hash(trace);hourEvidence.add(trace);
                points.add(new Point(hour,from,to,"COMPLETE",null,null,quantity,rounded(exact),input.sourceSnapshotIds(),
                        List.copyOf(factors),List.copyOf(formulas),List.copyOf(gwps),List.copyOf(rounding),input.qualityPolicyVersions(),
                        input.projectVersionIds(),input.relationVersionIds(),List.copyOf(gases),pointHash));
                exactDay=exactDay.add(exact);available++;
            } catch(BusinessException ex) {
                if(ex.getCode()==401||ex.getCode()==403||ex.getCode()>=500||LIMIT_EXCEEDED.equals(ex.getErrorCode()))throw ex;
                String code=Objects.toString(ex.getErrorCode(),"CONFIGURATION_NOT_READY");
                String status=ACTIVITY_INCOMPLETE.equals(code)||code.contains("ANCHOR")||code.contains("SOURCE")?"INCOMPLETE":"BLOCKED";
                trace.put("status",status);trace.put("reasonCode",code);trace.put("reason",ex.getMessage());
                hourEvidence.add(trace);points.add(empty(hour,from,to,status,code,ex.getMessage()));
            }
        }
        evidence.set("contexts",CarbonEvidence.tree(capture.contexts()));
        // 先合并未舍入十进制结果，整日完整才给日合计；缺失和未来小时不折成零。
        String evidenceJson=CarbonEvidence.canonical(evidence);
        var draft=new View(request.buildingId(),request.date(),ZONE.getId(),"KG_CO2E","PURCHASED_ELECTRICITY_SCOPE_2",request.sourceKind(),
                request.declaredSourceNature(),request.factorUsageNature(),"MONITORING",available==24?"COMPLETE":"PARTIAL",null,null,
                request.calculationAsOf(),request.calculationAsOf(),"RECEIVED_TIME_CUTOFF",due,available,available==24?rounded(exactDay):null,
                List.copyOf(points),Instant.now(),CarbonCalculationCore.sha256(evidenceJson));
        deadline.requireRemaining();
        try { return repository.publish(user,request,requestHash,draft,evidenceJson).view(); }
        catch(org.springframework.dao.DuplicateKeyException conflict) {
            // 不同日期并发复用了同一建筑幂等键时，由数据库唯一键裁决，再转为稳定业务冲突。
            var replay=repository.replay(request.buildingId(),request.idempotencyKey(),requestHash);
            if(replay!=null)return replay.view();
            throw conflict;
        }
    }

    private static Point empty(int hour,Instant from,Instant to,String status,String code,String reason) {
        return new Point(hour,from,to,status,code,reason,null,null,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),null);
    }
    private static BigDecimal rounded(BigDecimal value) { return value.setScale(18,RoundingMode.HALF_UP); }
    private static Instant start(LocalDate date) { return date.atStartOfDay(ZONE).toInstant(); }
    private static String hash(JsonNode value) { return CarbonCalculationCore.sha256(CarbonEvidence.canonical(value)); }
    private static void validateIdentity(String building,LocalDate date) {
        if(building==null||building.isBlank()||building.length()>32||date==null||date.getYear()<2000||date.getYear()>2200)
            throw error(400,VALIDATION_FAILED,"建筑或查询日期无效");
    }
    private static void validate(Request r) {
        if(r==null)throw error(400,VALIDATION_FAILED,"小时计算请求不能为空");
        validateIdentity(r.buildingId(),r.date());
        if(r.calculationAsOf()==null||r.calculationAsOf().isAfter(Instant.now())||r.calculationAsOf().getNano()%1_000_000!=0
                ||r.idempotencyKey()==null||r.idempotencyKey().isBlank()||r.idempotencyKey().length()>100
                ||!Set.of("RAW_CUMULATIVE","REVIEWED_INTERVAL").contains(Objects.toString(r.sourceKind(),""))
                ||!Set.of("SIMULATED","SOURCE_LABELLED").contains(Objects.toString(r.declaredSourceNature(),""))
                ||!Set.of("FORMAL","DEVELOPMENT_REFERENCE").contains(Objects.toString(r.factorUsageNature(),""))
                ||r.sourceReference()==null||r.sourceReference().isBlank()||r.sourceReference().length()>500)
            throw error(400,VALIDATION_FAILED,"小时计算接收截止、来源声明或幂等参数无效");
    }

    /** 保留执行槽直到底层工作退出；超时中断后，发布前仍须核对请求截止时间。 */
    private <T>T bounded(CarbonCalculationDeadline deadline,Callable<T> task) {
        try { if(!workers.tryAcquire(deadline.remaining().toNanos(),TimeUnit.NANOSECONDS))throw CarbonCalculationDeadline.timeout(); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt();throw error(503,DEPENDENCY_UNAVAILABLE,"小时计算被中断"); }
        var outcome=new CompletableFuture<T>();String traceId=MDC.get(TraceContext.MDC_KEY);
        Thread thread;
        try {
            thread=Thread.ofVirtual().name("hourly-carbon-").start(()->{
                if(traceId!=null)MDC.put(TraceContext.MDC_KEY,traceId);
                try { outcome.complete(task.call()); }catch(Throwable ex) { outcome.completeExceptionally(ex); }
                finally { MDC.remove(TraceContext.MDC_KEY);workers.release(); }
            });
        }catch(RuntimeException ex) {workers.release();throw ex;}
        try { return outcome.get(deadline.remaining().toNanos(),TimeUnit.NANOSECONDS); }
        catch(TimeoutException ex) {thread.interrupt();throw CarbonCalculationDeadline.timeout();}
        catch(InterruptedException ex) {thread.interrupt();Thread.currentThread().interrupt();throw error(503,DEPENDENCY_UNAVAILABLE,"小时计算被中断");}
        catch(ExecutionException ex) {if(ex.getCause() instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("小时计算失败",ex.getCause());}
    }
}
