package com.platform.energy.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.carbon.DynamicElectricityRepository;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.energy.period.EnergyPeriodSnapshotReader;
import com.platform.framework.exception.BusinessException;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.RelationGovernanceProperties;
import com.platform.relation.api.RelationContracts.QueryMetadata;
import com.platform.relation.api.RelationContracts.HistoricalMeteringScopeView;
import com.platform.relation.api.RelationContracts.MeteringAssignmentView;
import com.platform.relation.api.RelationContracts.MeteringAssignmentsView;
import com.platform.relation.api.RelationContracts.MeterStructureView;
import com.platform.relation.api.RelationContracts.MeterStructuresView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.energy.classification.ClassificationContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离H2执行真实新增DDL与事务；关系、目录及封账读取均为替身，不访问现场资源。 */
class ManualReadingRefinementTest {
    private static final String BUILDING="BLD_TEST";
    private static final Set<String> ROLES=Set.of("ENERGY_MANAGER");
    private static final Instant FROM=Instant.parse("2026-07-31T16:00:00Z"),TO=Instant.parse("2026-08-31T16:00:00Z");
    private ClassificationService service;
    private ClassificationRepository repository;
    private JdbcTemplate jdbc;
    private AuditEvidenceWriter audit;
    private EnergyPeriodAuthorization authorization;
    private RelationGovernanceService relations;
    private EnergyCatalogService catalog;
    private EnergyPeriodSnapshotReader snapshots;
    private ObjectMapper mapper;
    private RelationGovernanceProperties relationProperties;

