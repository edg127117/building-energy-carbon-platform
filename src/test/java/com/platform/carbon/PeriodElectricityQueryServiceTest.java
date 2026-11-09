package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实任务事务验证竞争与读取边界；业务计算使用替身，不连接外部资源。 */
class PeriodElectricityQueryServiceTest {
    JdbcTemplate jdbc;
    CarbonDisplayQueryRepository repo;
    DisplaySourceRevisionRepository revisions;
    PeriodElectricityQueryService service;
    DynamicElectricityService dynamic=mock(DynamicElectricityService.class);
    DailyElectricityCarbonService daily=mock(DailyElectricityCarbonService.class);
    CarbonAuthorization authorization=mock(CarbonAuthorization.class);
    PeriodElectricityQueryService.Query year=PeriodElectricityQueryService.validate("B","ELECTRICITY_YEAR","2025");
    List<String> roles=List.of("ENERGY_MANAGER");
    @BeforeEach void setup()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:period-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute(Files.readString(Path.of("src/env/init/V103__mysql_carbon_display_read_model.sql")).replaceAll("(?s)ENGINE=InnoDB.*?;",";"));
        jdbc.execute(Files.readString(Path.of("src/env/init/V102__mysql_daily_trend_read_model.sql")).replaceAll("(?s)ENGINE=InnoDB.*?;",";"));
        jdbc.execute("CREATE TABLE biz_carbon_display_source_revision(building_id VARCHAR(32),domain_key VARCHAR(32),scope_key VARCHAR(16),revision BIGINT NOT NULL,PRIMARY KEY(building_id,domain_key,scope_key))");
        jdbc.execute("CREATE TABLE biz_dynamic_electricity_chart_projection(result_id VARCHAR(64),building_id VARCHAR(32),start_ms BIGINT,end_ms BIGINT,source_kind VARCHAR(32))");
        revisions=new DisplaySourceRevisionRepository(jdbc,new DataSourceTransactionManager(ds));
        repo=new CarbonDisplayQueryRepository(jdbc,new CarbonDisplayQueryProperties());
        service=new PeriodElectricityQueryService(authorization,repo,revisions,dynamic,daily,new ObjectMapper().findAndRegisterModules());
        for(var point:view().points())when(dynamic.queryYearMonth(1L,roles,"B",YearMonth.parse(point.period()))).thenReturn(point);
    }
    DynamicElectricityContracts.View view() {
        var points=new ArrayList<DynamicElectricityContracts.Point>();
        for(int month=1;month<=12;month++) {
            var ym=YearMonth.of(2025,month);var start=ym.atDay(1).atStartOfDay(PeriodElectricityQueryService.ZONE).toInstant();
            var end=ym.plusMonths(1).atDay(1).atStartOfDay(PeriodElectricityQueryService.ZONE).toInstant();
            points.add(new DynamicElectricityContracts.Point(ym.toString(),start,end,"MISSING","COMPLETE_RESULT_REQUIRED",null,null,null,List.of(),List.of(),List.of()));
        }
        return new DynamicElectricityContracts.View("B","YEAR","2025","Asia/Shanghai","tCO2","kgCO2/kWh","MONITORING",points);
    }
    void build() {
        for(var point:view().points()) {
            var leaf=PeriodElectricityQueryService.validate("B","ELECTRICITY_YEAR_MONTH",point.period());
            service.prepare(leaf,1);var h=repo.head(leaf.key());
            assertThat(service.rebuild(h,repo.claim(h,System.currentTimeMillis()),roles)).isTrue();
        }
        service.prepare(year,1);var head=repo.head(year.key());
        assertThat(head).isNotNull();String token=repo.claim(head,System.currentTimeMillis());
        assertThat(service.rebuild(head,token,roles)).isTrue();
    }
    @Test void coldRequestRegistersOnlyAndReadyReadSurvivesServiceRestart() {
        assertThatThrownBy(()->service.dynamic(1,roles,"B","YEAR","2025")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("PERIOD_DISPLAY_PREPARING"));
        verifyNoInteractions(dynamic,daily);
        build();clearInvocations(dynamic,daily);
        assertThat(service.dynamic(1,roles,"B","YEAR","2025")).isEqualTo(view());
        var restarted=new PeriodElectricityQueryService(authorization,repo,revisions,dynamic,daily,new ObjectMapper().findAndRegisterModules());
        assertThat(restarted.dynamic(1,roles,"B","YEAR","2025")).isEqualTo(view());
        verifyNoInteractions(dynamic,daily);
    }
    @Test void preparingPollDoesNotWriteOrTakePublishLocksAgain() {
        service.prepare(year,1);var head=repo.head(year.key());
        String token=repo.claim(head,System.currentTimeMillis());
        var observed=spy(revisions);
        var polling=new PeriodElectricityQueryService(authorization,repo,observed,dynamic,daily,new ObjectMapper().findAndRegisterModules());
        assertThatThrownBy(()->polling.dynamic(1,roles,"B","YEAR","2025"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("PERIOD_DISPLAY_PREPARING"));
        verify(observed,never()).ifCurrent(anyString(),anyString(),any(Instant.class),any(Instant.class),anyString(),any(Runnable.class));
        assertThat(repo.head(year.key()).token()).isEqualTo(token);
    }
    @Test void coldAndChangedRequestsDoNotAcquireSourcePublicationLocks() {
        var observed=spy(revisions);
        var reader=new PeriodElectricityQueryService(authorization,repo,observed,dynamic,daily,new ObjectMapper().findAndRegisterModules());
        assertThatThrownBy(()->reader.dynamic(1,roles,"B","YEAR","2025")).isInstanceOf(BusinessException.class);
        jdbc.update("INSERT INTO biz_carbon_display_source_revision VALUES ('B','DYNAMIC_INPUT','M:2025-01',1)");
        assertThatThrownBy(()->reader.dynamic(1,roles,"B","YEAR","2025")).isInstanceOf(BusinessException.class);
        assertThat(repo.head(year.key()).source()).isEqualTo(reader.current(year));
        verify(observed,never()).ifCurrent(anyString(),anyString(),any(Instant.class),any(Instant.class),anyString(),any(Runnable.class));
        var tasks=spy(repo);var preparer=new PeriodElectricityQueryService(authorization,tasks,observed,dynamic,daily,new ObjectMapper().findAndRegisterModules());
        preparer.prepare(year,1);
        verify(tasks,never()).requestPeriod(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyLong(),any());
    }
    @Test void delayedRegistrationCannotReplaceNewerTaskOrResetItsLease() {
        service.prepare(year,1);var old=repo.head(year.key());
        jdbc.update("INSERT INTO biz_carbon_display_source_revision VALUES ('B','DYNAMIC_INPUT','M:2025-01',1)");
        service.prepare(year,1);var newer=repo.head(year.key());String token=repo.claim(newer,System.currentTimeMillis());
        repo.requestPeriod(year.key(),"B",year.kind(),old.query(),2,"delayed-version",System.currentTimeMillis(),old);
        repo.requestPeriod(year.key(),"B",year.kind(),old.query(),2,"delayed-first-request",System.currentTimeMillis(),null);
        assertThat(repo.head(year.key()).source()).isEqualTo(newer.source());
        assertThat(repo.head(year.key()).token()).isEqualTo(token);
        assertThat(repo.head(year.key()).user()).isEqualTo(1);
    }
    void changeJanuary() {jdbc.update("UPDATE biz_carbon_display_source_revision SET revision=revision+1 WHERE building_id='B' AND domain_key='DYNAMIC_INPUT' AND scope_key='M:2025-01'");}
    @Test void committedChangeRejectsOldSnapshotAndRebuildRaceCannotPublish() {
        build();changeJanuary();
        assertThatThrownBy(()->service.dynamic(1,roles,"B","YEAR","2025")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("PERIOD_DISPLAY_PREPARING"));
        var h=repo.head(year.key());String token=repo.claim(h,System.currentTimeMillis());
        assertThat(service.rebuild(h,token,roles)).isFalse();
        assertThat(repo.head(year.key()).state()).isEqualTo("PENDING");
        var january=PeriodElectricityQueryService.validate("B","ELECTRICITY_YEAR_MONTH","2025-01");
        when(dynamic.queryYearMonth(1L,roles,"B",YearMonth.of(2025,1))).thenAnswer(c->{changeJanuary();return view().points().getFirst();});
        var leaf=repo.head(january.key());
        assertThat(service.rebuild(leaf,repo.claim(leaf,System.currentTimeMillis()),roles)).isFalse();
        assertThat(repo.head(january.key()).state()).isEqualTo("PENDING");
    }
    @Test void corruptionCannotFallBackToHeavyQueryOrOldResult() {
        build();clearInvocations(dynamic);
        jdbc.update("UPDATE biz_carbon_display_read_model SET result_json='{}' WHERE query_key=?",year.key());
        assertThatThrownBy(()->service.dynamic(1,roles,"B","YEAR","2025")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("PERIOD_DISPLAY_INTEGRITY"));
        verifyNoInteractions(dynamic);
    }
    @Test void lostLeaseCannotPublishAndUnrelatedMonthDoesNotInvalidateYear() {
        build();String before=service.current(year);
        jdbc.update("INSERT INTO biz_carbon_display_source_revision VALUES ('B','DYNAMIC_INPUT','M:2024-12',1)");
        assertThat(service.current(year)).isEqualTo(before);
        changeJanuary();service.prepare(year,1);var h=repo.head(year.key());String old=repo.claim(h,1);
        String replacement=repo.claim(h,System.currentTimeMillis());assertThat(replacement).isNotEqualTo(old);
        assertThat(service.rebuild(h,old,roles)).isFalse();
        assertThat(repo.head(year.key()).token()).isEqualTo(replacement);
    }
    @Test void authorizationRunsBeforeReadingOrPreparing() {
        doThrow(new BusinessException(403,"denied")).when(authorization).requireReader(1,roles,"B");
        assertThatThrownBy(()->service.dynamic(1,roles,"B","YEAR","2025")).isInstanceOf(BusinessException.class);
        assertThat(repo.head(year.key())).isNull();verifyNoInteractions(dynamic,daily);
    }
    @Test void annualRefreshReusesUnchangedMonthsAndNeverRecomputesWholeYear() {
        build();clearInvocations(dynamic);changeJanuary();service.prepare(year,1);
        var h=repo.head(year.key());assertThat(service.rebuild(h,repo.claim(h,System.currentTimeMillis()),roles)).isFalse();
        verifyNoInteractions(dynamic);
        var january=PeriodElectricityQueryService.validate("B","ELECTRICITY_YEAR_MONTH","2025-01");
        var leaf=repo.head(january.key());assertThat(service.rebuild(leaf,repo.claim(leaf,System.currentTimeMillis()),roles)).isTrue();
        h=repo.head(year.key());assertThat(service.rebuild(h,repo.claim(h,System.currentTimeMillis()),roles)).isTrue();
        assertThat(service.dynamic(1,roles,"B","YEAR","2025")).isEqualTo(view());
        verify(dynamic).queryYearMonth(1L,roles,"B",YearMonth.of(2025,1));verifyNoMoreInteractions(dynamic);
    }
    @Test void rawChangesOnlyInvalidatePeriodsThatReferenceRawResults() {
        build();String reviewed=service.current(year);
        jdbc.update("INSERT INTO biz_carbon_display_source_revision VALUES ('B','DYNAMIC_RAW','U:2025-01',1)");
        assertThat(service.current(year)).isEqualTo(reviewed);
        assertThat(service.dynamic(1,roles,"B","YEAR","2025")).isEqualTo(view());
        jdbc.update("INSERT INTO biz_dynamic_electricity_chart_projection VALUES ('raw','B',?,?,'RAW_CUMULATIVE')",year.from().toEpochMilli(),year.from().plusSeconds(3600).toEpochMilli());
        String raw=service.current(year);assertThat(raw).isNotEqualTo(reviewed);
        jdbc.update("UPDATE biz_carbon_display_source_revision SET revision=2 WHERE domain_key='DYNAMIC_RAW'");
        assertThat(service.current(year)).isNotEqualTo(raw);
        assertThatThrownBy(()->service.dynamic(1,roles,"B","YEAR","2025")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("PERIOD_DISPLAY_PREPARING"));
    }
    @Test void internalYearMonthLeafIsNotAPublicQueryType() {
        assertThatThrownBy(()->service.dynamic(1,roles,"B","YEAR_MONTH","2025-01")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(400));
        verifyNoInteractions(dynamic,daily);
    }
    final PeriodElectricityQueryService.Query month=PeriodElectricityQueryService.validate("B","DAILY_TREND_DETAIL","2025-02");
    DailyElectricityCarbonContracts.View prepareDailyLeaves()throws Exception {
        var days=new ArrayList<DailyElectricityCarbonContracts.Day>();var mapper=new ObjectMapper().findAndRegisterModules();
        for(int day=1;day<=28;day++) {
            var date=LocalDate.of(2025,2,day);var leaf=PeriodElectricityQueryService.validate("B","DAILY_TREND_DAY",date.toString());
            var value=new DailyElectricityCarbonContracts.Day(date,leaf.from(),leaf.to(),null,"MISSING","NO_SOURCE",null,null,List.of("complete-evidence-"+day),null,null,"MISSING",null,"MISSING",null,null,null,List.of(),null,"UNKNOWN");
            days.add(value);service.prepare(leaf,1);var h=repo.head(leaf.key());String payload=mapper.writeValueAsString(value);
            assertThat(repo.publish(h,repo.claim(h,System.currentTimeMillis()),payload,CarbonCalculationCore.sha256(leaf.key()+":"+payload),System.currentTimeMillis())).isTrue();
        }
        var view=new DailyElectricityCarbonContracts.View("B","2025-02","Asia/Shanghai","READY","kWh","tCO2e","kgCO2e/kWh","MONITORING","DETAIL",days,Instant.parse("2025-03-01T00:00:00Z"),"evidence");
        when(daily.assemble(eq("B"),eq(YearMonth.of(2025,2)),anyList())).thenReturn(view);
        service.prepare(month,1);return view;
    }
    @Test void summaryIsPublishedWithDetailAndReadsNoLargeDetailPayload()throws Exception {
        var view=prepareDailyLeaves();var h=repo.head(month.key());
        assertThat(service.rebuild(h,repo.claim(h,System.currentTimeMillis()),roles)).isTrue();
        var observed=spy(repo);var reader=new PeriodElectricityQueryService(authorization,observed,revisions,dynamic,daily,new ObjectMapper().findAndRegisterModules());
        assertThat(reader.summary(1,roles,"B","2025-02")).isEqualTo(DailyTrendSummaryContracts.from(view,h.source()));
        verify(observed,never()).payload(any());
        assertThat(reader.detail(1,roles,"B","2025-02")).isEqualTo(view);
        jdbc.update("UPDATE biz_carbon_display_source_revision SET revision=revision+1 WHERE domain_key='DAILY_INPUT' AND scope_key='M:2025-02'");
        assertThat(reader.summary(1,roles,"B","2025-02").state()).isEqualTo("PREPARING");
    }
    @Test void existingDetailBackfillsSummaryOnlyInWorkerAndCorruptSummaryIsRejected()throws Exception {
        prepareDailyLeaves();var h=repo.head(month.key());service.rebuild(h,repo.claim(h,System.currentTimeMillis()),roles);
        jdbc.update("DELETE FROM biz_daily_trend_read_model");
        var observed=spy(repo);var reader=new PeriodElectricityQueryService(authorization,observed,revisions,dynamic,daily,new ObjectMapper().findAndRegisterModules());
        assertThat(reader.summary(1,roles,"B","2025-02").state()).isEqualTo("PREPARING");verify(observed,never()).payload(any());
        reader.prepareSummary(repo.head(month.key()));assertThat(reader.summary(1,roles,"B","2025-02").days()).hasSize(28);
        jdbc.update("UPDATE biz_daily_trend_read_model SET summary_json='{}'");
        assertThatThrownBy(()->reader.summary(1,roles,"B","2025-02")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("PERIOD_DISPLAY_INTEGRITY"));
    }
    @Test void summaryPublicationFailureRollsBackDetailPublication()throws Exception {
        prepareDailyLeaves();var h=repo.head(month.key());String token=repo.claim(h,System.currentTimeMillis());var failing=spy(repo);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("summary storage unavailable")).when(failing).publishSummary(any(),anyString(),anyString(),anyString(),anyLong());
        var publisher=new PeriodElectricityQueryService(authorization,failing,revisions,dynamic,daily,new ObjectMapper().findAndRegisterModules());
        assertThatThrownBy(()->publisher.rebuild(h,token,roles)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repo.head(month.key()).state()).isEqualTo("RUNNING");assertThat(repo.head(month.key()).built()).isNull();
        assertThat(repo.summary(repo.head(month.key()),month.period())).isNull();
    }
}
