package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.platform.carbon.CurrentReviewedDayVersionRead.*;
import static com.platform.carbon.DynamicElectricityRepository.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 独立 H2 核验摘要等价、分页顺序和真实 RR 连接边界，不连接实际业务数据库。 */
class CurrentReviewedDayVersionReadTest {
    private static final Instant FROM=Instant.parse("2026-08-31T00:00:00Z"),TO=FROM.plusSeconds(86400);
    private static final Supplier<Duration> BUDGET=()->Duration.ofSeconds(30);
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    protected DynamicElectricityRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach void setup() {
        source=new DriverManagerDataSource("jdbc:h2:mem:current-day-"+UUID.randomUUID()
                +";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);
        // 数据库排序故意不同于 String.compareTo，防止分页后重新按 Java 排序改变旧摘要。
        jdbc.execute("SET COLLATION ENGLISH STRENGTH PRIMARY");
        jdbc.execute("CREATE TABLE biz_energy_manual_batch(batch_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),"
                +"status VARCHAR(30),revision INT,reviewed_at BIGINT)");
        jdbc.execute("CREATE TABLE biz_energy_manual_reading(fact_id VARCHAR(32) PRIMARY KEY,batch_id VARCHAR(32),"
                +"building_id VARCHAR(32),point_id VARCHAR(80),start_inclusive BIGINT,end_exclusive BIGINT,"
                +"reading_json CLOB,normalized_quantity DECIMAL(38,12),supersedes_fact_id VARCHAR(32))");
        jdbc.execute("CREATE INDEX idx_energy_manual_point_period ON biz_energy_manual_reading"
                +"(building_id,point_id,start_inclusive,end_exclusive)");
        jdbc.execute("CREATE INDEX idx_energy_manual_supersedes ON biz_energy_manual_reading"
                +"(building_id,supersedes_fact_id,batch_id)");
        batch("approved","B","APPROVED",7,TO.plusSeconds(86400).toEpochMilli());
        repository=spy(new DynamicElectricityRepository(jdbc,new ObjectMapper().findAndRegisterModules()));
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @AfterEach void shutdown() throws Exception {
        // JdbcTemplate 的 DEBUG 警告读取会在 SHUTDOWN 后访问已关闭连接；原生 JDBC 直接关闭独立测试库。
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    @Test void complete219PointDayMatchesAll24OriginalHashesWithBoundedReads() {
        var values=new ArrayList<Object[]>();
        for(int hour=0;hour<24;hour++)for(int point=0;point<219;point++) {
            long start=FROM.plusSeconds(hour*3600L).toEpochMilli();
            values.add(new Object[]{"F"+hour+"_"+point,"approved","B","P"+point,start,start+3_600_000,
                    "{\"point\":\"测点"+point+"\",\"value\":1.234567890123}",new BigDecimal("1.234567890123"),null});
        }
        jdbc.batchUpdate("INSERT INTO biz_energy_manual_reading VALUES (?,?,?,?,?,?,?,?,?)",values);

        transaction.executeWithoutResult(status->{
            var snapshot=repository.loadCurrentReviewedDay("B",FROM,TO,BUDGET).orElseThrow();
            for(int hour=0;hour<24;hour++) {
                Instant start=FROM.plusSeconds(hour*3600L),end=start.plusSeconds(3600);
                assertThat(snapshot.version(start,end)).isEqualTo(repository.electricityVersion("B",start,end,"REVIEWED_INTERVAL"));
            }
        });

        var limits=repository.currentReviewedDayLimits();
        verify(repository,times(limits.metadataPageSize()==1000?6:1))
                .currentReviewedDayCandidates(eq("B"),eq(FROM),eq(TO),any(),eq(limits.metadataPageSize()),same(BUDGET));
        @SuppressWarnings("unchecked") var batches=(org.mockito.ArgumentCaptor<List<String>>)(Object)
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository,times(limits.bodyPageSize()==256?21:6)).currentReviewedDayBodies(eq("B"),eq(FROM),eq(TO),batches.capture(),same(BUDGET));
        assertThat(batches.getAllValues()).allSatisfy(ids->assertThat(ids.size()).isBetween(1,limits.bodyPageSize()));
        assertThat(batches.getAllValues().stream().mapToInt(List::size).sum()).isEqualTo(5256);
        assertThat(DEFAULT_LIMITS).isEqualTo(new Limits(1000,256,1_000_000,120_000,32L*1024*1024,16384));
    }

    @Test void currentApprovalReplacementAndBuildingJoinsKeepExactOriginalSemantics() {
        batch("draft","B","DRAFT",8,TO.toEpochMilli());
        batch("foreign","OTHER","APPROVED",9,TO.toEpochMilli());
        batch("null-review","B","APPROVED",null,null);
        fact("outside-replaced","approved","B",FROM,TO,null);
        fact("outside-replacement","approved","B",TO,TO.plusSeconds(3600),"outside-replaced");
        fact("pending-parent","approved","B",FROM,TO,null);
        fact("pending-child","draft","B",FROM,TO,"pending-parent");
        fact("foreign-parent","approved","B",FROM,TO,null);
        fact("foreign-child","foreign","OTHER",FROM,TO,"foreign-parent");
        fact("bad-batch-join","foreign","B",FROM,TO,null);
        fact("bad-replacement-parent","approved","B",FROM,TO,null);
        fact("bad-replacement-child","foreign","B",FROM,TO,"bad-replacement-parent");
        fact("null-approved","null-review","B",FROM,TO,null);
        fact("replaced","approved","B",FROM,TO,null);
        fact("replacement","approved","B",FROM,TO,"replaced");

        var snapshot=read(repository.currentReviewedDayLimits()).orElseThrow();
        assertThat(snapshot.version(FROM,TO)).isEqualTo(repository.electricityVersion("B",FROM,TO,"REVIEWED_INTERVAL"));
        var actual=repository.currentReviewedDayBodies("B",FROM,TO,
                List.of("outside-replaced","pending-parent","foreign-parent","bad-batch-join","bad-replacement-parent",
                        "bad-replacement-child","null-approved","replaced","replacement"),BUDGET);
        assertThat(actual).extracting(row->row.candidate().id()).containsExactly(
                "bad-replacement-parent","foreign-parent","null-approved","pending-parent","replacement");
    }

    @Test void crossDayAndMonthOverlapAndDatabaseOrderMatchEveryHourAcrossPages() {
        fact("a2","approved","B",FROM.minusSeconds(86400),TO.plusSeconds(29*86400L),null);
        fact("B1","approved","B",FROM.minusSeconds(3600),FROM.plusSeconds(3600),null);
        fact("b3","approved","B",TO.minusSeconds(3600),TO.plusSeconds(3600),null);
        fact("C4","approved","B",FROM,TO,null);
        fact("ä5","approved","B",FROM.plusSeconds(3600),TO,null);
        fact("z6","approved","B",FROM,TO,null);
        fact("touch-before","approved","B",FROM.minusSeconds(3600),FROM,null);
        fact("touch-after","approved","B",TO,TO.plusSeconds(3600),null);
        var snapshot=read(new Limits(2,1,30,30,10000,16384)).orElseThrow();
        for(int hour=0;hour<24;hour++) {
            Instant start=FROM.plusSeconds(hour*3600L),end=start.plusSeconds(3600);
            assertThat(snapshot.version(start,end)).isEqualTo(repository.electricityVersion("B",start,end,"REVIEWED_INTERVAL"));
        }
        assertThat(jdbc.queryForList("SELECT fact_id FROM biz_energy_manual_reading ORDER BY fact_id",String.class))
                .isNotEqualTo(jdbc.queryForList("SELECT fact_id FROM biz_energy_manual_reading ORDER BY fact_id",String.class)
                        .stream().sorted().toList());
        assertThatThrownBy(()->snapshot.version(FROM.minusMillis(1),TO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sixOriginalColumnNamesAndJdbcTypesIncludeDecimalsUnicodeAndNulls() {
        fact("unicode","approved","B",FROM,TO,null);
        jdbc.update("UPDATE biz_energy_manual_reading SET reading_json=?,normalized_quantity=? WHERE fact_id='unicode'",
                "{\"label\":\"冷却水😀\",\"raw\":1.000}",new BigDecimal("219.123456789012"));
        batch("nullable","B","APPROVED",null,null);
        fact("nulls","nullable","B",FROM,TO,null);
        jdbc.update("UPDATE biz_energy_manual_reading SET normalized_quantity=NULL,reading_json=NULL WHERE fact_id='nulls'");
        var original=jdbc.queryForList("SELECT r.fact_id,r.point_id,r.reading_json,r.normalized_quantity,b.revision,b.reviewed_at "
                +"FROM biz_energy_manual_reading r JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id ORDER BY r.fact_id");
        var rows=repository.currentReviewedDayBodies("B",FROM,TO,List.of("unicode","nulls"),BUDGET);
        assertThat(rows).extracting(Fact::fields).containsExactlyElementsOf(original);
        assertThat(rows.getLast().fields().get("normalized_quantity")).isInstanceOf(BigDecimal.class);
        assertThat(rows.getLast().fields().get("revision")).isInstanceOf(Integer.class);
        assertThat(rows.getLast().fields().get("reviewed_at")).isInstanceOf(Long.class);
        assertThat(rows.getFirst().fields().get("normalized_quantity")).isNull();
        assertThatThrownBy(()->rows.getFirst().fields().put("extra",1)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(read(repository.currentReviewedDayLimits()).orElseThrow().version(FROM,TO)).isEqualTo(hash(encode(original)));
    }

    @Test void rowsJsonBytesAndBodyCapsDiscardAllPartialResultsAndLeaveOriginalFallbackAvailable() {
        fact("a","approved","B",FROM,TO,null);fact("b","approved","B",FROM,TO,null);
        assertThat(read(new Limits(2,1,10,1,1000,16384))).isEmpty();
        assertThat(read(new Limits(2,1,10,10,1,16384))).isEmpty();
        jdbc.update("UPDATE biz_energy_manual_reading SET reading_json=? WHERE fact_id='b'","测".repeat(5462));
        var body=repository.currentReviewedDayBodies("B",FROM,TO,List.of("b"),BUDGET).getFirst();
        assertThat(body.bytes()).isEqualTo(16386);
        assertThat(body.fields().get("reading_json")).isNull();
        assertThat(read(repository.currentReviewedDayLimits())).isEmpty();
        assertThat(repository.electricityVersion("B",FROM,TO,"REVIEWED_INTERVAL")).isNotEqualTo(hash("[]"));
        jdbc.update("UPDATE biz_energy_manual_reading SET reading_json=? WHERE fact_id='b'","x".repeat(16384));
        assertThat(read(repository.currentReviewedDayLimits()).orElseThrow().version(FROM,TO))
                .isEqualTo(repository.electricityVersion("B",FROM,TO,"REVIEWED_INTERVAL"));
    }

    @Test void candidateCapCountsUnapprovedAndSupersededCandidatesBeforeBodyReads() {
        batch("draft","B","DRAFT",8,null);
        fact("a","draft","B",FROM,TO,null);fact("b","draft","B",FROM,TO,null);
        assertThat(read(new Limits(2,1,1,10,1000,16384))).isEmpty();
        verify(repository,never()).currentReviewedDayBodies(any(),any(),any(),any(),any());
        assertThat(repository.electricityVersion("B",FROM,TO,"REVIEWED_INTERVAL")).isEqualTo(hash("[]"));
    }

    @Test void metadataAndBodiesStayOnOneReadOnlyRepeatableReadConnectionDuringConcurrentApprovalChange() {
        fact("one","approved","B",FROM,TO,null);
        var changed=new AtomicBoolean();var pinned=new AtomicReference<java.sql.Connection>();
        doAnswer(call->{
            assertTransaction(pinned);
            Object page=call.callRealMethod();
            if(changed.compareAndSet(false,true)) {
                try(var connection=source.getConnection();var statement=connection.createStatement()) {
                    statement.executeUpdate("UPDATE biz_energy_manual_batch SET status='DRAFT',revision=99 WHERE batch_id='approved'");
                }
            }
            return page;
        }).when(repository).currentReviewedDayCandidates(any(),any(),any(),any(),anyInt(),any());
        doAnswer(call->{assertTransaction(pinned);return call.callRealMethod();})
                .when(repository).currentReviewedDayBodies(any(),any(),any(),any(),any());
        transaction.executeWithoutResult(status->{
            String before=repository.electricityVersion("B",FROM,TO,"REVIEWED_INTERVAL");
            var snapshot=repository.loadCurrentReviewedDay("B",FROM,TO,BUDGET).orElseThrow();
            assertThat(snapshot.version(FROM,TO)).isEqualTo(before);
            assertThat(repository.electricityVersion("B",FROM,TO,"REVIEWED_INTERVAL")).isEqualTo(before);
        });
        assertThat(changed).isTrue();assertThat(pinned.get()).isNotNull();
        assertThat(repository.electricityVersion("B",FROM,TO,"REVIEWED_INTERVAL")).isEqualTo(hash("[]"));
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
    }

    @Test void emptyDayIsAnAvailableEmptyHashAndExpiredBudgetThrows() {
        assertThat(read(repository.currentReviewedDayLimits()).orElseThrow().version(FROM,TO)).isEqualTo(hash("[]"));
        clearInvocations(repository);
        assertThatThrownBy(()->repository.loadCurrentReviewedDay("B",FROM,TO,()->Duration.ZERO))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verify(repository,never()).currentReviewedDayCandidates(any(),any(),any(),any(),anyInt(),any());
    }

    @Test void fullFinalMetadataPageNeedsAnEmptyPageButExactCandidateCapDoesNotReadASentinel() {
        fact("a","approved","B",FROM,TO,null);fact("b","approved","B",FROM,TO,null);
        assertThat(read(new Limits(2,2,3,10,1000,16384))).isPresent();
        verify(repository).currentReviewedDayCandidates("B",FROM,TO,null,2,BUDGET);
        verify(repository).currentReviewedDayCandidates("B",FROM,TO,"b",1,BUDGET);
        clearInvocations(repository);
        assertThat(read(new Limits(2,2,2,10,1000,16384))).isEmpty();
        verify(repository,times(1)).currentReviewedDayCandidates(any(),any(),any(),any(),anyInt(),any());
        verify(repository,never()).currentReviewedDayBodies(any(),any(),any(),any(),any());
    }

    @Test void deadlineFailureReleasesReadOnlyTransactionResources() {
        fact("one","approved","B",FROM,TO,null);
        doThrow(new org.springframework.dao.QueryTimeoutException("expired body read"))
                .when(repository).currentReviewedDayBodies(any(),any(),any(),any(),any());
        assertThatThrownBy(()->transaction.execute(status->
                repository.loadCurrentReviewedDay("B",FROM,TO,BUDGET)))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(repository,never()).electricityVersion(any(),any(),any(),any());
    }

    private void assertTransaction(AtomicReference<java.sql.Connection> pinned) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        var connection=((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).getConnection();
        pinned.compareAndSet(null,connection);assertThat(connection).isSameAs(pinned.get());
        assertThat(repository.currentReviewedDayDataSource()).isSameAs(source);
    }

    private Optional<DaySnapshot> read(Limits limits) {
        return transaction.execute(status->CurrentReviewedDayVersionRead.read(repository,"B",FROM,TO,BUDGET,limits));
    }

    private void batch(String id,String building,String status,Integer revision,Long reviewed) {
        jdbc.update("INSERT INTO biz_energy_manual_batch VALUES (?,?,?,?,?)",id,building,status,revision,reviewed);
    }

    private void fact(String id,String batch,String building,Instant start,Instant end,String supersedes) {
        jdbc.update("INSERT INTO biz_energy_manual_reading VALUES (?,?,?,?,?,?,?,?,?)",id,batch,building,"P-"+id,
                start.toEpochMilli(),end.toEpochMilli(),"{\"quantity\":1}",BigDecimal.ONE,supersedes);
    }
}
