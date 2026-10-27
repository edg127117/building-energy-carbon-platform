package com.platform.energy.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.energy.period.EnergyPeriodSnapshotReader;
import com.platform.framework.exception.BusinessException;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.RelationGovernanceProperties;
import com.platform.relation.api.RelationContracts.QueryMetadata;
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
class ClassificationServiceTest {
    private static final String BUILDING="BLD_TEST";
    private static final Set<String> ROLES=Set.of("ENERGY_MANAGER");
    private static final Instant FROM=Instant.parse("2026-08-01T00:00:00Z"),TO=Instant.parse("2026-09-01T00:00:00Z");
    private ClassificationService service;
    private ClassificationRepository repository;
    private JdbcTemplate jdbc;
    private AuditEvidenceWriter audit;
    private EnergyPeriodAuthorization authorization;
    private RelationGovernanceService relations;
    private EnergyCatalogService catalog;

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
        repository=new ClassificationRepository(jdbc);authorization=mock(EnergyPeriodAuthorization.class);
        audit=mock(AuditEvidenceWriter.class);relations=mock(RelationGovernanceService.class);catalog=mock(EnergyCatalogService.class);
        doAnswer(call->{if(Objects.equals(call.getArgument(0),call.getArgument(1))) throw new BusinessException(403,"职责分离");return null;}).when(authorization).requireSeparation(anyLong(),anyLong());
        var snapshots=mock(EnergyPeriodSnapshotReader.class);
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
        var target=new ClassificationService(repository,authorization,relations,snapshots,new ClassificationCore(),new ObjectMapper().findAndRegisterModules(),audit,new AuditGovernanceProperties(),catalog,new RelationGovernanceProperties());
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

