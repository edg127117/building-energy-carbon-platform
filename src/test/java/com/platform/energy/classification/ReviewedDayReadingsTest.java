package com.platform.energy.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.period.*;
import com.platform.framework.exception.BusinessException;
import com.platform.relation.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.platform.energy.classification.ClassificationContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2 and mocks only: no external database, approval writes, or imported production facts. */
class ReviewedDayReadingsTest {
    private static final Instant FROM=Instant.parse("2026-08-01T16:00:00Z"),TO=FROM.plusSeconds(86400),AS_OF=TO.plusSeconds(86400);
    private static final Supplier<Duration> BUDGET=()->Duration.ofSeconds(30);
    private static final Set<String> ROLES=Set.of("ENERGY_MANAGER");
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private ClassificationRepository repository;
    private ClassificationService service;
    private EnergyPeriodAuthorization authorization;

    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_UPPER=false","sa","");
        // Keep this isolated database alive across the repository's short-lived connections.
        ds.setUrl(ds.getUrl()+";DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE building(building_id VARCHAR(32) PRIMARY KEY,del_flag INT DEFAULT 0)");
        jdbc.update("INSERT INTO building VALUES ('B',0),('OTHER',0)");
        jdbc.execute("CREATE TABLE biz_energy_manual_batch(batch_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),status VARCHAR(30),created_at BIGINT,reviewed_at BIGINT)");
        jdbc.execute("CREATE TABLE biz_energy_manual_reading(fact_id VARCHAR(32) PRIMARY KEY,batch_id VARCHAR(32),building_id VARCHAR(32),start_inclusive BIGINT,end_exclusive BIGINT,reading_json CLOB,normalized_quantity DECIMAL(38,12),created_by BIGINT,supersedes_fact_id VARCHAR(32))");
        batch("old","B",AS_OF.minusSeconds(1));
        repository=spy(new ClassificationRepository(jdbc));authorization=mock(EnergyPeriodAuthorization.class);
        var target=new ClassificationService(repository,authorization,mock(RelationGovernanceService.class),
                mock(EnergyPeriodSnapshotReader.class),new ClassificationCore(),json,mock(AuditEvidenceWriter.class),
                new AuditGovernanceProperties(),mock(EnergyCatalogService.class),new RelationGovernanceProperties());
        var proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(ClassificationService)proxy.getProxy();
    }

    @Test void complete219PointDayMatchesAll24OriginalHourlyReads() throws Exception {
        var rows=new ArrayList<Object[]>();
        for(int hour=0;hour<24;hour++)for(int point=0;point<219;point++) {
            Instant start=FROM.plusSeconds(hour*3600L);
            var reading=reading("P"+point,start,start.plusSeconds(3600));
            rows.add(new Object[]{"F"+hour+"_"+point,"old","B",start.toEpochMilli(),start.plusSeconds(3600).toEpochMilli(),json.writeValueAsString(reading),BigDecimal.ONE,1L,null});
        }
        jdbc.batchUpdate("INSERT INTO biz_energy_manual_reading VALUES (?,?,?,?,?,?,?,?,?)",rows);
        var day=read();assertThat(day).hasSize(5256);
        var metadataLimits=org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(repository,times(6)).overlappingWindowCandidatesPage(eq("B"),eq(FROM),eq(TO),any(),any(),metadataLimits.capture(),same(BUDGET));
        assertThat(metadataLimits.getAllValues()).containsOnly(1000);
        @SuppressWarnings("unchecked") var bodyIds=(org.mockito.ArgumentCaptor<List<String>>)(Object)org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository,times(21)).approvedWindowByIdsAsOf(eq("B"),eq(FROM),eq(TO),eq(AS_OF),bodyIds.capture(),same(BUDGET));
        assertThat(bodyIds.getAllValues()).allSatisfy(ids->{
            assertThat(ids.size()).isBetween(1,256);
            assertThat(new HashSet<>(ids)).hasSize(ids.size());
        });
        assertThat(bodyIds.getAllValues().stream().mapToInt(List::size).sum()).isEqualTo(5256);
        for(int hour=0;hour<24;hour++) {
            Instant start=FROM.plusSeconds(hour*3600L),end=start.plusSeconds(3600);
            assertThat(day.stream().filter(r->r.reading().startInclusive().isBefore(end)&&r.reading().endExclusive().isAfter(start)).toList())
                    .containsExactlyElementsOf(service.reviewedIntervalReadings(1,ROLES,"B",start,end,AS_OF));
        }
        verify(repository,never()).currentApprovedWindowByIds(any(),any(),any(),any(),any());
        verify(authorization,atLeastOnce()).checkBuilding(1,ROLES,"B");
    }

    @Test void preservesMonthCrossDayAndBothOverlapEdgesWithoutClipping() throws Exception {
        insert("month","old","B",FROM.minusSeconds(86400),TO.plusSeconds(29*86400L),null);
        insert("left","old","B",FROM.minusSeconds(3600),FROM.plusSeconds(3600),null);
        insert("right","old","B",TO.minusSeconds(3600),TO.plusSeconds(3600),null);
        insert("before","old","B",FROM.minusSeconds(3600),FROM,null);
        insert("after","old","B",TO,TO.plusSeconds(3600),null);
        assertThat(read()).extracting(ReadingView::factId).containsExactly("month","left","right");
        assertThat(read().getFirst().reading().startInclusive()).isEqualTo(FROM.minusSeconds(86400));
    }

    @Test void approvalAndReplacementCutoffsAreIdenticalToOriginalReader() throws Exception {
        batch("late","B",AS_OF.plusMillis(1));batch("missing","B",null);batch("other","OTHER",AS_OF);
        insert("oldfact","old","B",FROM,FROM.plusSeconds(3600),null);
        insert("late-replacement","late","B",FROM,FROM.plusSeconds(3600),"oldfact");
        insert("null-review","missing","B",FROM,FROM.plusSeconds(3600),null);
        insert("other-building","other","OTHER",FROM,FROM.plusSeconds(3600),"oldfact");
        insert("replaced","old","B",FROM,FROM.plusSeconds(3600),null);
        insert("replacement","old","B",FROM,FROM.plusSeconds(3600),"replaced");
        batch("created-late","B",AS_OF.minusMillis(1));
        jdbc.update("UPDATE biz_energy_manual_batch SET created_at=? WHERE batch_id='created-late'",AS_OF.plusMillis(1).toEpochMilli());
        insert("created-late-fact","created-late","B",FROM,FROM.plusSeconds(3600),null);
        assertThat(read()).containsExactlyElementsOf(service.reviewedIntervalReadings(1,ROLES,"B",FROM,FROM.plusSeconds(3600),AS_OF));
        assertThat(read()).extracting(ReadingView::factId).containsExactly("oldfact","replacement");
    }

    @Test void metadataAndBodiesUseOneReadOnlyRepeatableReadTransaction() throws Exception {
        insert("one","old","B",FROM,TO,null);
        var transactionNames=new HashSet<String>();var calls=new AtomicInteger();
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            transactionNames.add(TransactionSynchronizationManager.getCurrentTransactionName());calls.incrementAndGet();
            return call.callRealMethod();
        }).when(repository).overlappingWindowCandidatesPage(any(),any(),any(),any(),any(),anyInt(),any());
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            transactionNames.add(TransactionSynchronizationManager.getCurrentTransactionName());calls.incrementAndGet();
            return call.callRealMethod();
        }).when(repository).approvedWindowByIdsAsOf(any(),any(),any(),any(),any(),any());
        assertThat(read()).hasSize(1);assertThat(calls.get()).isEqualTo(2);assertThat(transactionNames).hasSize(1);
    }

    @Test void rejectsNormalizedQuantityMismatch() throws Exception {
        insert("one","old","B",FROM,TO,null);
        jdbc.update("UPDATE biz_energy_manual_reading SET normalized_quantity=2");
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class).hasMessageContaining("固定用量");
    }

    @Test void concurrentBodyChangeAfterMetadataCannotMixSnapshots() throws Exception {
        insert("one","old","B",FROM,TO,null);
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            Object page=call.callRealMethod();
            if(changed.compareAndSet(false,true)) {
                // An independent H2 connection models a writer between metadata and exact-PK body reads.
                try(var connection=jdbc.getDataSource().getConnection();var statement=connection.createStatement()) {
                    statement.executeUpdate("UPDATE biz_energy_manual_reading SET normalized_quantity=2 WHERE fact_id='one'");
                }
            }
            return page;
        }).when(repository).overlappingWindowCandidatesPage(any(),any(),any(),any(),any(),anyInt(),any());
        assertThat(read()).singleElement().extracting(ReadingView::normalizedQuantity).isEqualTo(new BigDecimal("1.000000000000"));
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class).hasMessageContaining("固定用量");
    }

    @Test void cumulativeAndScaledQuantitiesUseOriginalNormalization() throws Exception {
        insert("one","old","B",FROM,FROM.plusSeconds(3600),null);
        var cumulative=new Reading("P",FROM,FROM.plusSeconds(3600),"ELECTRICITY","kWh","CUMULATIVE",
                new BigDecimal("10"),new BigDecimal("13"),null,new BigDecimal("2"),false,"CONFIRMED","test","EXACT",null);
        jdbc.update("UPDATE biz_energy_manual_reading SET reading_json=?,normalized_quantity=6",json.writeValueAsString(cumulative));
        assertThat(read()).containsExactlyElementsOf(service.reviewedIntervalReadings(1,ROLES,"B",FROM,FROM.plusSeconds(3600),AS_OF));
    }

    @Test void oversizedSqlBodyFailsBeforeDecodingAndNeverReturnsPartialResults() throws Exception {
        insert("one","old","B",FROM,TO,null);
        jdbc.update("UPDATE biz_energy_manual_reading SET reading_json=?","x".repeat(16385));
        assertThat(repository.approvedWindowByIdsAsOf("B",FROM,TO,AS_OF,List.of("one"),BUDGET).getFirst().reading().json()).isNull();
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class).hasMessageContaining("limit");
    }

    @Test void rejectsNonNaturalDayAndInvalidCutoff() {
        assertThatThrownBy(()->service.reviewedDayIntervalReadings(1,ROLES,"B",FROM.plusSeconds(1),TO.plusSeconds(1),AS_OF,BUDGET)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.reviewedDayIntervalReadings(1,ROLES,"B",FROM,TO,TO.minusMillis(1),BUDGET)).isInstanceOf(BusinessException.class);
        verify(repository,never()).overlappingWindowCandidatesPage(any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test void expiredBudgetAndDeniedIdentityNeverReadBodies() {
        assertThatThrownBy(()->service.reviewedDayIntervalReadings(1,ROLES,"B",FROM,TO,AS_OF,()->Duration.ZERO)).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        doThrow(new BusinessException(403,"denied")).when(authorization).checkBuilding(2,ROLES,"B");
        assertThatThrownBy(()->service.reviewedDayIntervalReadings(2,ROLES,"B",FROM,TO,AS_OF,BUDGET)).isInstanceOf(BusinessException.class);
        verify(repository,never()).overlappingWindowCandidatesPage(any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test void unrelatedHistoryIsFilteredAndOverlappingCandidatesStillFailAtHardBound() throws Exception {
        for(int i=0;i<4;i++)insert("old"+i,"old","B",FROM.minusSeconds(7200),FROM.minusSeconds(3600),null);
        assertThat(bounded(new ReviewedDayReadings.Limits(2,3,5,4096,16384))).isEmpty();
        verify(repository,never()).approvedWindowByIdsAsOf(any(),any(),any(),any(),any(),any());
        for(int i=0;i<4;i++)insert("relevant"+i,"old","B",FROM.minusSeconds(3600),FROM.plusSeconds(3600),null);
        assertThatThrownBy(()->bounded(new ReviewedDayReadings.Limits(2,3,5,4096,16384))).isInstanceOf(BusinessException.class).hasMessageContaining("limit");
        assertThat(ReviewedDayReadings.DEFAULT_LIMITS.maximumCandidates()).isEqualTo(1_000_000);
    }

    @Test void repeatedHistoricalIdsAndDecreasingTimestampsRejectCursorCorruption() {
        Instant old=FROM.minusSeconds(7200),end=FROM.minusSeconds(3600);
        doReturn(List.of(new ClassificationRepository.WindowCandidate("a",old,end),
                new ClassificationRepository.WindowCandidate("b",old,end),
                new ClassificationRepository.WindowCandidate("a",old,end)))
                .when(repository).overlappingWindowCandidatesPage(any(),any(),any(),any(),any(),anyInt(),any());
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class).hasMessageContaining("inconsistent");
        doReturn(List.of(new ClassificationRepository.WindowCandidate("a",old,end),
                new ClassificationRepository.WindowCandidate("b",old.minusSeconds(1),end)))
                .when(repository).overlappingWindowCandidatesPage(any(),any(),any(),any(),any(),anyInt(),any());
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class).hasMessageContaining("inconsistent");
        verify(repository,never()).approvedWindowByIdsAsOf(any(),any(),any(),any(),any(),any());
    }

    @Test void mixedCaseIdsDoNotRequireJavaLexicalCursorOrder() {
        Instant old=FROM.minusSeconds(7200),end=FROM.minusSeconds(3600);
        // Valid case-insensitive database order differs from Java's uppercase-before-lowercase ordering.
        doReturn(List.of(new ClassificationRepository.WindowCandidate("a",old,end),
                new ClassificationRepository.WindowCandidate("B",old,end)))
                .when(repository).overlappingWindowCandidatesPage(any(),any(),any(),any(),any(),anyInt(),any());
        assertThat(read()).isEmpty();
    }

    @Test void rowAndTotalByteBoundsNeverReturnPartialDay() throws Exception {
        insert("a","old","B",FROM,TO,null);insert("b","old","B",FROM,TO,null);
        assertThatThrownBy(()->bounded(new ReviewedDayReadings.Limits(1,10,1,10000,16384))).isInstanceOf(BusinessException.class).hasMessageContaining("limit");
        assertThatThrownBy(()->bounded(new ReviewedDayReadings.Limits(1,10,10,1,16384))).isInstanceOf(BusinessException.class).hasMessageContaining("limit");
    }

    @Test void deadlineIsCheckedWhileProcessingRowsAndAfterLastPage() throws Exception {
        insert("a","old","B",FROM,TO,null);
        var available=new java.util.concurrent.atomic.AtomicReference<>(Duration.ofSeconds(30));
        assertThatThrownBy(()->ReviewedDayReadings.read(repository,"B",FROM,TO,AS_OF,available::get,row->{
            available.set(Duration.ZERO);return view(row);
        })).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    }

    private List<ReadingView> bounded(ReviewedDayReadings.Limits limits) {
        return ReviewedDayReadings.read(repository,"B",FROM,TO,AS_OF,BUDGET,this::view,limits);
    }
    private ReadingView view(ClassificationRepository.StoredReading row) {
        try{return new ReadingView(row.id(),row.batchId(),json.readValue(row.json(),Reading.class),row.quantity(),row.author(),row.batchStatus(),null);}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private List<ReadingView> read(){return service.reviewedDayIntervalReadings(1,ROLES,"B",FROM,TO,AS_OF,BUDGET);}
    private void batch(String id,String building,Instant reviewed){jdbc.update("INSERT INTO biz_energy_manual_batch VALUES (?,?,'APPROVED',?,?)",id,building,FROM.toEpochMilli(),reviewed==null?null:reviewed.toEpochMilli());}
    private void insert(String id,String batch,String building,Instant from,Instant to,String supersedes) throws Exception {
        jdbc.update("INSERT INTO biz_energy_manual_reading VALUES (?,?,?,?,?,?,?,?,?)",id,batch,building,from.toEpochMilli(),to.toEpochMilli(),json.writeValueAsString(reading("P",from,to)),BigDecimal.ONE,1,supersedes);
    }
    private Reading reading(String point,Instant from,Instant to){return new Reading(point,from,to,"ELECTRICITY","kWh","PERIOD_TOTAL",null,null,BigDecimal.ONE,BigDecimal.ONE,false,"CONFIRMED","test","EXACT",null);}
}