    @org.junit.jupiter.api.AfterEach
    void closeFixtureDatabase() {
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE building(building_id VARCHAR(32) PRIMARY KEY,del_flag INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE biz_data_point(point_id VARCHAR(80) PRIMARY KEY,building_id VARCHAR(32),equip_id VARCHAR(80),del_flag INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE biz_equipment(equip_id VARCHAR(80) PRIMARY KEY,building_id VARCHAR(32),del_flag INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE biz_space(space_id VARCHAR(80) PRIMARY KEY,building_id VARCHAR(32),del_flag INT DEFAULT 0)");
        jdbc.update("INSERT INTO building(building_id) VALUES (?)",BUILDING);
        for(String id:List.of("A","B","C","D")) jdbc.update("INSERT INTO biz_data_point(point_id,building_id) VALUES (?,?)",id,BUILDING);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/mysql/V75__mysql_energy_classification.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/mysql/V99__mysql_monthly_reading_query_indexes.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/mysql/V97__mysql_hourly_carbon.sql")).execute(ds);
        repository=new ClassificationRepository(jdbc);mapper=new ObjectMapper().findAndRegisterModules();authorization=mock(EnergyPeriodAuthorization.class);
        audit=mock(AuditEvidenceWriter.class);relations=mock(RelationGovernanceService.class);catalog=mock(EnergyCatalogService.class);
        doAnswer(call->{if(Objects.equals(call.getArgument(0),call.getArgument(1))) throw new BusinessException(403,"职责分离");return null;}).when(authorization).requireSeparation(anyLong(),anyLong());
        snapshots=mock(EnergyPeriodSnapshotReader.class);
        when(snapshots.listVisibleSnapshots(anyString(),anyString(),any(),any(),anyInt())).thenReturn(List.of());
        QueryMetadata meta=new QueryMetadata(BUILDING,"R1",1,LocalDateTime.of(2026,1,1,0,0),1,0,false,0,0,0,0);
        List<MeteringAssignmentView> assignments=new ArrayList<>();List<MeterStructureView> structures=new ArrayList<>();
        for(String id:List.of("A","B","C","D")) {
            String role=id.equals("A")?"MAIN":"SUB",parent=switch(id){case "A"->null;case "C"->"B";default->"A";};
            assignments.add(new MeteringAssignmentView("assign-"+id,"UNASSIGNED","UNKNOWN","unknown","test","BOUNDARY","bd","范围","ELECTRICITY","CONFIRMED","ACTIVE","node-"+id,id,id,id,role,"INBOUND","CONFIRMED",null,null,null,null,null));
            structures.add(new MeterStructureView("s-"+id,"BOUNDARY","node-"+id,id,role,parent==null?null:"node-"+parent,parent,"INBOUND","CONFIRMED",null,null,"test",null,"MANUAL"));
            when(catalog.effectiveBinding(anyLong(),anyCollection(),eq(BUILDING),eq(id),any())).thenReturn(new BindingVersionView("binding-"+id,"binding-v1",1,BUILDING,id,id,"kWh","ELECTRICITY","item-v1",LocalDateTime.of(2026,1,1,0,0),null,"CONFIRMED","test",0,1L,null,2L,null));
        }
        when(relations.historicalMeteringAssignments(anyLong(),anyCollection(),eq(BUILDING),eq("R1"),eq(1),eq(100))).thenReturn(new MeteringAssignmentsView(meta,1,100,4,assignments));
        when(relations.effectiveMeterStructures(anyLong(),anyCollection(),eq(BUILDING),eq(1),eq(100))).thenReturn(new MeterStructuresView(meta,1,100,4,structures));
        relationProperties=new RelationGovernanceProperties();
        var target=new ClassificationService(repository,authorization,relations,snapshots,new ClassificationCore(),mapper,audit,new AuditGovernanceProperties(),catalog,relationProperties);
        ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(ClassificationService)factory.getProxy();
    }
    private Configuration config(String relation) {return new Configuration(relation,List.of(new Scheme("use","用途","USAGE","ADDITIVE")),
            List.of(new Node("use","hvac","空调",null),new Node("use","light","照明",null)),
            List.of(new Binding("use","hvac","POINT","B",null,"METERING","CONFIRMED","test"),new Binding("use","light","POINT","D",null,"METERING","CONFIRMED","test")),
            List.of(new BoundaryRule("BOUNDARY","MAIN_WITH_SUBMETER_BREAKDOWN","CONFIRMED","test","ELECTRICITY","kWh")));}
    private VersionView draft(String rel) {
        var v=service.create(1,ROLES,BUILDING,new CreateRequest("test",null));
        return service.save(1,ROLES,BUILDING,v.versionId(),new SaveRequest(v.revision(),config(rel),"test"));
    }
    private VersionView approved(String rel) {
        var v=draft(rel);v=service.submit(1,ROLES,BUILDING,v.versionId(),new TransitionRequest(v.revision(),"test",null));
        return service.approve(2,ROLES,BUILDING,v.versionId(),new TransitionRequest(v.revision(),"test",FROM));
    }
    private Reading reading(String point,String qty,String supersedes) {return new Reading(point,FROM,TO,"ELECTRICITY","kWh","PERIOD_TOTAL",null,null,new BigDecimal(qty),BigDecimal.ONE,true,"CONFIRMED","test source","EXACT",supersedes);}
    private ImportRequest request(String key,Reading... r){return new ImportRequest(key,List.of(r),"test reason");}
    private ImportView importApproved(String key,Reading... r){var imported=service.importReadings(1,ROLES,BUILDING,request(key,r));return service.approveReadings(2,ROLES,BUILDING,imported.batchId(),new TransitionRequest(0L,"reviewed",null));}
    private QueryRequest query(String version){return new QueryRequest(FROM,TO,"MONTH","ELECTRICITY","kWh","use",version,List.of());}

    private Instant start(YearMonth month) { return month.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); }
    private Reading total(String point, Instant from, Instant to, String quantity, String precision, String parent, String mode) {
        return new Reading(point,from,to,"ELECTRICITY","kWh","PERIOD_TOTAL",null,null,new BigDecimal(quantity),BigDecimal.ONE,true,"CONFIRMED","test evidence",precision,parent,mode);
    }
    private String parent(YearMonth month, String precision) {
        importApproved("parent-"+month,total("A",start(month),start(month.plusMonths(1)),String.valueOf(month.lengthOfMonth()),precision,null,null));
        return repository.currentApprovedReadings(BUILDING,"A",start(month),start(month.plusMonths(1)),100).getFirst().id();
    }
    private List<Reading> days(YearMonth month,String parent,String precision) {
        List<Reading> rows=new ArrayList<>();
        for(int i=0;i<month.lengthOfMonth();i++) rows.add(total("A",start(month).plus(Duration.ofDays(i)),start(month).plus(Duration.ofDays(i+1)),"1",precision,parent,"MONTH_TO_DAY"));
        return rows;
    }
    private ImportView pending(String key,List<Reading> rows) { return service.importReadings(1,ROLES,BUILDING,new ImportRequest(key,rows,"refinement evidence")); }
    private void approve(ImportView batch) { assertThat(batch.batchId()).isNotNull(); service.approveReadings(2,ROLES,BUILDING,batch.batchId(),new TransitionRequest(0L,"review evidence",null)); }
    private void rejected(List<Reading> rows) {
        var batch=pending("invalid-"+UUID.randomUUID(),rows);
        if(batch.batchId()==null) assertThat(batch.status()).isEqualTo("INVALID");
        else {
            assertThatThrownBy(()->approve(batch)).isInstanceOf(BusinessException.class);
            assertThat(repository.batch(BUILDING,batch.batchId(),false).status()).isEqualTo("PENDING_REVIEW");
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2026-02,EXACT","2024-02,DATE_ONLY","2026-04,EXACT","2026-08,DATE_ONLY"})
    void refinesEveryNaturalMonthLengthAndRetainsPrecision(String value,String precision) throws Exception {
        var month=YearMonth.parse(value);var id=parent(month,precision);var batch=pending("days",days(month,id,precision));
        assertThat(repository.readings(BUILDING,start(month),start(month.plusMonths(1)),true)).extracting(ClassificationRepository.StoredReading::id).containsExactly(id);
        approve(batch);
        var current=repository.readings(BUILDING,start(month),start(month.plusMonths(1)),true);
        assertThat(current).hasSize(month.lengthOfMonth());
        assertThat(current.stream().map(ClassificationRepository.StoredReading::quantity).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(String.valueOf(month.lengthOfMonth()));
        for(var row:current) {
            var reading=mapper.readValue(row.json(),Reading.class);
            assertThat(reading.timePrecision()).isEqualTo(precision);
            assertThat(reading.supersedesFactId()).isEqualTo(id);
            assertThat(reading.refinementMode()).isEqualTo("MONTH_TO_DAY");
        }
        assertThat(repository.approvedReading(BUILDING,id)).isNotNull();
        assertThat(repository.readings(BUILDING,start(month),start(month.plusMonths(1)),false)).hasSize(month.lengthOfMonth()+1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing","overlap","off-boundary","sum","status","energy","unit","point","precision","mode","ordinary-bypass"})
    void invalidRefinementCannotReplaceParent(String fault) {
        var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");var rows=days(month,id,"EXACT");var r=rows.getFirst();
        switch(fault) {
            case "missing" -> rows.removeLast();
            case "overlap" -> rows.set(1,r);
            case "off-boundary" -> rows.set(0,total("A",r.startInclusive().plusSeconds(1),r.endExclusive(),"1","EXACT",id,"MONTH_TO_DAY"));
            case "sum" -> rows.set(0,total("A",r.startInclusive(),r.endExclusive(),"2","EXACT",id,"MONTH_TO_DAY"));
            default -> rows.set(0,new Reading(fault.equals("point")?"B":"A",r.startInclusive(),r.endExclusive(),fault.equals("energy")?"WATER":"ELECTRICITY",fault.equals("unit")?"MWh":"kWh","PERIOD_TOTAL",null,null,BigDecimal.ONE,BigDecimal.ONE,true,fault.equals("status")?"PENDING_EXPERT":"CONFIRMED","evidence",fault.equals("precision")?"DATE_ONLY":"EXACT",id,fault.equals("mode")?"UNSUPPORTED":fault.equals("ordinary-bypass")?null:"MONTH_TO_DAY"));
        }
        rejected(rows);
        assertThat(repository.readings(BUILDING,FROM,TO,true)).extracting(ClassificationRepository.StoredReading::id).containsExactly(id);
    }
    @Test void partialGroupsCannotBeApprovedAcrossSeparateBatches() {
        var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");var rows=days(month,id,"EXACT");
        rejected(new ArrayList<>(rows.subList(0,15)));rejected(new ArrayList<>(rows.subList(15,31)));
        assertThat(repository.readings(BUILDING,FROM,TO,true)).extracting(ClassificationRepository.StoredReading::id).containsExactly(id);
    }
    @Test void ordinaryDaySupersessionCannotBypassTheMonthRefinementContract() {
        var id=parent(YearMonth.of(2026,8),"EXACT");
        rejected(List.of(total("A",FROM,FROM.plus(Duration.ofDays(1)),"31","EXACT",id,null)));
        assertThat(repository.readings(BUILDING,FROM,TO,true)).extracting(ClassificationRepository.StoredReading::id).containsExactly(id);
    }
    @Test void auditFailureRollsBackTheEntireRefinementAndAllowsRetry() {
        var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");var batch=pending("days",days(month,id,"EXACT"));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).append(any());
        assertThatThrownBy(()->approve(batch)).hasMessageContaining("audit unavailable");
        assertThat(repository.batch(BUILDING,batch.batchId(),false).status()).isEqualTo("PENDING_REVIEW");
        assertThat(repository.batch(BUILDING,batch.batchId(),false).revision()).isZero();
        assertThat(repository.readings(BUILDING,FROM,TO,true)).extracting(ClassificationRepository.StoredReading::id).containsExactly(id);
        doNothing().when(audit).append(any());approve(batch);
        assertThat(repository.readings(BUILDING,FROM,TO,true)).hasSize(31);
    }
    @Test void sameAuthorAndWrongBuildingCannotApproveRefinement() {
        var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");var batch=pending("days",days(month,id,"EXACT"));
        assertThatThrownBy(()->service.approveReadings(1,ROLES,BUILDING,batch.batchId(),new TransitionRequest(0L,"review",null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.approveReadings(2,ROLES,"OTHER",batch.batchId(),new TransitionRequest(0L,"review",null))).isInstanceOf(BusinessException.class);
        assertThat(repository.batch(BUILDING,batch.batchId(),false).status()).isEqualTo("PENDING_REVIEW");
        assertThat(repository.approvedReading("OTHER",id)).isNull();
        approve(batch);
        assertThatThrownBy(()->approve(batch)).isInstanceOf(BusinessException.class);
        assertThat(repository.batch(BUILDING,batch.batchId(),false).revision()).isEqualTo(1);
    }
    @Test void competingCompleteBatchesPublishOnlyOneWholeGroup() throws Exception {
        var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");
        var a=pending("first",days(month,id,"EXACT"));var b=pending("second",days(month,id,"EXACT"));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CyclicBarrier(2);
            java.util.function.Function<ImportView,java.util.concurrent.Callable<String>> task=batch->()->{
                gate.await(5,java.util.concurrent.TimeUnit.SECONDS);
                try {approve(batch);return batch.batchId();} catch(BusinessException e) {assertThat(e.getCode()).isEqualTo(409);return "CONFLICT";}
            };
            var first=executor.submit(task.apply(a));var second=executor.submit(task.apply(b));
            assertThat(List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS))).filteredOn(value->!value.equals("CONFLICT")).hasSize(1);
        }
        var current=repository.readings(BUILDING,FROM,TO,true);
        assertThat(current).hasSize(31);
        assertThat(current.stream().map(ClassificationRepository.StoredReading::batchId).distinct()).hasSize(1);
    }
    @Test void reviewCutoffsPagingAndDynamicVersionSwitchTogether() {
        var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");var parentBatch=repository.approvedReading(BUILDING,id).batchId();
        var sources=new DynamicElectricityRepository(jdbc,mapper);var before=sources.electricityVersion(BUILDING,FROM,TO,"REVIEWED_INTERVAL");
        var batch=pending("days",days(month,id,"EXACT"));
        assertThat(sources.electricityVersion(BUILDING,FROM,TO,"REVIEWED_INTERVAL")).isEqualTo(before);
        approve(batch);
        assertThat(sources.electricityVersion(BUILDING,FROM,TO,"REVIEWED_INTERVAL")).isNotEqualTo(before);
        var cutoff=Instant.parse("2026-10-01T00:00:00Z");
        jdbc.update("UPDATE biz_energy_manual_batch SET created_at=?,reviewed_at=? WHERE batch_id=?",cutoff.minusSeconds(60).toEpochMilli(),cutoff.minusSeconds(30).toEpochMilli(),parentBatch);
        jdbc.update("UPDATE biz_energy_manual_batch SET created_at=?,reviewed_at=? WHERE batch_id=?",cutoff.minusSeconds(10).toEpochMilli(),cutoff.plusSeconds(30).toEpochMilli(),batch.batchId());
        assertThat(repository.approvedReadingsAsOf(BUILDING,FROM,TO,cutoff,100)).extracting(ClassificationRepository.StoredReading::id).containsExactly(id);
        assertThat(repository.approvedReadingsAsOf(BUILDING,FROM,TO,cutoff.plusSeconds(60),100)).hasSize(31);
        List<ClassificationRepository.StoredReading> all=new ArrayList<>();Instant cursor=null;String fact=null;
        while(true) {
            var page=repository.currentApprovedReadingsPage(BUILDING,FROM,TO,cursor,fact,7);all.addAll(page);
            if(page.size()<7) break;
            cursor=page.getLast().startInclusive();fact=page.getLast().id();
        }
        assertThat(all).hasSize(31);assertThat(all.stream().map(ClassificationRepository.StoredReading::id).distinct()).hasSize(31);
        assertThat(all).allSatisfy(row->assertThat(row.batchId()).isEqualTo(batch.batchId()));
    }
    @Test void legacyJsonAndIdempotencyHashRemainByteCompatible() throws Exception {
        var old=reading("A","31",null);
        // Frozen pre-refinement wire format, independent of the current record serializer.
        String oldJson="{\"pointId\":\"A\",\"startInclusive\":1785513600.000000000,\"endExclusive\":1788192000.000000000,\"energyItemCode\":\"ELECTRICITY\",\"unit\":\"kWh\",\"semantics\":\"PERIOD_TOTAL\",\"startReading\":null,\"endReading\":null,\"quantity\":\"31\",\"multiplier\":\"1\",\"alreadyScaled\":true,\"status\":\"CONFIRMED\",\"sourceReference\":\"test source\",\"timePrecision\":\"EXACT\",\"supersedesFactId\":null}";
        String oldRequest="{\"idempotencyKey\":\"legacy\",\"readings\":["+oldJson+"],\"reason\":\"test reason\"}";
        assertThat(mapper.writeValueAsString(old)).isEqualTo(oldJson).doesNotContain("refinementMode");
        assertThat(mapper.readValue(oldJson,Reading.class).refinementMode()).isNull();
        var request=request("legacy",old);
        assertThat(mapper.writeValueAsString(request)).isEqualTo(oldRequest);
        var batch=service.importReadings(1,ROLES,BUILDING,request);
        assertThat(repository.batch(BUILDING,batch.batchId(),false).hash()).isEqualTo("c11c77cad907657e5274ab51c06f20cf8b86e271f17012cc69e0793feb7a7428");
        assertThat(service.importReadings(1,ROLES,BUILDING,request).batchId()).isEqualTo(batch.batchId());
    }
    private com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot snapshot(String parent, String quantity) {
        String evidence="{\"inputKind\":\"REVIEWED_MANUAL_MONTH\",\"relationVersionId\":\"R1\",\"request\":{\"factIds\":[\""+parent+"\"]}}";
        return new com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot("LOCKED_MAIN","locked-main","projection",BUILDING,"A","MONTH",FROM,TO,
                "Asia/Shanghai","period",1,"LOCKED_COMPLETE","FORMAL","ELECTRICITY",new BigDecimal(quantity),"kWh",BigDecimal.ZERO,"TCE",BigDecimal.ONE,
                "","",evidence,"hash","{}",TO,null,null,1,LocalDateTime.of(2026,9,2,0,0));
    }
    @Test void sealedMonthDeduplicatesDirectRefinementButRejectsLaterDayCorrection() throws Exception {
        var version=approved("R1");var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");
        when(snapshots.listVisibleSnapshots(BUILDING,"MONTH",FROM,TO,5001)).thenReturn(List.of(snapshot(id,"31")));
        assertThat(service.query(1,ROLES,BUILDING,query(version.versionId())).total()).isEqualByComparingTo("31");
        approve(pending("days",days(month,id,"EXACT")));
        var result=service.query(1,ROLES,BUILDING,query(version.versionId()));
        assertThat(result.total()).isEqualByComparingTo("31");
        assertThat(result.evidenceIds()).contains("LOCKED_MAIN").doesNotContain(id);
        var first=repository.currentApprovedReadings(BUILDING,"A",FROM,FROM.plus(Duration.ofDays(1)),100).getFirst();
        importApproved("day-correction",total("A",FROM,FROM.plus(Duration.ofDays(1)),"2","EXACT",first.id(),null));
        assertThatThrownBy(()->service.query(1,ROLES,BUILDING,query(version.versionId())))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getCode()).isEqualTo(409));
        var day=new QueryRequest(FROM,FROM.plus(Duration.ofDays(1)),"DAY","ELECTRICITY","kWh","use",version.versionId(),List.of());
        assertThat(service.query(1,ROLES,BUILDING,day).total()).isEqualByComparingTo("2");
    }
    @Test void snapshotQuantityMismatchCannotSilentlySuppressCurrentDays() {
        var version=approved("R1");var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");approve(pending("days",days(month,id,"EXACT")));
        when(snapshots.listVisibleSnapshots(BUILDING,"MONTH",FROM,TO,5001)).thenReturn(List.of(snapshot(id,"32")));
        assertThatThrownBy(()->service.query(1,ROLES,BUILDING,query(version.versionId())))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getCode()).isEqualTo(409));
    }
    @Test void normalizedRatherThanRawDayQuantitiesMustMatchTheParent() {
        var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");var rows=days(month,id,"EXACT");
        rows.replaceAll(r->new Reading(r.pointId(),r.startInclusive(),r.endExclusive(),r.energyItemCode(),r.unit(),r.semantics(),null,null,new BigDecimal("0.5"),new BigDecimal("2"),false,r.status(),r.sourceReference(),r.timePrecision(),r.supersedesFactId(),r.refinementMode()));
        approve(pending("scaled-days",rows));
        assertThat(repository.readings(BUILDING,FROM,TO,true)).hasSize(31).allSatisfy(row->assertThat(row.quantity()).isEqualByComparingTo("1"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"EXACT","DATE_ONLY"})
    void previouslyPreparedFormalMonthCannotLockAfterItsParentWasRefined(String childPrecision) {
        var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");
        var policies=mock(com.platform.energy.period.EnergyPeriodGovernanceService.class);
        var effective=LocalDateTime.of(2026,1,1,0,0);
        when(policies.periodPolicyVersion("POLICY-1")).thenReturn(new com.platform.energy.period.EnergyPeriodModels.PeriodPolicyVersion(
                "POLICY","POLICY-1",1,BUILDING,"Asia/Shanghai",0,"REVIEW_REQUIRED","APPROVED","FORMAL","evidence",effective,null,1,1L,effective,2L,effective));
        var relation=new com.platform.relation.api.RelationContracts.VersionView("R1","MODEL",BUILDING,1,null,null,"EFFECTIVE",1L,1L,"hash","approved",1L,1L,2L,2L,effective,effective);
        var metadata=new QueryMetadata(BUILDING,"R1",1,effective,1L,0,false,0,0,0,0);
        when(relations.versionDetail(1L,ROLES,BUILDING,"R1")).thenReturn(new com.platform.relation.api.RelationContracts.VersionDetailView(metadata,relation,new com.platform.relation.api.RelationContracts.SnapshotCounts(0,0,0,0,0,0,0),List.of()));
        when(relations.model(1L,ROLES,BUILDING)).thenReturn(new com.platform.relation.api.RelationContracts.ModelView("MODEL",BUILDING,"BUILDING","FORMAL","R1",null,1L));
        var formal=new com.platform.energy.period.ManualPeriodInputService(authorization,repository,catalog,policies,relations,mapper);
        var request=new com.platform.energy.period.ManualPeriodInputService.Request("A",month.atDay(1),"POLICY-1","R1",List.of(id),0L,"formal month");
        var value=formal.prepare(1,ROLES,BUILDING,request);
        var current=new com.platform.energy.period.EnergyPeriodModels.CurrentProjection("CURRENT","RESULT",BUILDING,"A","MONTH",
                value.window().startInclusive(),value.window().endExclusive(),value.window().timezoneId(),value.periodPolicyVersionId(),"PROVISIONAL",1L,
                value.resultNature(),value.energyItemCode(),value.nativeQuantity(),value.nativeUnitCode(),value.tce(),value.tceUnitCode(),value.coverageRatio(),
                "",value.evidenceJson(),value.evidenceHash(),null,value.activityWatermark(),value.calculatedAt(),value.calculatedAt());
        formal.verifyStored(1,ROLES,current);
        approve(pending("days",days(month,id,childPrecision)));
        assertThatThrownBy(()->formal.verifyStored(1,ROLES,current)).isInstanceOf(BusinessException.class);
        var freshRequest=new com.platform.energy.period.ManualPeriodInputService.Request("A",month.atDay(1),"POLICY-1","R1",
                repository.readings(BUILDING,FROM,TO,true).stream().map(ClassificationRepository.StoredReading::id).toList(),0L,"fresh formal month");
        if("DATE_ONLY".equals(childPrecision)) {
            assertThatThrownBy(()->formal.prepare(1,ROLES,BUILDING,freshRequest)).isInstanceOf(BusinessException.class);
            return;
        }
        var fresh=formal.prepare(1,ROLES,BUILDING,freshRequest);
        assertThat(fresh.nativeQuantity()).isEqualByComparingTo(value.nativeQuantity());
        assertThat(fresh.evidenceHash()).isNotEqualTo(value.evidenceHash());
    }
    @Test void oversizedImportRejects501RowsWithoutPersistingAnything() {
        List<Reading> rows=new ArrayList<>();
        for(int i=0;i<501;i++) rows.add(total("A",FROM.plusSeconds(i),FROM.plusSeconds(i+1),"1","EXACT",null,null));
        assertThatThrownBy(()->pending("too-many",rows)).isInstanceOf(BusinessException.class);
        assertThat(repository.batch(BUILDING,"too-many",true)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_energy_manual_reading",Integer.class)).isZero();
    }
    @Test void oneBatchCanAtomicallyRefineMultipleCompleteParentGroups() {
        var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");
        importApproved("parent-b",total("B",FROM,TO,"31","EXACT",null,null));
        var parentB=repository.currentApprovedReadings(BUILDING,"B",FROM,TO,100).getFirst().id();
        var rows=days(month,id,"EXACT");
        for(var r:days(month,parentB,"EXACT")) rows.add(total("B",r.startInclusive(),r.endExclusive(),"1","EXACT",parentB,"MONTH_TO_DAY"));
        var batch=pending("two-complete-groups",rows);approve(batch);
        var current=repository.readings(BUILDING,FROM,TO,true);
        assertThat(current).hasSize(62).allSatisfy(row->assertThat(row.batchId()).isEqualTo(batch.batchId()));
        assertThat(current).extracting(ClassificationRepository.StoredReading::id).doesNotContain(id,parentB);
        assertThat(repository.currentApprovedReadings(BUILDING,"A",FROM,TO,100)).hasSize(31);
        assertThat(repository.currentApprovedReadings(BUILDING,"B",FROM,TO,100)).hasSize(31);
    }
    @Test void sealedRefinementGroupRemainsWholeAcrossServiceSourcePages() {
        var version=approved("R1");var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");approve(pending("days",days(month,id,"EXACT")));
        var realDays=repository.currentApprovedReadings(BUILDING,"A",FROM,TO,100);
        List<ClassificationRepository.StoredReading> firstPage=new ArrayList<>();List<String> sealedIds=new ArrayList<>();
        for(int i=0;i<4990;i++) {
            String fact=String.format(Locale.ROOT,"SEALED%04d",i);sealedIds.add(fact);
            firstPage.add(new ClassificationRepository.StoredReading(fact,"OLD","{}",BigDecimal.ZERO,1,"APPROVED",FROM));
        }
        firstPage.addAll(realDays.subList(0,10));
        String evidence="{\"inputKind\":\"REVIEWED_MANUAL_MONTH\",\"relationVersionId\":\"R1\",\"request\":{\"factIds\":["+
                sealedIds.stream().map(fact->"\""+fact+"\"").collect(java.util.stream.Collectors.joining(","))+"]}}";
        var sealedB=new com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot("LOCKED_B","locked-b","projection",BUILDING,"B","MONTH",FROM,TO,
                "Asia/Shanghai","period",1,"LOCKED_COMPLETE","FORMAL","ELECTRICITY",BigDecimal.ZERO,"kWh",BigDecimal.ZERO,"TCE",BigDecimal.ONE,
                "","",evidence,"hash-b","{}",TO,null,null,1,LocalDateTime.of(2026,9,2,0,0));
        when(snapshots.listVisibleSnapshots(BUILDING,"MONTH",FROM,TO,5001)).thenReturn(List.of(snapshot(id,"31"),sealedB));
        var paged=spy(repository);
        doReturn(firstPage).when(paged).currentApprovedReadingsPage(BUILDING,FROM,TO,null,null,5000);
        var last=firstPage.getLast();
        doReturn(realDays.subList(10,31)).when(paged).currentApprovedReadingsPage(BUILDING,FROM,TO,last.startInclusive(),last.id(),5000);
        var target=org.springframework.test.util.AopTestUtils.<ClassificationService>getTargetObject(service);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"repository",paged);
        var result=service.query(1,ROLES,BUILDING,query(version.versionId()));
        assertThat(result.total()).isEqualByComparingTo("31");
        assertThat(result.evidenceIds()).contains("LOCKED_MAIN","LOCKED_B");
        assertThat(result.evidenceIds()).doesNotContainAnyElementsOf(realDays.stream().map(ClassificationRepository.StoredReading::id).toList());
        verify(paged).currentApprovedReadingsPage(BUILDING,FROM,TO,null,null,5000);
        verify(paged).currentApprovedReadingsPage(BUILDING,FROM,TO,last.startInclusive(),last.id(),5000);
    }
    @Test void exactMonthMayDowngradeToDateOnlyDaysWithoutChangingItsSealedTotal() throws Exception {
        var version=approved("R1");var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");
        var parentBatch=repository.approvedReading(BUILDING,id).batchId();
        var batch=pending("date-only-days",days(month,id,"DATE_ONLY"));approve(batch);
        var cutoff=Instant.parse("2026-10-01T00:00:00Z");
        jdbc.update("UPDATE biz_energy_manual_batch SET created_at=?,reviewed_at=? WHERE batch_id=?",cutoff.minusSeconds(60).toEpochMilli(),cutoff.minusSeconds(30).toEpochMilli(),parentBatch);
        jdbc.update("UPDATE biz_energy_manual_batch SET created_at=?,reviewed_at=? WHERE batch_id=?",cutoff.minusSeconds(10).toEpochMilli(),cutoff.plusSeconds(30).toEpochMilli(),batch.batchId());
        var before=repository.approvedReadingsAsOf(BUILDING,FROM,TO,cutoff,100);
        assertThat(before).extracting(ClassificationRepository.StoredReading::id).containsExactly(id);
        assertThat(mapper.readValue(before.getFirst().json(),Reading.class).timePrecision()).isEqualTo("EXACT");
        var after=repository.approvedReadingsAsOf(BUILDING,FROM,TO,cutoff.plusSeconds(60),100);
        assertThat(after).hasSize(31);
        for(var row:after) assertThat(mapper.readValue(row.json(),Reading.class).timePrecision()).isEqualTo("DATE_ONLY");
        assertThat(repository.readings(BUILDING,FROM,TO,true)).extracting(ClassificationRepository.StoredReading::id)
                .containsExactlyElementsOf(after.stream().map(ClassificationRepository.StoredReading::id).toList());
        when(snapshots.listVisibleSnapshots(BUILDING,"MONTH",FROM,TO,5001)).thenReturn(List.of(snapshot(id,"31")));
        var result=service.query(1,ROLES,BUILDING,query(version.versionId()));
        assertThat(result.total()).isEqualByComparingTo("31");
        assertThat(result.evidenceIds()).contains("LOCKED_MAIN").doesNotContainAnyElementsOf(after.stream().map(ClassificationRepository.StoredReading::id).toList());
    }
    @Test void dateOnlyParentCannotBeUpgradedToExactDays() {
        var month=YearMonth.of(2026,8);var id=parent(month,"DATE_ONLY");
        rejected(days(month,id,"EXACT"));
        assertThat(repository.readings(BUILDING,FROM,TO,true)).extracting(ClassificationRepository.StoredReading::id).containsExactly(id);
    }
    @Test void approvalDeclaresAndActuallyUsesReadCommittedIsolation() throws Exception {
        var method=ClassificationService.class.getMethod("approveReadings",long.class,Collection.class,String.class,String.class,TransitionRequest.class);
        var transaction=method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transaction.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);
        var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");var batch=pending("days",days(month,id,"DATE_ONLY"));
        var auditObserved=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Integer isolation=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)java.sql.Connection::getTransactionIsolation);
            assertThat(isolation).isEqualTo(java.sql.Connection.TRANSACTION_READ_COMMITTED);
            auditObserved.set(true);return null;
        }).when(audit).append(any());
        approve(batch);assertThat(auditObserved.get()).isTrue();
    }
    @Test void sharedMonthlySourceMatchesEveryIndependentDayAfterRefinementAndCorrection() {
        var version=approved("R1");var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");
        approve(pending("shared-days",days(month,id,"DATE_ONLY")));
        var first=repository.currentApprovedReadings(BUILDING,"A",FROM,FROM.plusSeconds(86400),100).getFirst();
        importApproved("daily-correction",total("A",FROM,FROM.plusSeconds(86400),"2.000000000000","DATE_ONLY",first.id(),null));
        // 未审批的再次修订不能隐藏刚批准的日量。
        var current=repository.currentApprovedReadings(BUILDING,"A",FROM,FROM.plusSeconds(86400),100).getFirst();
        pending("pending-correction",List.of(total("A",FROM,FROM.plusSeconds(86400),"99","DATE_ONLY",current.id(),null)));
        try(var sources=MonthlyReadingWindow.prepare(repository,BUILDING,month,()->Duration.ofMinutes(1))) {
            for(int day=0;day<31;day++) {
                var start=FROM.plusSeconds(day*86400L);var request=new QueryRequest(start,start.plusSeconds(86400),"DAY","ELECTRICITY","kWh","use",version.versionId(),List.of());
                assertThat(service.queryUsingMonthSource(1,ROLES,BUILDING,request,sources)).isEqualTo(service.query(1,ROLES,BUILDING,request));
            }
            assertThat(sources.readDay(BUILDING,FROM,FROM.plusSeconds(86400),Set.of())).extracting(ClassificationRepository.StoredReading::id).containsExactly(current.id());
        }
    }
    @Test void sharedSourceDoesNotClipAnUnrefinedMonthlyFactIntoFakeDays() {
        var version=approved("R1");var month=YearMonth.of(2026,8);parent(month,"EXACT");
        try(var sources=MonthlyReadingWindow.prepare(repository,BUILDING,month,()->Duration.ofMinutes(1))) {
            for(int day:List.of(0,15,30)) {
                var start=FROM.plusSeconds(day*86400L);var request=new QueryRequest(start,start.plusSeconds(86400),"DAY","ELECTRICITY","kWh","use",version.versionId(),List.of());
                assertThatThrownBy(()->service.query(1,ROLES,BUILDING,request)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(409));
                assertThatThrownBy(()->service.queryUsingMonthSource(1,ROLES,BUILDING,request,sources)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(409));
            }
        }
    }
    @Test void preparedReaderEndsSourceTransactionAndStartsIndependentTransactionsForEachDay() {
        approved("R1");var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");approve(pending("transaction-days",days(month,id,"EXACT")));
        var holders=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        ProxyFactory sourceFactory=new ProxyFactory(repository);sourceFactory.setProxyTargetClass(true);
        sourceFactory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(jdbc.getDataSource()),new AnnotationTransactionAttributeSource()));
        sourceFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor)invocation->{
            if(invocation.getMethod().getName().equals("populateMonthlyWindow")) {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                holders.add(org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource()));
            }
            return invocation.proceed();
        });
        var target=new BuildingDailyElectricityReader((ClassificationRepository)sourceFactory.getProxy(),service,authorization,mapper);
        ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(jdbc.getDataSource()),new AnnotationTransactionAttributeSource()));
        var reader=(BuildingDailyElectricityReader)factory.getProxy();
        var preparing=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call->{
            if(preparing.get()) {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return null;
            }
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            holders.add(org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource()));return null;
        }).when(authorization).checkBuilding(1,ROLES,BUILDING);
        try(var sources=reader.prepareMonth(1,ROLES,BUILDING,month,month.plusMonths(1).atDay(1),()->Duration.ofMinutes(1))) {
            preparing.set(false);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            for(int day=1;day<=3;day++) {
                reader.readPrepared(1,ROLES,BUILDING,month.atDay(day),sources);
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            }
        }
        assertThat(holders).hasSize(4).doesNotContainNull();
    }
    @Test void boundedIdSnapshotDoesNotReadHistoricalOrUnapprovedPayloadAndKeepsAcceptedOrder() {
        var version=approved("R1");var month=YearMonth.of(2026,8);var id=parent(month,"EXACT");
        approve(pending("id-snapshot-days",days(month,id,"EXACT")));
        var historical=parent(YearMonth.of(2026,7),"EXACT");
        jdbc.update("UPDATE biz_energy_manual_reading SET reading_json=? WHERE fact_id=?","not valid JSON",historical);
        var unapproved=pending("id-snapshot-pending",List.of(total("B",FROM,FROM.plusSeconds(86400),"900","EXACT",null,null)));
        jdbc.update("UPDATE biz_energy_manual_reading SET reading_json=? WHERE batch_id=?","not valid JSON",unapproved.batchId());
        var legacy=repository.windowCandidatesPage(BUILDING,TO,null,null,1000,()->Duration.ofMinutes(1));
        var overlapping=repository.overlappingWindowCandidatesPage(BUILDING,FROM,TO,null,null,1000,()->Duration.ofMinutes(1));
        String unapprovedId=jdbc.queryForObject("SELECT fact_id FROM biz_energy_manual_reading WHERE batch_id=?",String.class,unapproved.batchId());
        assertThat(legacy).hasSize(34);
        assertThat(legacy).extracting(ClassificationRepository.WindowCandidate::id).contains(historical,unapprovedId);
        assertThat(overlapping).hasSize(33);
        assertThat(overlapping).extracting(ClassificationRepository.WindowCandidate::id).contains(unapprovedId).doesNotContain(historical);
        // SQL excludes only the non-overlapping July metadata; approval still gates payload loading later.
        try(var sources=MonthlyReadingWindow.prepare(repository,BUILDING,month,()->Duration.ofMinutes(1))) {
            assertThat(sources.metrics().metadataRows()).isEqualTo(33);
            assertThat(sources.metrics().candidateIds()).isEqualTo(33);
            assertThat(sources.metrics().payloadRows()).isEqualTo(31);
            for(int day=0;day<31;day++) {
                var start=FROM.plusSeconds(day*86400L);
                var request=new QueryRequest(start,start.plusSeconds(86400),"DAY","ELECTRICITY","kWh","use",version.versionId(),List.of());
                assertThat(service.queryUsingMonthSource(1,ROLES,BUILDING,request,sources)).isEqualTo(service.query(1,ROLES,BUILDING,request));
            }
        }
    }

}
