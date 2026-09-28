package com.platform.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.weather.source.WeatherSourceModels.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.platform.weather.WeatherModels.*;
import static org.assertj.core.api.Assertions.*;

class WeatherRepositoryTest {
    JdbcTemplate jdbc;WeatherRepository repo;
    LocalDate day=LocalDate.of(2026,9,20);
    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE building(building_id VARCHAR(32) PRIMARY KEY)");jdbc.update("INSERT INTO building VALUES('building')");
        String migration=Files.readString(Path.of("src/env/init/V64__mysql_weather_backend.sql")).replace("\uFEFF","").replaceAll("(?m)--[^\\r\\n]*","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        for(String statement:migration.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        String auditMigration=Files.readString(Path.of("src/env/init/V25__mysql_audit_governance_foundation.sql"));
        String auditTable=auditMigration.substring(auditMigration.indexOf("CREATE TABLE IF NOT EXISTS `sys_security_audit_event`"));
        auditTable=auditTable.substring(0,auditTable.indexOf(";")).replaceAll("(?s) ENGINE=InnoDB.*","");
        jdbc.execute(auditTable);
        var audit=new WeatherAudit(new com.platform.audit.JdbcSecurityAuditEvidenceWriter(jdbc,new com.platform.audit.AuditSummarySanitizer()),new com.platform.audit.AuditGovernanceProperties());
        repo=new WeatherRepository(jdbc,new WeatherCodec(new ObjectMapper().registerModule(new JavaTimeModule())),audit);
    }
    Job enqueue(String key) {
        return repo.enqueue(WeatherService.newJob("WEATHER","building","binding",Source.OPEN_METEO,Product.HISTORY_HOURLY,day,day,null,null,1),key,List.of(key));
    }
    @Test void bindingVersionAndIdempotencyAreAtomic() {
        var req=new BindingRequest("Nanjing",32d,118d,"101190101","CITY",day.atStartOfDay(ZONE).toInstant(),0,true);
        var first=repo.saveBinding("building",1,"a",req);
        assertThat(jdbc.queryForObject("SELECT result FROM sys_security_audit_event WHERE object_id=?",String.class,first.id())).isEqualTo("SUCCESS");
        assertThat(repo.saveBinding("building",1,"a",req).id()).isEqualTo(first.id());
        assertThatThrownBy(()->repo.saveBinding("building",1,"b",req)).hasMessageContaining("变化");
        var next=repo.saveBinding("building",1,"c",new BindingRequest("next",33d,119d,null,"DISTRICT",day.plusDays(1).atStartOfDay(ZONE).toInstant(),1,true));
        assertThat(repo.effective("building",day.atStartOfDay(ZONE).toInstant()).id()).isEqualTo(first.id());
        assertThat(repo.effective("building",day.plusDays(1).atStartOfDay(ZONE).toInstant()).id()).isEqualTo(next.id());
    }
    @Test void sourceLaneSerializesJobsAndExpiredWorkerCannotPublish() {
        enqueue("one");enqueue("two");long now=System.currentTimeMillis();
        Job first=repo.claim("OPEN_METEO",now);assertThat(first).isNotNull();
        assertThat(repo.claim("OPEN_METEO",now+10)).isNull();
        jdbc.update("UPDATE biz_weather_lane SET lease_until=0,next_allowed=0");jdbc.update("UPDATE biz_weather_job SET lease_until=0 WHERE id=?",first.id());
        Job second=repo.claim("OPEN_METEO",now+20);assertThat(second).isNotNull();
        assertThatThrownBy(()->repo.finish(first,"SUCCEEDED",null)).hasMessage("LEASE_LOST");
    }
    @Test void fixedPayloadReplaysAndPartialHistoryDoesNotReplaceCompleteHead() {
        enqueue("one");Job job=repo.claim("OPEN_METEO",System.currentTimeMillis());
        FetchResult original=new FetchResult(Source.OPEN_METEO,Product.HISTORY_HOURLY,Instant.now(),null,32d,118d,List.of(new Sample(day.atStartOfDay(ZONE).toInstant(),Map.of("temperature_2m",20d,"relative_humidity_2m",60d),Map.of())),"v1");
        Dataset data=repo.prepare(job,original);
        assertThat(repo.head("binding",Source.OPEN_METEO,Product.HISTORY_HOURLY,day)).isNull();
        assertThat(repo.prepare(job,new FetchResult(Source.OPEN_METEO,Product.HISTORY_HOURLY,Instant.now(),null,null,null,List.of(),"v2")).id()).isEqualTo(data.id());
        repo.publish(job,data);
        assertThat(repo.head("binding",Source.OPEN_METEO,Product.HISTORY_HOURLY,day).id()).isEqualTo(data.id());
        enqueue("two");jdbc.update("UPDATE biz_weather_lane SET next_allowed=0");
        Job next=repo.claim("OPEN_METEO",System.currentTimeMillis());
        Dataset partial=repo.prepare(next,new FetchResult(Source.OPEN_METEO,Product.HISTORY_HOURLY,Instant.now(),null,32d,118d,List.of(new Sample(day.atStartOfDay(ZONE).toInstant(),Map.of("temperature_2m",21d),Map.of())),"v1"));
        repo.publish(next,partial);
        assertThat(repo.head("binding",Source.OPEN_METEO,Product.HISTORY_HOURLY,day).id()).isEqualTo(data.id());
        assertThat(repo.job(next.id()).state()).isEqualTo("SUCCEEDED");
    }
    private com.platform.weather.energy.DailyElectricityAdapter.DailyResult dayResult(LocalDate date,long start,long end) {
        return new com.platform.weather.energy.DailyElectricityAdapter.DailyResult(date,java.math.BigDecimal.ONE,"COMPLETE",false,start,end,java.math.BigDecimal.ZERO,java.math.BigDecimal.ONE,"relation","quality","INBOUND","kWh",0L,0L,0L,"hash",List.of(),"policy",List.of());
    }
    @Test void differentSharedBoundaryOutsideBatchRejectsTheEntireWrite() {
        long boundary=day.atStartOfDay(ZONE).toInstant().toEpochMilli();
        var previous=dayResult(day.minusDays(1),boundary-86400000,boundary);
        var codec=new WeatherCodec(new ObjectMapper().registerModule(new JavaTimeModule()));
        jdbc.update("INSERT INTO biz_energy_daily_comparison_result VALUES(?,?,?,?,?,?)","building","system","point",day.minusDays(1),codec.json(previous),0);
        Job job=repo.enqueue(WeatherService.newJob("ENERGY","building",null,null,null,day,day,"system","point",1),"energy-key",List.of("energy"));
        job=repo.claim("ENERGY",System.currentTimeMillis());
        Job claimed=job;
        assertThatThrownBy(()->repo.saveEnergy(claimed,Map.of(day,dayResult(day,boundary-180000,boundary+86400000))))
                .hasMessageContaining("扩大补算范围");
        assertThat(repo.energy("building","system","point",day,day)).isEmpty();
    }
    @Test void commandKeyCannotBeReusedForDifferentRanges() {
        var first=repo.command(1,"request",List.of("first"),()->List.of(JobView.of(enqueue("child"))));
        assertThat(repo.command(1,"request",List.of("first"),()->{throw new AssertionError("must reuse");})).isEqualTo(first);
        assertThatThrownBy(()->repo.command(1,"request",List.of("different"),List::of)).hasMessageContaining("不同内容");
    }
    @Test void sourceRetryAfterBlocksOtherQueuedRequests() {
        enqueue("one");enqueue("two");Job job=repo.claim("OPEN_METEO",System.currentTimeMillis());
        repo.finish(job,"RETRY_WAIT","SOURCE_HTTP_ERROR",300000);
        assertThat(repo.claim("OPEN_METEO",System.currentTimeMillis()+120000)).isNull();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"SUCCEEDED,SUCCESS","FAILED,FAILED","RETRY_WAIT,FAILED","BLOCKED,REJECTED","CANCELLED,REJECTED"})
    void jobStateFitsPublicAuditConstraint(String state,String expected) {
        enqueue("audit");
        Job job=repo.claim("OPEN_METEO",System.currentTimeMillis());
        repo.finish(job,state,"TEST_REASON");
        assertThat(repo.job(job.id()).state()).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT result FROM sys_security_audit_event WHERE object_id=?",String.class,job.id())).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT after_summary FROM sys_security_audit_event WHERE object_id=?",String.class,job.id())).isEqualTo("state="+state);
    }
}
