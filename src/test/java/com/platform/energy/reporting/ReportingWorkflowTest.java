package com.platform.energy.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.platform.audit.*;
import com.platform.carbon.ReportingSnapshotChanges;
import com.platform.energy.catalog.*;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.framework.exception.BusinessException;
import com.platform.system.service.BuildingScopeService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.platform.energy.reporting.ReportingContracts.*;

/** 真实隔离H2事务验证账单闭环；目录、鉴权及碳影响发布是显式替身，不冒充现场验收。 */
class ReportingWorkflowTest {
    static final String BUILDING="BILL_BUILDING";
    static final Set<String> ADMIN=Set.of("PLATFORM_ADMIN"), OWNER=Set.of("BUILDING_OWNER");
    static final LocalDate MONTH=LocalDate.of(2024,1,1);
    ReportingService service;ReportingRepository repository;JdbcTemplate jdbc;TransactionTemplate tx;
    BackendDutyService duties;BuildingScopeService scopes;EnergyConversionService conversion;ReportingSnapshotChanges changes;
    @BeforeEach void setup() throws Exception {
        var ds=dataSource();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        jdbc.execute("CREATE TABLE building(building_id VARCHAR(32) PRIMARY KEY,del_flag INT DEFAULT 0)");
        jdbc.update("INSERT INTO building(building_id) VALUES (?)",BUILDING);
        for(String sql:Files.readString(Path.of("src/test/resources/energy-reporting-schema-test.sql")).split(";"))if(!sql.isBlank())jdbc.execute(sql);
        repository=new ReportingRepository(jdbc);scopes=mock(BuildingScopeService.class);duties=mock(BackendDutyService.class);
        when(duties.hasDuty(anyLong(),any())).thenReturn(true);
        doAnswer(i->{if(i.<Long>getArgument(0).equals(i.<Long>getArgument(1)))throw new BusinessException(403,"职责分离");return null;})
            .when(duties).requireSeparation(anyLong(),anyLong());
        var catalog=mock(EnergyCatalogService.class);var lookup=mock(EnergyCatalogLookup.class);conversion=mock(EnergyConversionService.class);changes=mock(ReportingSnapshotChanges.class);
        when(lookup.findApprovedItem(eq("ELECTRICITY"),any())).thenReturn(Optional.of(new EnergyCatalogLookup.ApprovedEnergyItem(
            "ei","ELECTRICITY","ei-v1",1,"ELECTRICITY",List.of("PURCHASED_ELECTRICITY"),"STANDARD","已确认资料",LocalDateTime.of(2000,1,1,0,0),null)));
        when(lookup.findApprovedUnit(eq("KWH"),any())).thenReturn(Optional.of(new EnergyCatalogLookup.ApprovedUnit(
            "u","KWH","u-v1",1,"kWh","ENERGY","KWH",BigDecimal.ONE,"IDENTITY",null,"STANDARD","单位依据",LocalDateTime.of(2000,1,1,0,0),null)));
        service=new ReportingService(repository,scopes,duties,catalog,lookup,conversion,
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),mock(AuditEvidenceWriter.class),new AuditGovernanceProperties(),changes);
    }
    javax.sql.DataSource dataSource() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:reporting_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");return ds;
    }
    @Test void concurrentIdenticalRequestsProduceOnlyOneBatchAndReceipt() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var requests=new ArrayList<java.util.concurrent.Future<Batch>>();
            for(int i=0;i<8;i++)requests.add(pool.submit(()->create("concurrent")));
            Set<String> ids=new HashSet<>();for(var f:requests)ids.add(f.get(15,java.util.concurrent.TimeUnit.SECONDS).batchId());
            assertThat(ids).hasSize(1);assertThat(repository.list(BUILDING,"BATCH")).hasSize(1);
        }
    }
    @Test void ownerBillWithoutDeviceCanSealAndFreezeNativeQuantityWhileTceIsUnavailable() {
        var source=approved("bill","NO_METER");var draft=create("batch");
        var saved=save(draft,row(source,"1000",null));assertThat(service.preview(1,OWNER,BUILDING,saved.batchId()).ready()).isTrue();
        var sealed=seal(saved);assertThat(sealed.snapshot().nativeStatus()).isEqualTo("COMPLETE");
        assertThat(sealed.snapshot().tceStatus()).isEqualTo("NOT_CALCULATED");
        var activity=new ReportingCarbonInput(service).read(BUILDING,start(),end(),100).activities().getFirst();
        assertThat(activity.quantity()).isEqualByComparingTo("1000");assertThat(activity.lockStatus()).isEqualTo("LOCKED_NATIVE_COMPLETE");
        assertThat(activity.traceEvidence().path("classificationStatus").asText()).isEqualTo("UNALLOCATED");
        verify(changes).sealed(BUILDING,null,sealed.batchId(),MONTH,3);
        assertThatThrownBy(()->save(sealed,row(source,"2",null))).isInstanceOf(BusinessException.class);
    }
    @Test void missingAnnualEstimatedPendingAndDuplicateRowsNeverBecomeFormal() {
        var source=approved("bill","NO_METER");var draft=create("batch");
        assertThat(service.preview(1,OWNER,BUILDING,draft.batchId()).ready()).isFalse();
        var annual=new Row(source.sourceId(),source.revision(),new BigDecimal("1000"),"CONFIRMED",MONTH,MONTH.plusYears(1),"账单","",null,"","","NATIVE_QUANTITY");
        draft=save(draft,annual);assertThat(service.preview(1,OWNER,BUILDING,draft.batchId()).issues()).anyMatch(i->i.message().contains("整月"));
        var estimate=new Row(source.sourceId(),source.revision(),BigDecimal.ONE,"CONFIRMED",MONTH,MONTH.plusMonths(1),"金额折算","",null,"","","AMOUNT_ESTIMATE");
        draft=save(draft,estimate);assertThat(service.preview(1,OWNER,BUILDING,draft.batchId()).issues()).anyMatch(i->i.message().contains("估算"));
        var pending=new Row(source.sourceId(),source.revision(),null,"PENDING_CONFIRMATION",MONTH,MONTH.plusMonths(1),"","",null,"","","NATIVE_QUANTITY");
        draft=save(draft,pending);var current=draft;
        assertThatThrownBy(()->command(current,"submit",1,OWNER)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->tx.execute(s->service.saveBatch(1,OWNER,BUILDING,current.batchId(),new SaveBatch(current.revision(),List.of(pending,pending),"重复"))))
            .isInstanceOf(BusinessException.class);
        assertThat(repository.list(BUILDING,"BATCH")).hasSize(1);
    }
    @Test void extremeExponentCannotOverflowPrecisionGuardOrBecomeFrozenEvidence() {
        var source=approved("exponent","NO_METER");var batch=create("exponent-batch");
        assertThatThrownBy(()->save(batch,row(source,"1e2147483647",null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("精度");
    }
    @Test void sourceCommandReplayReturnsOriginalReceiptAndNullRowFailsAsInputError() {
        var source=tx.execute(s1->service.createSource(2,ADMIN,BUILDING,new CreateSource(config("replay","NO_METER"),"source-replay")));
        var command=new Command(0L,"提交","source-submit-replay");
        var submitted=tx.execute(s1->service.sourceCommand(2,ADMIN,BUILDING,source.sourceId(),"submit",command));
        var replay=tx.execute(s1->service.sourceCommand(2,ADMIN,BUILDING,source.sourceId(),"submit",command));
        assertThat(replay).isEqualTo(submitted);
        var batch=create("null-row");
        assertThatThrownBy(()->tx.execute(s1->service.saveBatch(1,OWNER,BUILDING,batch.batchId(),new SaveBatch(0L,Collections.singletonList(null),"非法行"))))
                .isInstanceOf(BusinessException.class);
    }
    @Test void confirmedZeroIsDistinctFromMissingAndCanBeSealed() {
        var source=approved("zero","NO_METER");var sealed=seal(save(create("batch"),row(source,"0",null)));
        assertThat(sealed.snapshot().rows().getFirst().nativeQuantity()).isEqualByComparingTo("0");
    }
    @Test void idempotencyBindsActorAndContentAndReturnsOriginalReceipt() {
        var first=create("same");var repeated=create("same");assertThat(repeated.batchId()).isEqualTo(first.batchId());
        assertThatThrownBy(()->tx.execute(s->service.createBatch(2,ADMIN,BUILDING,new CreateBatch(MONTH,"填报","same",null))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("幂等");
        assertThat(repository.list(BUILDING,"BATCH")).hasSize(1);
    }
    @Test void independentReviewStaleRevisionAndCrossBuildingAreEnforced() {
        var source=approved("bill","NO_METER");var saved=save(create("batch"),row(source,"100",null));
        assertThatThrownBy(()->save(new Batch(saved.batchId(),BUILDING,MONTH,saved.status(),0,1,null,null,saved.rows(),null),row(source,"2",null)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("修订");
        var submitted=command(saved,"submit",1,OWNER);
        assertThatThrownBy(()->command(submitted,"approve",1,ADMIN)).isInstanceOf(BusinessException.class).hasMessageContaining("独立审核");
        doThrow(new BusinessException(403,"越权")).when(scopes).checkAccess(1L,OWNER,"OTHER");
        assertThatThrownBy(()->service.batch(1,OWNER,"OTHER",saved.batchId())).isInstanceOf(BusinessException.class).hasMessageContaining("越权");
    }
    @Test void exclusionKeepsNativeQuantityAndSubtractsOnlyFromCarbonInput() {
        var source=approved("exclusion","NO_METER");var saved=save(create("batch"),row(source,"1000","600"));
        var sealed=seal(saved);var activity=new ReportingCarbonInput(service).read(BUILDING,start(),end(),100).activities().getFirst();
        assertThat(activity.quantity()).isEqualByComparingTo("400");assertThat(sealed.snapshot().rows().getFirst().nativeQuantity()).isEqualByComparingTo("1000");
        verify(duties,atLeastOnce()).requireDuty(3,BackendDuty.CARBON_RULE_REVIEW);
        assertThatThrownBy(()->save(createRevision(sealed),row(source,"100","101"))).isInstanceOf(BusinessException.class);
    }
    @Test void revisionPreservesOldSnapshotAndReplacesOnlyCurrentPointer() {
        var source=approved("bill","NO_METER");var old=seal(save(create("original"),row(source,"1000",null)));
        var next=seal(save(createRevision(old),row(source,"1100",null)));
        assertThat(service.batch(1,OWNER,BUILDING,old.batchId()).status()).isEqualTo("SUPERSEDED");
        assertThat(service.batch(1,OWNER,BUILDING,old.batchId()).snapshot().rows().getFirst().nativeQuantity()).isEqualByComparingTo("1000");
        assertThat(service.sealed(BUILDING)).extracting(Batch::batchId).containsExactly(next.batchId());
    }
    @Test void unknownOverlapCannotApproveAndNoSecondPrimaryForSameEnergyCanBePublished() {
        var pending=tx.execute(s->service.createSource(2,ADMIN,BUILDING,new CreateSource(config("pending","PENDING_CONFIRMATION"),"pending")));
        assertThatThrownBy(()->tx.execute(s->service.sourceCommand(2,ADMIN,BUILDING,pending.sourceId(),"submit",new Command(0L,"提交",null))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("待确认");
        approved("one","NO_METER");var second=tx.execute(s->service.createSource(2,ADMIN,BUILDING,new CreateSource(config("two","REPLACES_METER_TOTAL"),"two")));
        var submitted=tx.execute(s->service.sourceCommand(2,ADMIN,BUILDING,second.sourceId(),"submit",new Command(0L,"提交",null)));
        assertThatThrownBy(()->tx.execute(s->service.sourceCommand(3,ADMIN,BUILDING,submitted.sourceId(),"approve",new Command(1L,"审核",null))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("主来源");
    }
    @Test void configuredPrimaryWithoutSealedBillNeverFallsBackToMeterOrZero() {
        approved("bill","REPLACES_METER_TOTAL");
        assertThatThrownBy(()->new ReportingCarbonInput(service).read(BUILDING,start(),end(),100)).isInstanceOf(BusinessException.class).hasMessageContaining("尚无");
    }
    @Test void reviewedDependenciesChangingBeforeSealRequireIndependentReviewAgain() {
        var c=config("formal-rule","NO_METER");
        var configured=new SourceConfig(c.name(),c.energyItemCode(),c.unitCode(),c.channel(),c.pathway(),c.purpose(),c.coverage(),
                c.meterOverlap(),c.timezoneId(),c.effectiveFrom(),c.effectiveTo(),c.evidence(),c.reason(),
                "DIRECT_TCE_FACTOR","CALORIFIC_EQUIVALENT","GLOBAL",null);
        var source=tx.execute(s->service.createSource(2,ADMIN,BUILDING,new CreateSource(configured,"formal-src")));
        source=tx.execute(s->service.sourceCommand(2,ADMIN,BUILDING,repository.list(BUILDING,"SOURCE").getFirst().id(),"submit",new Command(0L,"提交",null)));
        var submittedSource=source;
        source=tx.execute(s->service.sourceCommand(3,ADMIN,BUILDING,submittedSource.sourceId(),"approve",new Command(1L,"审核",null)));
        when(conversion.evaluateFormalRules(anyLong(),any(),any(),any())).thenReturn(
                new EnergyConversionService.FormalConversion(new BigDecimal("1"),"TCE","p1","f1",null,Map.of("version","p1")));
        var reviewed=command(command(save(create("batch"),row(source,"1000",null)),"submit",1,OWNER),"approve",3,ADMIN);
        when(conversion.evaluateFormalRules(anyLong(),any(),any(),any())).thenReturn(
                new EnergyConversionService.FormalConversion(new BigDecimal("2"),"TCE","p2","f1",null,Map.of("version","p2")));
        assertThatThrownBy(()->command(reviewed,"seal",3,ADMIN)).isInstanceOf(BusinessException.class).hasMessageContaining("变化");
        assertThat(service.batch(1,OWNER,BUILDING,reviewed.batchId()).status()).isEqualTo("REVIEWED");
        verify(changes,never()).sealed(anyString(),any(),anyString(),any(),anyLong());
    }
    @Test void approvedReplacementClosesOnlyFutureSourceAndPreservesOldFrozenBill() {
        var oldSource=approved("old","NO_METER");var old=seal(save(create("original"),row(oldSource,"1000",null)));
        var c=config("new","REPLACES_METER_TOTAL");
        var replacement=new SourceConfig(c.name(),c.energyItemCode(),c.unitCode(),c.channel(),c.pathway(),c.purpose(),c.coverage(),
                c.meterOverlap(),c.timezoneId(),MONTH.plusMonths(1),null,c.evidence(),c.reason(),null,null,null,oldSource.sourceId());
        var source=tx.execute(s->service.createSource(2,ADMIN,BUILDING,new CreateSource(replacement,"replacement")));
        var submitted=tx.execute(s->service.sourceCommand(2,ADMIN,BUILDING,source.sourceId(),"submit",new Command(0L,"提交",null)));
        tx.execute(s->service.sourceCommand(3,ADMIN,BUILDING,source.sourceId(),"approve",new Command(submitted.revision(),"审核替代",null)));
        var closed=service.primarySources(BUILDING).stream().filter(v->v.sourceId().equals(oldSource.sourceId())).findFirst().orElseThrow();
        assertThat(closed.configuration().effectiveTo()).isEqualTo(MONTH.plusMonths(1));
        assertThat(service.batch(1,OWNER,BUILDING,old.batchId()).snapshot()).isEqualTo(old.snapshot());
        assertThat(new ReportingCarbonInput(service).read(BUILDING,start(),end(),100).activities().getFirst().quantity()).isEqualByComparingTo("1000");
        var revision=createRevision(old);
        assertThat(revision.rows().getFirst().sourceRevision()).isEqualTo(closed.revision());
        assertThat(revision.rows().getFirst().quantity()).isEqualByComparingTo("1000");
        assertThat(service.preview(1,OWNER,BUILDING,revision.batchId()).ready()).isTrue();
    }
    @Test void frozenTenThousandStandardVolumeIsConvertedExactlyOnceAndKeepsConditions() {
        var lookup=(EnergyCatalogLookup)org.springframework.test.util.ReflectionTestUtils.getField(service,"lookup");
        when(lookup.findApprovedItem(eq("NATURAL_GAS"),any())).thenReturn(Optional.of(new EnergyCatalogLookup.ApprovedEnergyItem(
                "ng","NATURAL_GAS","ng-v1",1,"NATURAL_GAS",List.of("STATIONARY_COMBUSTION"),"STANDARD","隔离资料",LocalDateTime.of(2000,1,1,0,0),null)));
        when(lookup.findApprovedUnit(eq("TEN_THOUSAND_NM3"),any())).thenReturn(Optional.of(new EnergyCatalogLookup.ApprovedUnit(
                "ng-u","TEN_THOUSAND_NM3","ng-u-v1",1,"万Nm³","NORMAL_VOLUME","NM3",new BigDecimal("10000"),"FIXED_SCALE","ACCEPTANCE_CONDITION","STANDARD","隔离状态依据",LocalDateTime.of(2000,1,1,0,0),null)));
        var c=new SourceConfig("天然气账单","NATURAL_GAS","TEN_THOUSAND_NM3","BILL","STATIONARY_COMBUSTION","PRIMARY","WHOLE_BUILDING","NO_METER","Asia/Shanghai",MONTH,null,"独立标准状态凭证","建立",null,null,null,null);
        var source=tx.execute(s1->service.createSource(2,ADMIN,BUILDING,new CreateSource(c,"ng")));
        tx.execute(s1->service.sourceCommand(2,ADMIN,BUILDING,source.sourceId(),"submit",new Command(0L,"提交",null)));
        var approved=tx.execute(s1->service.sourceCommand(3,ADMIN,BUILDING,source.sourceId(),"approve",new Command(1L,"审核",null)));
        var sealed=seal(save(create("gas-batch"),row(approved,"5.3173","0.0013")));
        assertThat(sealed.snapshot().rows().getFirst().nativeQuantity()).isEqualByComparingTo("5.3173");
        var activity=new ReportingCarbonInput(service).read(BUILDING,start(),end(),100).activities().getFirst();
        assertThat(activity.quantity()).isEqualByComparingTo("53160");assertThat(activity.unitCode()).isEqualTo("NM3");
        assertThat(activity.traceEvidence().path("standardConditionCode").asText()).isEqualTo("ACCEPTANCE_CONDITION");
        assertThat(activity.traceEvidence().path("excludedQuantity").asText()).isEqualTo("0.0013");
    }
    @Test void auditFailureRollsBackRecordAndIdempotencyReceiptTogether() {
        var auditField=org.springframework.test.util.ReflectionTestUtils.getField(service,"audit");
        doThrow(new IllegalStateException("audit unavailable")).when((AuditEvidenceWriter)auditField).append(any());
        assertThatThrownBy(()->create("failed")).isInstanceOf(IllegalStateException.class);
        assertThat(repository.list(BUILDING,"BATCH")).isEmpty();
        assertThat(repository.receipt(BUILDING,"failed")).isNull();
    }
    @Test void sourceCodesAreCanonicalBeforeAuthorityComparisonAndFreezing() {
        var c=config("normalized","NO_METER");
        var lookup=(EnergyCatalogLookup)org.springframework.test.util.ReflectionTestUtils.getField(service,"lookup");
        var canonicalItem=lookup.findApprovedItem("ELECTRICITY",MONTH.atStartOfDay());
        var canonicalUnit=lookup.findApprovedUnit("KWH",MONTH.atStartOfDay());
        when(lookup.findApprovedItem(eq(" electricity "),any())).thenReturn(canonicalItem);
        when(lookup.findApprovedUnit(eq(" kwh "),any())).thenReturn(canonicalUnit);
        var raw=new SourceConfig(c.name()," electricity "," kwh ",c.channel(),c.pathway(),c.purpose(),c.coverage(),
                c.meterOverlap(),c.timezoneId(),c.effectiveFrom(),c.effectiveTo(),c.evidence(),c.reason(),null,null,null,null);
        var source=tx.execute(s->service.createSource(2,ADMIN,BUILDING,new CreateSource(raw,"normalized")));
        assertThat(source.configuration().energyItemCode()).isEqualTo("ELECTRICITY");
        assertThat(source.configuration().unitCode()).isEqualTo("KWH");
        var submitted=tx.execute(s->service.sourceCommand(2,ADMIN,BUILDING,source.sourceId(),"submit",new Command(0L,"提交",null)));
        var approved=tx.execute(s->service.sourceCommand(3,ADMIN,BUILDING,source.sourceId(),"approve",new Command(submitted.revision(),"审核",null)));
        var sealed=seal(save(create("normalized-batch"),row(approved,"1000",null)));
        assertThat(new ReportingCarbonInput(service).read(BUILDING,start(),end(),100).activities()).singleElement()
                .satisfies(a->{assertThat(a.energyItemCode()).isEqualTo("ELECTRICITY");assertThat(a.unitCode()).isEqualTo("KWH");});
        assertThat(sealed.snapshot().nativeStatus()).isEqualTo("COMPLETE");
        var duplicate=tx.execute(s->service.createSource(2,ADMIN,BUILDING,new CreateSource(config("duplicate","NO_METER"),"duplicate-source")));
        var pending=tx.execute(s->service.sourceCommand(2,ADMIN,BUILDING,duplicate.sourceId(),"submit",new Command(0L,"提交",null)));
        assertThatThrownBy(()->tx.execute(s->service.sourceCommand(3,ADMIN,BUILDING,duplicate.sourceId(),"approve",new Command(pending.revision(),"审核",null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("主来源");
    }
    @Test void billingMonthCatalogMustStillSupportTheApprovedSourcePathway() {
        var source=approved("catalog-change","NO_METER");
        var lookup=(EnergyCatalogLookup)org.springframework.test.util.ReflectionTestUtils.getField(service,"lookup");
        when(lookup.findApprovedItem(eq("ELECTRICITY"),any())).thenAnswer(i->{
            LocalDateTime at=i.getArgument(1);boolean later=!at.isBefore(MONTH.atStartOfDay());
            return Optional.of(new EnergyCatalogLookup.ApprovedEnergyItem("ei","ELECTRICITY",later?"ei-v2":"ei-v1",later?2:1,
                    later?"HEAT":"ELECTRICITY",later?List.of("PURCHASED_HEAT"):List.of("PURCHASED_ELECTRICITY"),
                    "STANDARD","隔离目录变更",LocalDateTime.of(2000,1,1,0,0),null));
        });
        var batch=save(create("catalog-change-batch"),row(source,"1000",null));
        assertThat(service.preview(1,OWNER,BUILDING,batch.batchId()).ready()).isFalse();
        assertThatThrownBy(()->command(batch,"submit",1,OWNER)).isInstanceOf(BusinessException.class).hasMessageContaining("核算路径");
        assertThat(service.batch(1,OWNER,BUILDING,batch.batchId()).status()).isEqualTo("DRAFT");
    }
    @Test void refreshSourcesRepairsAnEmptyDraftWithoutBypassingReviewOrCopyingNewSourceQuantities() {
        var empty=create("empty-before-source");var source=approved("late-approved","NO_METER");
        var refreshed=command(empty,"refresh-sources",1,OWNER);
        assertThat(refreshed.status()).isEqualTo("DRAFT");assertThat(refreshed.snapshot()).isNull();
        assertThat(refreshed.rows()).singleElement().satisfies(r->{assertThat(r.sourceId()).isEqualTo(source.sourceId());
            assertThat(r.sourceRevision()).isEqualTo(source.revision());assertThat(r.quantity()).isNull();assertThat(r.valueStatus()).isEqualTo("MISSING");});
        assertThat(command(empty,"refresh-sources",1,OWNER)).isEqualTo(refreshed);
        assertThat(service.preview(1,OWNER,BUILDING,refreshed.batchId()).ready()).isFalse();
        var saved=save(refreshed,row(source,"1000",null));
        assertThatThrownBy(()->command(saved,"refresh-sources",2,ADMIN)).isInstanceOf(BusinessException.class).hasMessageContaining("本人");
        var submitted=command(saved,"submit",1,OWNER);
        assertThatThrownBy(()->command(submitted,"refresh-sources",1,OWNER)).isInstanceOf(BusinessException.class).hasMessageContaining("状态");
        verify(changes,never()).sealed(anyString(),any(),anyString(),any(),anyLong());
    }
    @Test void refreshDoesNotCopyQuantitiesAcrossReplacementSourceIdentities() {
        var old=approved("old-current","NO_METER");var draft=save(create("old-draft"),row(old,"1000",null));
        var c=config("replacement-current","NO_METER");
        var configuration=new SourceConfig(c.name(),c.energyItemCode(),c.unitCode(),c.channel(),c.pathway(),c.purpose(),c.coverage(),
                c.meterOverlap(),c.timezoneId(),MONTH,null,c.evidence(),c.reason(),null,null,null,old.sourceId());
        var created=tx.execute(s->service.createSource(2,ADMIN,BUILDING,new CreateSource(configuration,"replacement-current")));
        var submitted=tx.execute(s->service.sourceCommand(2,ADMIN,BUILDING,created.sourceId(),"submit",new Command(0L,"提交",null)));
        var replacement=tx.execute(s->service.sourceCommand(3,ADMIN,BUILDING,created.sourceId(),"approve",new Command(submitted.revision(),"审核",null)));
        var refreshed=command(draft,"refresh-sources",1,OWNER);
        assertThat(refreshed.rows()).singleElement().satisfies(r->{assertThat(r.sourceId()).isEqualTo(replacement.sourceId());
            assertThat(r.quantity()).isNull();assertThat(r.valueStatus()).isEqualTo("MISSING");});
        assertThat(service.preview(1,OWNER,BUILDING,refreshed.batchId()).ready()).isFalse();
    }
    private Source approved(String name,String overlap) {
        var created=tx.execute(s->service.createSource(2,ADMIN,BUILDING,new CreateSource(config(name,overlap),"src:"+name)));
        var submitted=tx.execute(s->service.sourceCommand(2,ADMIN,BUILDING,created.sourceId(),"submit",new Command(0L,"提交",null)));
        return tx.execute(s->service.sourceCommand(3,ADMIN,BUILDING,created.sourceId(),"approve",new Command(submitted.revision(),"审核",null)));
    }
    private SourceConfig config(String name,String overlap) {return new SourceConfig(name,"ELECTRICITY","KWH","BILL","PURCHASED_ELECTRICITY","PRIMARY","WHOLE_BUILDING",overlap,"Asia/Shanghai",LocalDate.of(2020,1,1),null,"已核对覆盖对象","建立来源",null,null,null,null);}
    private Batch create(String key){return tx.execute(s->service.createBatch(1,OWNER,BUILDING,new CreateBatch(MONTH,"填报",key,null)));}
    private Batch createRevision(Batch old){return tx.execute(s->service.createBatch(1,OWNER,BUILDING,new CreateBatch(MONTH,"修订","revision:"+old.batchId(),old.batchId())));}
    private Row row(Source s,String quantity,String exclusion){return new Row(s.sourceId(),s.revision(),new BigDecimal(quantity),"CONFIRMED",MONTH,MONTH.plusMonths(1),"实际账单凭证","",exclusion==null?null:new BigDecimal(exclusion),exclusion==null?"":"经专业确认排除",exclusion==null?"":"独立量化凭证","NATIVE_QUANTITY");}
    private Batch save(Batch b,Row row){return tx.execute(s->service.saveBatch(1,OWNER,BUILDING,b.batchId(),new SaveBatch(b.revision(),List.of(row),"保存")));}
    private Batch command(Batch b,String action,long user,Set<String> roles){return tx.execute(s->service.batchCommand(user,roles,BUILDING,b.batchId(),action,new Command(b.revision(),"处理",b.batchId()+":"+action+":"+b.revision())));}
    private Batch seal(Batch b){return command(command(command(b,"submit",1,OWNER),"approve",3,ADMIN),"seal",3,ADMIN);}
    static Instant start(){return MONTH.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();}
    static Instant end(){return MONTH.plusMonths(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();}
}