    @Test void batchDetailReturnsAuthoritativeRevisionAndKeepsBuildingBoundary() {
        var imported = service.importReadings(1, ROLES, BUILDING,
                request("review-detail", reading("B", "10", null)));
        var detail = service.readingBatch(2, ROLES, BUILDING, imported.batchId());
        assertThat(detail.status()).isEqualTo("PENDING_REVIEW");
        assertThat(detail.revision()).isZero();
        assertThat(detail.createdBy()).isEqualTo(1L);
        assertThat(detail.count()).isEqualTo(1);
        assertThatThrownBy(() -> service.readingBatch(2, ROLES, "OTHER", imported.batchId()))
                .isInstanceOf(BusinessException.class);
        service.approveReadings(2, ROLES, BUILDING, imported.batchId(),
                new TransitionRequest(detail.revision(), "独立审核合成来源", null));
        assertThat(service.readingBatch(2, ROLES, BUILDING, imported.batchId()).revision()).isEqualTo(1);
        assertThatThrownBy(() -> service.approveReadings(2, ROLES, BUILDING, imported.batchId(),
                new TransitionRequest(detail.revision(), "重复旧版本审核", null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void sealedProjectionNeverUsesReviewedManualReadingsToFillMissingSnapshots() {
        var version=approved("R1");
        importApproved("manual",reading("A","1000",null),reading("B","600",null),reading("C","200",null),reading("D","300",null));
        var snapshots=List.of(sealed("A","1000"),sealed("B","600"),sealed("C","200"),sealed("D","300"));
        var result=service.projectSealed(1,ROLES,BUILDING,version.versionId(),FROM,TO,"ELECTRICITY","kWh",snapshots);
        assertThat(result.total()).isEqualByComparingTo("1000");
        assertThat(result.slices().stream().map(ClassificationCore.SourceSlice::quantity).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("1000");
        assertThatThrownBy(()->service.projectSealed(1,ROLES,BUILDING,version.versionId(),FROM,TO,"ELECTRICITY","kWh",List.of())).hasMessageContaining("不完整");
        assertThatThrownBy(()->service.projectSealed(1,ROLES,"OTHER",version.versionId(),FROM,TO,"ELECTRICITY","kWh",snapshots)).isInstanceOf(BusinessException.class);
    }

    private com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot sealed(String point,String quantity) {
        return new com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot("sealed-"+point,"key-"+point,"projection",BUILDING,point,"MONTH",FROM,TO,
                "Asia/Shanghai","period",1,"LOCKED_COMPLETE","FORMAL","ELECTRICITY",new BigDecimal(quantity),"kWh",BigDecimal.ZERO,"TCE",BigDecimal.ONE,
                "","","{\"relationVersionId\":\"R1\"}","hash-"+point,"{}",TO,null,null,1,LocalDateTime.of(2026,9,2,0,0));
    }

    @Test void versionLifecycleRejectsStaleRevisionAndPreservesApprovedPayload(){
        var v=draft("R1");final String id=v.versionId();
        assertThatThrownBy(()->service.save(1,ROLES,BUILDING,id,new SaveRequest(0L,config("R1"),"stale"))).isInstanceOf(BusinessException.class);
        v=service.submit(1,ROLES,BUILDING,id,new TransitionRequest(v.revision(),"submit",null));final long revision=v.revision();
        assertThatThrownBy(()->service.approve(1,ROLES,BUILDING,id,new TransitionRequest(revision,"self",FROM))).hasMessageContaining("职责分离");
        v=service.approve(2,ROLES,BUILDING,id,new TransitionRequest(revision,"review",FROM));assertThat(v.status()).isEqualTo("APPROVED");
        assertThatThrownBy(()->service.save(1,ROLES,BUILDING,id,new SaveRequest(revision,config("R1"),"edit"))).isInstanceOf(BusinessException.class);
        verify(authorization,atLeastOnce()).checkBuilding(1,ROLES,BUILDING);
    }
    @Test void withdrawalAndRejectionAllowRepairWithoutReplacingOldVersion(){
        var v=draft(null);v=service.submit(1,ROLES,BUILDING,v.versionId(),new TransitionRequest(v.revision(),"submit",null));
        v=service.reopen(2,ROLES,BUILDING,v.versionId(),new TransitionRequest(v.revision(),"reject",null),true);
        assertThat(v.status()).isEqualTo("DRAFT");
        v=service.submit(1,ROLES,BUILDING,v.versionId(),new TransitionRequest(v.revision(),"submit",null));
        assertThat(service.reopen(1,ROLES,BUILDING,v.versionId(),new TransitionRequest(v.revision(),"withdraw",null),false).status()).isEqualTo("DRAFT");
    }
    @Test void auditFailureRollsBackVersionMutation(){
        doThrow(new IllegalStateException("audit unavailable")).when(audit).append(any());
        assertThatThrownBy(()->service.create(1,ROLES,BUILDING,new CreateRequest("test",null))).hasMessageContaining("audit unavailable");
        assertThat(repository.versions(BUILDING)).isEmpty();
    }
    @Test void unknownRelationIsPublishedAsPendingWithoutInventingStatistics(){
        var v=approved(null);var result=service.query(1,ROLES,BUILDING,query(v.versionId()));
        assertThat(result.status()).isEqualTo("PENDING_EXPERT");assertThat(result.total()).isNull();
    }
    @Test void importIsIdempotentAndDifferentPayloadCannotReuseKey(){
        var r=request("key",reading("A","1000",null));var first=service.importReadings(1,ROLES,BUILDING,r);
        assertThat(service.importReadings(1,ROLES,BUILDING,r).batchId()).isEqualTo(first.batchId());
        assertThatThrownBy(()->service.importReadings(1,ROLES,BUILDING,request("key",reading("A","1001",null)))).hasMessageContaining("幂等键");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_energy_manual_reading",Integer.class)).isEqualTo(1);
    }
    @Test void detectsDoubleMultiplierAndUnitMismatchAndCrossBuilding(){
        Reading scaled=new Reading("A",FROM,TO,"ELECTRICITY","kWh","PERIOD_TOTAL",null,null,BigDecimal.ONE,new BigDecimal("30"),true,"CONFIRMED","test","EXACT",null);
        assertThat(service.preview(1,ROLES,BUILDING,request("bad",scaled)).status()).isEqualTo("INVALID");
        Reading wrong=new Reading("A",FROM,TO,"WATER","m3","PERIOD_TOTAL",null,null,BigDecimal.ONE,BigDecimal.ONE,true,"CONFIRMED","test","EXACT",null);
        var batch=service.importReadings(1,ROLES,BUILDING,request("wrong",wrong));
        assertThatThrownBy(()->service.approveReadings(2,ROLES,BUILDING,batch.batchId(),new TransitionRequest(0L,"review",null))).hasMessageContaining("品种");
        assertThat(service.preview(1,ROLES,BUILDING,request("missing",reading("OTHER","1",null))).status()).isEqualTo("INVALID");
    }
    @Test void duplicatePeriodIsRejectedAtApprovalAndRevisionRetainsOldEvidence(){
        importApproved("first",reading("A","1000",null));
        var duplicate=service.importReadings(1,ROLES,BUILDING,request("second",reading("A","1000",null)));
        assertThatThrownBy(()->service.approveReadings(2,ROLES,BUILDING,duplicate.batchId(),new TransitionRequest(0L,"review",null))).hasMessageContaining("重叠");
        String previous=repository.readings(BUILDING,FROM,TO,true).getFirst().id();
        importApproved("revision",reading("A","1001",previous));
        assertThat(repository.readings(BUILDING,FROM,TO,true)).singleElement().satisfies(r->assertThat(r.quantity()).isEqualByComparingTo("1001"));
        assertThat(service.readings(1,ROLES,BUILDING,FROM,TO)).hasSize(3);
    }
    @Test void actualServiceJoinsReviewedSourcesWithFormalRelationsAndClassifiesThreeLevels(){
        var v=approved("R1");importApproved("month",reading("A","1000",null),reading("B","600",null),reading("C","200",null),reading("D","300",null));
        var result=service.query(1,ROLES,BUILDING,query(v.versionId()));
        assertThat(result.total()).isEqualByComparingTo("1000");assertThat(result.assigned()).isEqualByComparingTo("900");
        assertThat(result.unallocated()).isEqualByComparingTo("100");assertThat(result.status()).isEqualTo("CALCULABLE");
        assertThat(result.resultNature()).isEqualTo("SOURCE_LABELLED_STATISTICS");assertThat(result.evidenceIds()).hasSize(4);
    }
    @Test void newConfigClosesOldIntervalAndQueriesCannotStraddleVersions(){
        var old=approved(null);var next=service.create(1,ROLES,BUILDING,new CreateRequest("copy",old.versionId()));
        next=service.submit(1,ROLES,BUILDING,next.versionId(),new TransitionRequest(next.revision(),"submit",null));
        service.approve(2,ROLES,BUILDING,next.versionId(),new TransitionRequest(next.revision(),"review",FROM.plusSeconds(100)));
        assertThat(service.version(1,ROLES,BUILDING,old.versionId()).effectiveTo()).isEqualTo(FROM.plusSeconds(100));
        assertThatThrownBy(()->service.query(1,ROLES,BUILDING,query(null))).hasMessageContaining("跨配置版本");
    }
    @Test void pendingAndDateOnlyCumulativeSourcesDoNotBecomeExactAmounts(){
        var v=approved("R1");Reading pending=new Reading("A",FROM,TO,"ELECTRICITY","kWh","CUMULATIVE",BigDecimal.TEN,BigDecimal.ONE,null,BigDecimal.ONE,false,"PENDING_EXPERT","needs reset evidence","DATE_ONLY",null);
        assertThat(service.preview(1,ROLES,BUILDING,request("pending",pending)).issues()).isNotEmpty();
        importApproved("pending",pending);assertThat(service.query(1,ROLES,BUILDING,query(v.versionId())).total()).isNull();
    }
    @Test void historyDiagnosticsAreReadOnlyAndDoNotNeedPublishedClassification() {
        var mid=FROM.plusSeconds(86400);
        var a=new Reading("A",FROM,mid,"ELECTRICITY","kWh","CUMULATIVE",BigDecimal.TEN,BigDecimal.TEN,null,BigDecimal.ONE,false,"CONFIRMED","first ledger","EXACT",null);
        var b=new Reading("A",mid,mid.plusSeconds(86400),"ELECTRICITY","kWh","CUMULATIVE",BigDecimal.TEN,BigDecimal.TEN,null,BigDecimal.ONE,false,"CONFIRMED","second ledger","EXACT",null);
        importApproved("unchanged",a,b);
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM biz_energy_manual_reading",Integer.class);
        var values=service.readings(1,ROLES,BUILDING,FROM,TO);
        assertThat(values).hasSize(2).allSatisfy(r->assertThat(r.normalizedQuantity()).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(values.stream().filter(r->r.unchangedReading()!=null)).singleElement().satisfies(r->{
            assertThat(r.unchangedReading().intervalCount()).isEqualTo(2);
            assertThat(r.unchangedReading().sources()).extracting(ReadingDiagnosticSource::sourceReference).containsExactly("first ledger","second ledger");
        });
        assertThat(repository.versions(BUILDING)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_energy_manual_reading",Integer.class)).isEqualTo(before);
        assertThat(service.readingBatch(1,ROLES,BUILDING,values.getFirst().batchId()).status()).isEqualTo("APPROVED");
    }
    @Test void crossBuildingAuthorizationStopsRepositoryAccess(){
        doThrow(new BusinessException(403,"forbidden")).when(authorization).checkBuilding(3,ROLES,BUILDING);
        assertThatThrownBy(()->service.versions(3,ROLES,BUILDING)).hasMessage("forbidden");
        assertThatThrownBy(()->service.readings(3,ROLES,BUILDING,FROM,TO)).hasMessage("forbidden");
    }
    @Test void waterQueryCannotMixElectricitySourcesOrPolicies(){
        var v=approved("R1");importApproved("electricity",reading("A","1000",null));
        var r=new QueryRequest(FROM,TO,"MONTH","WATER","m3","use",v.versionId(),List.of());
        assertThat(service.query(1,ROLES,BUILDING,r).total()).isNull();
        assertThat(service.query(1,ROLES,BUILDING,r).status()).isEqualTo("PENDING_EXPERT");
    }
    @Test void staleApprovalRollsBackClosingPreviousEffectiveVersion(){
        var old=approved(null);var next=service.create(1,ROLES,BUILDING,new CreateRequest("copy",old.versionId()));
        next=service.submit(1,ROLES,BUILDING,next.versionId(),new TransitionRequest(next.revision(),"submit",null));
        String nextId=next.versionId();
        assertThatThrownBy(()->service.approve(2,ROLES,BUILDING,nextId,new TransitionRequest(0L,"stale",FROM.plusSeconds(100)))).isInstanceOf(BusinessException.class);
        assertThat(repository.version(BUILDING,old.versionId()).to()).isNull();
    }
    @Test void provisionalRevisionCannotHideAnApprovedSource(){
        importApproved("first",reading("A","1000",null));String fact=repository.readings(BUILDING,FROM,TO,true).getFirst().id();
        Reading pending=new Reading("A",FROM,TO,"ELECTRICITY","kWh","PERIOD_TOTAL",null,null,new BigDecimal("999"),BigDecimal.ONE,true,"PENDING_EXPERT","pending correction","EXACT",fact);
        var imported=service.importReadings(1,ROLES,BUILDING,request("pending-revision",pending));
        assertThatThrownBy(()->service.approveReadings(2,ROLES,BUILDING,imported.batchId(),new TransitionRequest(0L,"review",null))).hasMessageContaining("修订必须");
        assertThat(repository.readings(BUILDING,FROM,TO,true)).singleElement().satisfies(r->assertThat(r.id()).isEqualTo(fact));
    }
    @Test void concurrentDraftCreationUsesDatabaseScopeLock() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<String> task=()->{
                gate.await(5,java.util.concurrent.TimeUnit.SECONDS);
                try {return service.create(1,ROLES,BUILDING,new CreateRequest("parallel",null)).versionId();}
                catch(BusinessException e){assertThat(e.getCode()).isEqualTo(409);return "CONFLICT";}
            };
            var a=pool.submit(task);var b=pool.submit(task);
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS)))
                    .filteredOn(v->!v.equals("CONFLICT")).hasSize(1);
            assertThat(repository.versions(BUILDING)).hasSize(1);
        }
    }
    @Test void concurrentSameImportKeyIsStoredOnce() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<String> task=()->{gate.await(5,java.util.concurrent.TimeUnit.SECONDS);return service.importReadings(1,ROLES,BUILDING,request("parallel",reading("A","1000",null))).batchId();};
            var a=pool.submit(task);var b=pool.submit(task);
            assertThat(a.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_energy_manual_reading",Integer.class)).isEqualTo(1);
        }
    }
    @Test void unresolvedMultiplierCanBeRetainedWithoutCreatingAQuantity(){
        Reading pending=new Reading("A",FROM,TO,"ELECTRICITY","kWh","CUMULATIVE",BigDecimal.ONE,BigDecimal.TEN,null,null,false,"PENDING_EXPERT","multiplier unknown","DATE_ONLY",null);
        var preview=service.preview(1,ROLES,BUILDING,request("unknown-multiplier",pending));
        assertThat(preview.status()).isEqualTo("PREVIEW");assertThat(preview.issues()).anyMatch(i->i.code().equals("PENDING_EXPERT"));
        service.importReadings(1,ROLES,BUILDING,request("unknown-multiplier",pending));
        assertThat(service.readings(1,ROLES,BUILDING,FROM,TO)).singleElement().satisfies(r->{assertThat(r.normalizedQuantity()).isNull();assertThat(r.reading().multiplier()).isNull();});
    }
    @Test void twoUnrelatedCountersOnOnePhysicalDeviceRequireExplicitChannelConfirmation(){
        var v=approved("R1");jdbc.update("UPDATE biz_data_point SET equip_id='MULTI_CHANNEL_METER' WHERE point_id IN ('C','D')");
        importApproved("month",reading("A","1000",null),reading("B","600",null),reading("C","200",null),reading("D","300",null));
        var result=service.query(1,ROLES,BUILDING,query(v.versionId()));
        assertThat(result.status()).isEqualTo("PENDING_EXPERT");assertThat(result.total()).isNull();
    }
    @Test void independentlyConfirmedChannelsCanUseTheSameGenericModel(){
        var draft=draft("R1");var c=config("R1");
        c=new Configuration(c.relationVersionId(),c.schemes(),c.nodes(),c.bindings(),List.of(new BoundaryRule("BOUNDARY","MAIN_WITH_SUBMETER_BREAKDOWN","CONFIRMED","independent channel evidence","ELECTRICITY","kWh",true)));
        draft=service.save(1,ROLES,BUILDING,draft.versionId(),new SaveRequest(draft.revision(),c,"confirmed channels"));
        draft=service.submit(1,ROLES,BUILDING,draft.versionId(),new TransitionRequest(draft.revision(),"submit",null));
        var v=service.approve(2,ROLES,BUILDING,draft.versionId(),new TransitionRequest(draft.revision(),"review",FROM));
        jdbc.update("UPDATE biz_data_point SET equip_id='MULTI_CHANNEL_METER' WHERE point_id IN ('C','D')");
        importApproved("month",reading("A","1000",null),reading("B","600",null),reading("C","200",null),reading("D","300",null));
        assertThat(service.query(1,ROLES,BUILDING,query(v.versionId())).total()).isEqualByComparingTo("1000");
    }
}
