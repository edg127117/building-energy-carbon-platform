package com.platform.energy.efficiency;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.audit.*;
import com.platform.energy.activity.EnergyActivityDataReader;
import com.platform.energy.activity.EnergyActivityDataReader.*;
import com.platform.energy.aggregation.*;
import com.platform.energy.period.*;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.entity.*;
import com.platform.hvac.service.*;
import com.platform.iot.calculation.CalculationPointReadService;
import com.platform.iot.qualityusage.*;
import com.platform.iot.qualityusage.QualityUsageModels.*;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.*;
import com.platform.system.service.BuildingScopeService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.platform.energy.efficiency.EerpContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 合成原始点经过公共读取、实际算法、H2任务索引和原生量提交；外部数值库明确使用替身。 */
class EerpServiceTest {
    static final String B="b", S="station";
    static final List<String> ROLES=List.of("ENERGY_MANAGER");
    static final Instant START=Instant.parse("2025-01-01T00:00:00Z"), END=Instant.parse("2026-01-01T00:00:00Z");
    EerpService service; EerpRepository repo; EerpLimits limits; EerpSupport codec;
    JdbcTemplate jdbc; EnergyPeriodValueStore values; EnergyActivityDataReader reader;
    BizDataPointService pointService; BizEquipmentService equipmentService;
    RelationGovernanceService relations; BackendDutyService duties; BuildingScopeService scope;
    AuditEvidenceWriter audit; EerpPeriodCalculator calculator; EerpConfigurationValidator validator;
    NativePeriodSnapshotService snapshots; NativeQuantityAggregationService nativeAggregation;
    ObjectMapper mapper; Map<String,BizDataPoint> metadata; List<RawEvent> facts;
    EerpService newService() {
        return new EerpService(repo,codec,limits,new EnergyPeriodAuthorization(duties,scope),validator,calculator,snapshots,audit,
                new AuditGovernanceProperties(),new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    @BeforeEach void setup() {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:eerp_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("eerp-schema-test.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds); repo=new EerpRepository(jdbc); mapper=new ObjectMapper().registerModule(new JavaTimeModule()); codec=new EerpSupport(mapper); limits=new EerpLimits();
        values=mock(EnergyPeriodValueStore.class); reader=mock(EnergyActivityDataReader.class); scope=mock(BuildingScopeService.class);
        duties=mock(BackendDutyService.class); when(duties.hasDuty(anyLong(),any())).thenReturn(true);
        pointService=mock(BizDataPointService.class); equipmentService=mock(BizEquipmentService.class); relations=mock(RelationGovernanceService.class); audit=mock(AuditEvidenceWriter.class);
        var quality=mock(QualityUsagePolicyResolver.class); var context=mock(ResolutionContext.class); when(context.configRevision()).thenReturn(1L);
        when(quality.historyContext(anySet(),eq("INDICATOR_CALCULATION"),anyLong(),anyLong())).thenReturn(context);
        when(quality.resolve(eq(context),anyString(),eq("INDICATOR_CALCULATION"),anyLong(),anyInt())).thenAnswer(i -> {
            int q=i.getArgument(4); return new Resolution(q==0?Decision.ALLOW:Decision.BLOCK,q,"INDICATOR_CALCULATION",PolicySource.SYSTEM_DEFAULT_Q0_ONLY,null,1,"TEST_Q0_ONLY");
        });
        metadata=new LinkedHashMap<>(); facts=new ArrayList<>();
        when(pointService.getById(anyString())).thenAnswer(i -> metadata.get(i.getArgument(0)));
        when(pointService.listByBuilding(B)).thenAnswer(i -> Result.success(new ArrayList<>(metadata.values())));
        when(reader.readRawEvents(eq(B),anySet(),anyLong(),anyLong(),any(),anyInt())).thenAnswer(i -> {
            Set<String> selected=i.getArgument(1); long from=i.getArgument(2),to=i.getArgument(3); Cursor cursor=i.getArgument(4);
            var rows=facts.stream().filter(f -> selected.contains(f.pointId())&&f.eventTime()>=from&&f.eventTime()<to)
                    .filter(f -> cursor==null||f.eventTime()>cursor.eventTime()||(f.eventTime()==cursor.eventTime()&&f.pointId().compareTo(cursor.pointId())>0))
                    .sorted(Comparator.comparingLong(RawEvent::eventTime).thenComparing(RawEvent::pointId)).toList();
            int n=Math.min(500,rows.size()); var page=rows.subList(0,n); var last=page.isEmpty()?null:page.getLast();
            return new RawEventPage(page,rows.size()>n,rows.size()>n?new Cursor(last.eventTime(),last.pointId()):null);
        });
        var publicReader=new CalculationPointReadService(scope,pointService,reader,quality);
        nativeAggregation=spy(new NativeQuantityAggregationService(new EnergyAggregationCore(),new EnergyAggregationAuthorization(duties,scope),mock(EnergyAggregationGovernanceService.class)));
        doReturn(new NativeQuantityAggregationService.GovernedEvidence(List.of(),List.of())).when(nativeAggregation).governedEvidence(anyLong(),anyCollection(),anyString(),anyString(),any(),any());
        calculator=new EerpPeriodCalculator(publicReader,nativeAggregation,codec);
        validator=new EerpConfigurationValidator(relations,pointService,equipmentService,limits,codec);
        snapshots=new NativePeriodSnapshotService(jdbc,values,new EnergyPeriodAuthorization(duties,scope),mapper);
        service=newService(); stubRelations();
    }
    @Test void bothCoolingSourcesPassPublicChainAndKeepIndependentElectricity() {
        var config=activate(configuration(true));
        sample("flow",START,100); sample("supply",START,7); sample("return",START,12);
        sample("flow",START.plusSeconds(3600),100); sample("supply",START.plusSeconds(3600),7); sample("return",START.plusSeconds(3600),12);
        sample("electric",START,10); sample("electric",START.plusSeconds(3600),110);
        var task=period(config,START,START.plusSeconds(3600),"flow-hour");
        assertThat(task.status()).isEqualTo("SUCCEEDED"); var r=(PeriodResult)task.result();
        assertThat(r.complete()).isTrue(); assertThat(r.coolingKwh()).isCloseTo(new BigDecimal("580.555555555556"),within(new BigDecimal("0.000000001")));
        assertThat(r.electricityKwh()).isEqualByComparingTo("100"); assertThat(r.sourceNature()).isEqualTo("UNKNOWN");
        assertThat(service.trace(1,ROLES,task.taskId()).evidenceJson()).contains("factIdentity","1000","4.18","INDICATOR_CALCULATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM energy_native_quantity_snapshot WHERE status='VISIBLE'",Integer.class)).isEqualTo(3);
        verify(values,atLeastOnce()).write(argThat(v -> v.tce()==null&&v.tceUnitCode()==null));
    }
    @Test void completeAnnualIsBuiltFrom365SealedPublicPeriodResults() {
        var config=activate(configuration(false)); List<String> ids=new ArrayList<>();
        for(int day=0;day<=365;day++){sample("cold",START.plusSeconds(day*86400L),day*5000d);sample("electric",START.plusSeconds(day*86400L),day*1000d);}
        for(int day=0;day<365;day++) ids.add(seal(period(config,START.plusSeconds(day*86400L),START.plusSeconds((day+1)*86400L),"day-"+day)).taskId());
        var annual=service.createAnnual(1,ROLES,new AnnualRequest("annual",B,S,2025,"UTC","tz1",ids,null));
        assertThat(annual.status()).isEqualTo("SUCCEEDED"); var r=(AnnualResult)annual.result();
        assertThat(r.eerp()).isEqualByComparingTo("5"); assertThat(r.evaluationBand()).isEqualTo("GUIDANCE_ONLY");
        assertThat(r.completeness()).isEqualTo("COMPLETE"); assertThat(r.inputTaskIds()).hasSize(365);
        assertThat(r.standardVerification()).isEqualTo("FULL_TEXT_NOT_VERIFIED");
    }
    @Test void failureDoesNotPublishResultAndResumeUsesFrozenInputAcrossServiceRestart() {
        var config=activate(configuration(false)); baselineHour();
        doThrow(new IllegalStateException("isolated store down")).when(values).write(any());
        var task=period(config,START,START.plusSeconds(3600),"retry");
        assertThat(task.status()).isEqualTo("FAILED"); assertThat(task.result()).isNull(); assertThat(repo.task(task.taskId()).stage()).isNotBlank();
        String hash=task.evidenceHash(); facts.clear(); clearInvocations(reader); reset(values);
        service=newService(); var restored=service.execute(1,ROLES,task.taskId());
        assertThat(restored.status()).isEqualTo("SUCCEEDED"); assertThat(restored.evidenceHash()).isEqualTo(hash);
        assertThat(((PeriodResult)restored.result()).coolingKwh()).isEqualByComparingTo("500"); verifyNoInteractions(reader);
        var same=service.createPeriod(1,ROLES,new PeriodRequest("retry",config.versionId(),START,START.plusSeconds(3600),END,null));
        assertThat(same.taskId()).isEqualTo(task.taskId());
        assertThatThrownBy(() -> service.createPeriod(1,ROLES,new PeriodRequest("retry",config.versionId(),START,START.plusSeconds(1800),END,null)))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo("EERP_IDEMPOTENCY_CONFLICT"));
    }
    @Test void missingAnchorAndQualityBlockedRowsAreIncompleteRatherThanZero() {
        var config=activate(configuration(false)); sample("cold",START,0); sample("electric",START,0); sample("electric",START.plusSeconds(3600),100);
        var task=period(config,START,START.plusSeconds(3600),"missing");
        assertThat(task.status()).isEqualTo("SUCCEEDED"); var r=(PeriodResult)task.result();
        assertThat(r.complete()).isFalse(); assertThat(r.measures().getFirst().quantityKwh()).isNull();
        assertThat(r.issues()).extracting(Issue::code).contains("ENERGY_AGGREGATION_ANCHOR_MISSING");
        facts.add(new RawEvent("cold","cold",B,"SYNTHETIC","cold","chiller",500,START.plusSeconds(3600).toEpochMilli(),END.toEpochMilli(),2,false));
        var blocked=period(config,START,START.plusSeconds(3600),"blocked");
        assertThat(((PeriodResult)blocked.result()).issues()).extracting(Issue::code).contains("QUALITY_BLOCKED");
    }
    @Test void annualRequiresSealingAndRecalculationRequiresReviewWithoutMutatingPredecessor() {
        var config=activate(configuration(false));baselineHour();var original=period(config,START,START.plusSeconds(3600),"first");
        var annual=service.createAnnual(1,ROLES,new AnnualRequest("unsealed",B,S,2025,"UTC","tz1",List.of(original.taskId()),null));
        assertThat(annual.status()).isEqualTo("FAILED");assertThat(annual.failureCode()).isEqualTo("EERP_INPUT_NOT_SEALED");
        var sealed=seal(original);
        var recalc=service.createPeriod(1,ROLES,new PeriodRequest("recalc",config.versionId(),START,START.plusSeconds(3600),END,original.taskId()));
        assertThat(recalc.status()).isEqualTo("PENDING_RECALC");
        String recalcId=recalc.taskId();
        assertThatThrownBy(() -> service.execute(1,ROLES,recalcId)).isInstanceOf(BusinessException.class);
        recalc=service.reviewTask(2,ROLES,recalc.taskId(),"APPROVE_RECALC",new Review(recalc.revision(),"explicit synthetic retry"));
        assertThat(service.execute(1,ROLES,recalc.taskId()).status()).isEqualTo("SUCCEEDED");
        assertThat(service.task(1,ROLES,original.taskId()).evidenceHash()).isEqualTo(sealed.evidenceHash());
        verify(duties).requireDuty(1,BackendDuty.ENERGY_RECALC_SUBMIT);verify(duties).requireDuty(2,BackendDuty.ENERGY_RECALC_APPROVE);
    }
    @Test void governanceRejectsOverlapMissingCoverageWrongUnitsAndStaleRevision() {
        var c=configuration(false);var active=activate(c);
        var draft=service.createConfig(1,ROLES,c);draft=service.reviewConfig(1,ROLES,draft.versionId(),"SUBMIT",new Review(0,"submit"));
        draft=service.reviewConfig(2,ROLES,draft.versionId(),"APPROVE",new Review(draft.revision(),"approve"));String id=draft.versionId();long rev=draft.revision();
        assertThatThrownBy(() -> service.reviewConfig(2,ROLES,id,"ACTIVATE",new Review(rev,"activate"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reviewConfig(1,ROLES,active.versionId(),"SUBMIT",new Review(0,"stale"))).isInstanceOf(BusinessException.class);
        metadata.get("cold").setUnit("kW");assertThatThrownBy(() -> service.createConfig(1,ROLES,c)).isInstanceOf(BusinessException.class);
        metadata.get("cold").setUnit("kWh");
        var bad=new Configuration(B,S,"boundary","rel1","UTC","tz1",START,END,c.equipment(),c.coolingSources(),
                List.of(new ElectricitySource("e1",c.electricitySources().getFirst().meter(),Set.of("chiller"),"explicit")),"test","rule1","attachment");
        assertThatThrownBy(() -> service.createConfig(1,ROLES,bad)).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo("EERP_COVERAGE_INCOMPLETE"));
    }
    @Test void resourceBudgetsAndDynamicDutyRevocationFailClosed() {
        var config=activate(configuration(false));baselineHour();
        assertThatThrownBy(() -> period(config,START,START.plusSeconds(86401),"too-long")).isInstanceOf(BusinessException.class);
        doThrow(new BusinessException(403,"duty revoked")).when(duties).requireDuty(1,BackendDuty.ENERGY_CALCULATION_RUN);
        assertThatThrownBy(() -> period(config,START,START.plusSeconds(3600),"revoked")).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM energy_eerp_task",Integer.class)).isZero();
        doThrow(new BusinessException(403,"scope denied")).when(scope).checkAccess(9L,ROLES,B);
        assertThatThrownBy(() -> service.config(9,ROLES,config.versionId())).isInstanceOf(BusinessException.class);
    }
    @Test void leaseCapacityAndExpiredWorkerCannotOverwriteNewOwner() {
        var config=activate(configuration(false));baselineHour();var done=period(config,START,START.plusSeconds(3600),"lease");
        jdbc.update("UPDATE energy_eerp_task SET status='RUNNING',lease_token='old',lease_until=? WHERE task_id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(600)),done.taskId());
        assertThatThrownBy(() -> service.execute(1,ROLES,done.taskId())).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE energy_eerp_task SET lease_until=? WHERE task_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(10)),done.taskId());
        assertThat(service.execute(1,ROLES,done.taskId()).status()).isEqualTo("SUCCEEDED");
        assertThat(repo.finish(done.taskId(),"old","SUCCEEDED",null)).isZero();
    }
    @Test void auditFailureRollsBackConfigurationCreation() {
        var c=configuration(false);doThrow(new IllegalStateException("audit unavailable")).when(audit).append(any());
        assertThatThrownBy(() -> service.createConfig(1,ROLES,c)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM energy_eerp_config",Integer.class)).isZero();
    }
    @Test void sourceSwitchUsesExactVersionBoundaryAndRejectsCrossingOrDuplicatedCoverage() {
        var base=configuration(false); Instant cut=START.plusSeconds(3600);
        var first=activate(interval(base,START,cut));
        var second=activate(interval(configuration(true),cut,cut.plusSeconds(3600)));
        baselineHour();sample("electric",cut.plusSeconds(3600),210);
        for(Instant at:List.of(cut,cut.plusSeconds(3600))){sample("flow",at,0);sample("supply",at,7);sample("return",at,12);}
        var p1=seal(period(first,START,cut,"before-switch"));var p2=seal(period(second,cut,cut.plusSeconds(3600),"after-switch"));
        assertThat(((PeriodResult)p2.result()).complete()).isTrue();
        assertThat(((PeriodResult)p2.result()).coolingKwh()).isEqualByComparingTo("0");
        assertThat(((PeriodResult)p2.result()).electricityKwh()).isEqualByComparingTo("100");
        assertThatThrownBy(() -> period(first,START,cut.plusMillis(1),"cross-config")).isInstanceOf(BusinessException.class);
        var annual=service.createAnnual(1,ROLES,new AnnualRequest("switch-year",B,S,2025,"UTC","tz1",List.of(p1.taskId(),p2.taskId()),null));
        var result=(AnnualResult)annual.result();assertThat(result.eerp()).isNull();assertThat(result.coolingKwh()).isEqualByComparingTo("500");
        assertThat(result.electricityKwh()).isEqualByComparingTo("200");assertThat(result.issues()).extracting(Issue::code).contains("PERIOD_GAP");
        var overlap=new Configuration(B,S,"boundary","rel1","UTC","tz1",START,END,base.equipment(),
                List.of(base.coolingSources().getFirst(),new CoolingSource("duplicate",SourceMode.METER_CUMULATIVE,Set.of("chiller"),"water1","PRODUCTION_OUTLET",null,
                        new Point("other","ACCUMULATE","kWh"),null,null,null,null,"duplicate physical coverage")),base.electricitySources(),"test","rule","attachment");
        assertThatThrownBy(() -> service.createConfig(1,ROLES,overlap)).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo("EERP_COVERAGE_OVERLAP"));
    }
    @Test void capacityOverflowAndImmutableNativeSnapshotConflictDoNotExposeSuccess() {
        var c=activate(configuration(false));baselineHour();limits.setMaximumEvidenceBytes(1024);
        var failed=period(c,START,START.plusSeconds(3600),"capacity");
        assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.failureCode()).isEqualTo("EERP_CAPACITY_EXCEEDED");
        assertThat(failed.result()).isNull();assertThat(repo.task(failed.taskId()).stage()).isNull();verifyNoInteractions(values);
        limits.setMaximumEvidenceBytes(8388608);var done=service.execute(1,ROLES,failed.taskId());assertThat(done.status()).isEqualTo("SUCCEEDED");
        var r=(PeriodResult)done.result();String snapshotId=r.measures().getFirst().numericSnapshotId();
        var original=snapshots.read(1,ROLES,B,snapshotId);
        var altered=new NativePeriodSnapshotService.Snapshot(snapshotId,B,original.sourceId(),original.quantityType(),original.unitCode(),original.evidenceHash(),
                List.of(new NativePeriodSnapshotService.NumericSample(START,BigDecimal.TEN,BigDecimal.ONE)));
        assertThatThrownBy(() -> snapshots.publish(1,ROLES,altered)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshots.read(1,ROLES,B,snapshotId)).isEqualTo(original);
    }
    @Test void concurrentExecutionCapIsCheckedUnderDatabaseGuard() {
        var c=activate(configuration(false));baselineHour();var a=period(c,START,START.plusSeconds(3600),"busy-a");var b=period(c,START,START.plusSeconds(3600),"busy-b");
        jdbc.update("UPDATE energy_eerp_task SET status='RUNNING',lease_token='busy',lease_until=? WHERE task_id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(60)),a.taskId());
        jdbc.update("UPDATE energy_eerp_task SET status='FAILED' WHERE task_id=?",b.taskId());limits.setMaximumConcurrentTasks(1);
        assertThatThrownBy(() -> service.execute(1,ROLES,b.taskId())).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo("EERP_CAPACITY_EXCEEDED"));
        assertThat(repo.task(b.taskId()).status()).isEqualTo("FAILED");
    }
    @Test void expiredLeaseDoesNotCreateAnotherLiveWorkerWhileStoreCallIsBlocked() throws Exception {
        limits.setMaximumConcurrentTasks(1);service=newService();var c=activate(configuration(false));baselineHour();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(i -> {entered.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test store wait expired");return null;}).when(values).write(any());
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result=executor.submit(() -> period(c,START,START.plusSeconds(3600),"live-worker"));
            assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var running=repo.byKey(B,S,"live-worker");
            jdbc.update("UPDATE energy_eerp_task SET lease_until=? WHERE task_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),running.id());
            assertThatThrownBy(() -> service.execute(1,ROLES,running.id())).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo("EERP_CAPACITY_EXCEEDED"));
            release.countDown();assertThat(result.get(5,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void numericProjectionPreservesExactEvidenceAndFitsExistingTdengineCoverageColumn() {
        var coverage=BigDecimal.ONE.divide(new BigDecimal("3"),java.math.MathContext.DECIMAL128);
        var snapshot=new NativePeriodSnapshotService.Snapshot("fractional",B,"derived-source","COOLING_ENERGY","kWh","b".repeat(64),
                List.of(new NativePeriodSnapshotService.NumericSample(START,new BigDecimal("10.125"),coverage)));
        snapshots.publish(1,ROLES,snapshot);
        verify(values).write(argThat(v -> v.pointId().startsWith("NATIVE:")&&v.pointId().length()==32
                &&v.coverageRatio().toPlainString().length()<=24&&v.nativeQuantity().compareTo(new BigDecimal("10.125"))==0));
        assertThat(snapshots.read(1,ROLES,B,"fractional").samples().getFirst().coverageRatio()).isEqualByComparingTo(coverage);
    }
    static Configuration interval(Configuration c,Instant from,Instant to) {return new Configuration(c.buildingId(),c.stationId(),c.boundaryId(),c.relationVersionId(),c.timezoneId(),c.timezoneVersion(),from,to,c.equipment(),c.coolingSources(),c.electricitySources(),c.professionalEvidence(),c.evaluationRuleVersion(),c.evaluationReference());}
    ConfigView activate(Configuration c) {var v=service.createConfig(1,ROLES,c);v=service.reviewConfig(1,ROLES,v.versionId(),"SUBMIT",new Review(v.revision(),"synthetic submit"));v=service.reviewConfig(2,ROLES,v.versionId(),"APPROVE",new Review(v.revision(),"synthetic reviewed"));return service.reviewConfig(2,ROLES,v.versionId(),"ACTIVATE",new Review(v.revision(),"synthetic activation"));}
    TaskView period(ConfigView c,Instant from,Instant to,String key){return service.createPeriod(1,ROLES,new PeriodRequest(key,c.versionId(),from,to,END,null));}
    TaskView seal(TaskView task){var v=service.reviewTask(1,ROLES,task.taskId(),"SUBMIT_SEAL",new Review(task.revision(),"submit immutable result"));return service.reviewTask(2,ROLES,v.taskId(),"APPROVE_SEAL",new Review(v.revision(),"review immutable result"));}
    void baselineHour(){sample("cold",START,100);sample("cold",START.plusSeconds(3600),600);sample("electric",START,10);sample("electric",START.plusSeconds(3600),110);}
    void sample(String point,Instant at,double value){facts.add(new RawEvent(point,point,B,"SYNTHETIC",point,"device",value,at.toEpochMilli(),END.toEpochMilli(),0,false));}
    Point point(String id,String type,String unit){var p=new BizDataPoint();p.setPointId(id);p.setPointCode(id);p.setBuildingId(B);p.setSystemGroupId(S);p.setDataType(type);p.setUnit(unit);p.setIsForCalc(1);metadata.put(id,p);return new Point(id,type,unit);}
    Configuration configuration(boolean flow){
        var devices=List.of(new Equipment("chiller",EquipmentRole.CHILLER),new Equipment("chilled-pump",EquipmentRole.CHILLED_WATER_PUMP),new Equipment("cooling-pump",EquipmentRole.COOLING_WATER_PUMP),new Equipment("tower",EquipmentRole.TOWER_FAN));
        var inventory=devices.stream().map(e -> {var d=new BizEquipment();d.setEquipId(e.deviceId());d.setBuildingId(B);d.setSystemGroupId(S);d.setTypeCode(e.role()==EquipmentRole.CHILLER?"WCR":"WCP");d.setEquipCategory(e.role()==EquipmentRole.CHILLER?"CHILLER":e.role()==EquipmentRole.TOWER_FAN?"TOWER":"PUMP");return d;}).toList();
        var page=new Page<BizEquipment>(1,100);page.setTotal(inventory.size());page.setRecords(inventory);
        when(equipmentService.list(anyInt(),anyInt(),eq(B),isNull(),isNull(),eq(Set.of(B)))).thenReturn(Result.success(page));
        CoolingSource source;
        if(flow)source=new CoolingSource("cold-source",SourceMode.FLOW_TEMPERATURE,Set.of("chiller"),"water1","PRODUCTION_OUTLET","SUPPLY",null,
                point("flow","ANALOG","m³/h"),point("supply","ANALOG","℃"),point("return","ANALOG","℃"),
                new CoolingComputationCore.Rules("water1","synthetic reviewed",new BigDecimal("1000"),new BigDecimal("4.18"),BigDecimal.ZERO,new BigDecimal("40"),3600000,0,3600000,true,true,CoolingComputationCore.AlignmentMode.EXACT_SYNCHRONOUS),"synthetic water circuit");
        else source=new CoolingSource("cold-source",SourceMode.METER_CUMULATIVE,Set.of("chiller"),"water1","PRODUCTION_OUTLET",null,point("cold","ACCUMULATE","kWh"),null,null,null,null,"synthetic meter mapping");
        return new Configuration(B,S,"boundary","rel1","UTC","tz1",START,END,devices,List.of(source),
                List.of(new ElectricitySource("electric-source",point("electric","ACCUMULATE","kWh"),Set.of("chiller","chilled-pump","cooling-pump","tower"),"exclusive synthetic system meter")),
                "synthetic expert fixture","attachment-year-v1","expert attachment: annual 4.0/5.0, strict greater-than");
    }
    void stubRelations(){
        QueryMetadata meta=new QueryMetadata(B,"rel1",1,LocalDateTime.of(2024,1,1,0,0),1,1,false,0,0,0,0);
        VersionView version=new VersionView("rel1","model",B,1,null,null,"EFFECTIVE",1,1L,"snapshot-hash","fixture",1L,1L,2L,2L,LocalDateTime.of(2024,1,1,0,0),LocalDateTime.of(2024,1,1,0,0));
        when(relations.versionDetail(anyLong(),anyCollection(),eq(B),eq("rel1"))).thenReturn(new VersionDetailView(meta,version,new SnapshotCounts(0,4,1,1,1,1,0),List.of()));
        var assignment=new MeteringAssignmentView("a1","ASSIGNED",null,null,"synthetic", "boundary","boundary","station electricity","ELECTRICITY","CONFIRMED","ACTIVE","n-electric","electric","electric","electric","MAIN","IMPORT","CONFIRMED","n-station","SYSTEM",S,S,S);
        when(relations.historicalMeteringAssignments(anyLong(),anyCollection(),eq(B),eq("rel1"),anyInt(),anyInt())).thenReturn(new MeteringAssignmentsView(meta,1,100,1,List.of(assignment)));
        when(relations.historicalNodeContext(anyLong(),anyCollection(),eq(B),eq("rel1"),anyString(),anyString(),eq(1),eq(1),eq(100))).thenAnswer(i -> {
            String type=i.getArgument(4),id=i.getArgument(5);var edges="POINT".equals(type)?List.of(new RelationEdgeView("edge-"+id,"MEASURES","n-"+id,"n-chiller","CONFIRMED","synthetic")):List.<RelationEdgeView>of();
            return new NodeContextView(meta,"n-"+id,type,id,1,100,edges.size(),edges);
        });
    }
}
