package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.carbon.CarbonModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import static com.platform.carbon.DailyStaticCarbonContracts.*;
import static com.platform.carbon.CarbonErrors.*;

/** Explicit DAY calculation publishes frozen static rules. Queries never multiply today's factor. */
@Service
public class DailyStaticCarbonService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final CarbonAuthorization authorization;
    private final DailyStaticCarbonInput inputs;
    private final HourlyCarbonRuleService rules;
    private final DailyStaticCarbonRepository repository;
    private final DailyTrendSummaryRepository versions;
    private final CarbonProperties properties;
    private final PlatformTransactionManager transactions;
    private final Semaphore workers;
    public DailyStaticCarbonService(CarbonAuthorization authorization,DailyStaticCarbonInput inputs,HourlyCarbonRuleService rules,
            DailyStaticCarbonRepository repository,DailyTrendSummaryRepository versions,CarbonProperties properties,PlatformTransactionManager transactions) {
        this.authorization=authorization;this.inputs=inputs;this.rules=rules;this.repository=repository;this.versions=versions;
        this.properties=properties;this.transactions=transactions;this.workers=new Semaphore(properties.getMaximumConcurrentCalculations(),true);
    }
    public View calculate(long user,Collection<String> roles,Request request) {
        validate(request);
        authorization.requireCalculationRunner(user,roles,request.buildingId());
        String requestHash=hash(CarbonEvidence.tree(request));
        var replay=repository.replay(request.buildingId(),request.idempotencyKey(),requestHash);if(replay!=null)return replay.view();
        var deadline=CarbonCalculationDeadline.start(properties.getCalculationTimeout());
        boolean acquired=false;
        try {
            acquired=workers.tryAcquire(deadline.remaining().toNanos(),TimeUnit.NANOSECONDS);
            if(!acquired)throw CarbonCalculationDeadline.timeout();
            try(var ignored=deadline.bind()) {
                var capture=new TransactionTemplate(transactions);
                capture.setReadOnly(true);capture.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                capture.setTimeout(CarbonCalculationDeadline.timeoutSeconds(deadline.remaining()));
                Draft draft=Objects.requireNonNull(capture.execute(status->compute(user,roles,request,
                        inputVersion(request.buildingId(),YearMonth.from(request.date())))));
                String version=draft.view().sourceVersion();deadline.requireRemaining();
                var transaction=new TransactionTemplate(transactions);
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                transaction.setTimeout(CarbonCalculationDeadline.timeoutSeconds(deadline.remaining()));
                try {
                return transaction.execute(status->repository.publish(user,request,requestHash,draft.view(),draft.evidence(),()->{
                    authorization.requireCalculationRunner(user,roles,request.buildingId());
                    if(!version.equals(inputVersion(request.buildingId(),YearMonth.from(request.date()))))throw changed();
                    // Reread the approved input and complete static rule bundle, not only their identifiers.
                    // This also detects changed frozen definitions or input text with unchanged version heads.
                    Draft current=compute(user,roles,request,version);
                    if(!draft.view().evidenceHash().equals(current.view().evidenceHash()))throw changed();
                    deadline.requireRemaining();
                }).view());
                } catch(org.springframework.dao.DuplicateKeyException conflict) {
                    // Different day locks can still race on the building-wide idempotency key.
                    var winner=repository.replay(request.buildingId(),request.idempotencyKey(),requestHash);
                    if(winner!=null)return winner.view();throw conflict;
                }
            }
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw CarbonCalculationDeadline.timeout();}
        finally {if(acquired)workers.release();}
    }
    private record Draft(View view,JsonNode evidence) { }
    private Draft compute(long user,Collection<String> roles,Request r,String sourceVersion) {
        DailyStaticCarbonRepository.budget();
        var input=inputs.read(user,roles,r.buildingId(),r.date());
        String inputHash=hash(input.evidence());
        Instant from=r.date().atStartOfDay(ZONE).toInstant(),to=r.date().plusDays(1).atStartOfDay(ZONE).toInstant();
        var upstream=CarbonEvidence.object();upstream.put("projectEnergyPathway","PURCHASED_ELECTRICITY");
        upstream.put("dailyInputHash",inputHash);upstream.set("dailyInput",input.evidence());
        var activity=new ActivitySegment(inputHash,r.buildingId(),PeriodType.DAY,from,to,ZONE.getId(),"ELECTRICITY",input.quantity(),
                "KWH","OBSERVED","COMPLETE",ResultNature.MONITORING,inputHash,upstream);
        // The existing static matcher requires one factor/formula/GWP covering the entire actual day.
        // A mid-day change is blocked rather than allocating an unobserved hourly quantity.
        var calculated=rules.calculate(activity,UsageNature.valueOf(r.factorUsageNature()));
        var factor=DailyElectricityCarbonService.frozenFactor(calculated.evidence());
        if(factor==null)throw error(409,FACTOR_CONFLICT,"Daily static electricity requires a frozen direct electricity factor");
        var evidence=CarbonEvidence.object();evidence.put("schemaVersion",1);evidence.put("calculationVersion","DAILY_STATIC_PURCHASED_ELECTRICITY_V1");
        evidence.put("sourceNatureAssurance","CALLER_DECLARATION_NOT_FIELD_ACCEPTANCE");
        evidence.put("activityWatermarkMeaning","CURRENT_APPROVED_AT_CALCULATION");
        evidence.put("sourceVersion",sourceVersion);evidence.set("request",CarbonEvidence.tree(r));
        evidence.set("input",input.evidence());evidence.set("activity",CarbonEvidence.tree(activity));
        evidence.set("calculation",calculated.evidence());
        String gas="CO2_ONLY_ELECTRICITY".equals(calculated.gasCoverage())?"CO2_ONLY":"GHG_CO2E";
        View view=new View(r.buildingId(),r.date(),from,to,ZONE.getId(),"DAY","KG_CO2E","PURCHASED_ELECTRICITY_SCOPE_2","MONITORING","COMPLETE",
                "REVIEWED_INTERVAL","CURRENT_APPROVED_AT_CALCULATION",r.declaredSourceNature(),input.sourceNature(),r.factorUsageNature(),null,null,r.calculationAsOf(),sourceVersion,
                input.classificationVersion(),input.relationVersion(),input.projectVersion(),input.quantity(),calculated.exactKgCo2e(),factor,"AVAILABLE",gas,
                List.of(calculated.factorVersionId()),inputHash,hash(evidence));
        DailyStaticCarbonRepository.budget();return new Draft(view,evidence);
    }
    String inputVersion(String building,YearMonth month){return versions.inputVersion(building,month);}
    record Trend(DailyStaticCarbonRepository.Stored stored,boolean current) { }
    Trend readForTrend(long user,Collection<String> roles,String building,LocalDate date) {
        return readForTrend(user,roles,building,date,null);
    }
    Trend readForTrend(long user,Collection<String> roles,String building,LocalDate date,
            com.platform.energy.classification.MonthlyReadingWindow sources) {
        var stored=repository.byDate(building,date,null);if(stored==null)return null;
        // Compare this day's actual approved/project input. A new batch on another day must not
        // invalidate this frozen result merely because the coarse month discovery fingerprint changed.
        try {
            var input=sources==null?inputs.read(user,roles,building,date):inputs.readPrepared(user,roles,building,date,sources);
            return new Trend(stored,stored.view().inputEvidenceHash().equals(hash(input.evidence())));
        } catch(com.platform.framework.exception.BusinessException e) {
            if(e.getCode()!=409)throw e;
            return new Trend(stored,false);
        }
    }
    public View query(long user,Collection<String> roles,String building,LocalDate date,Integer revision) {
        identity(building,date);authorization.requireReader(user,roles,building);
        try(var ignored=CarbonCalculationDeadline.start(properties.getQueryTimeout()).bind()) {
            var stored=repository.byDate(building,date,revision);
            if(stored==null)throw error(404,NOT_FOUND,"Daily static result missing");
            return stored.view();
        }
    }
    public JsonNode evidence(long user,Collection<String> roles,String id) {
        if(id==null||!id.matches("[a-zA-Z0-9_-]{1,64}"))throw error(400,VALIDATION_FAILED,"Daily static batch identity invalid");
        try(var ignored=CarbonCalculationDeadline.start(properties.getQueryTimeout()).bind()) {
            var stored=repository.byId(id);if(stored==null)throw error(404,NOT_FOUND,"Daily static result missing");
            authorization.requireReader(user,roles,stored.view().buildingId());return stored.evidence();
        }
    }
    static void validate(Request r) {
        if(r==null)throw error(400,VALIDATION_FAILED,"Daily static request required");identity(r.buildingId(),r.date());
        if(r.calculationAsOf()==null||r.calculationAsOf().isAfter(Instant.now())||r.calculationAsOf().getNano()%1_000_000!=0
                ||r.date().plusDays(1).atStartOfDay(ZONE).toInstant().isAfter(r.calculationAsOf())
                ||r.idempotencyKey()==null||r.idempotencyKey().isBlank()||r.idempotencyKey().length()>100
                ||!Set.of("SIMULATED","SOURCE_LABELLED").contains(Objects.toString(r.declaredSourceNature(),""))
                ||!Set.of("FORMAL","DEVELOPMENT_REFERENCE").contains(Objects.toString(r.factorUsageNature(),""))
                ||r.sourceReference()==null||r.sourceReference().isBlank()||r.sourceReference().length()>500)
            throw error(400,VALIDATION_FAILED,"Daily static date, source declaration or idempotency request invalid");
    }
    private static void identity(String building,LocalDate date){if(building==null||building.isBlank()||building.length()>32||date==null||date.getYear()<2000||date.getYear()>2200)throw error(400,VALIDATION_FAILED,"Daily static identity invalid");}
    private static String hash(JsonNode value){return CarbonEvidence.canonicalHash(value);}
    private static RuntimeException changed(){return error(409,"DAILY_STATIC_SOURCE_CHANGED","Daily static source or rules changed before publication");}
}
