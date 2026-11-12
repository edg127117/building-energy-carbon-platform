package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** H2只验证摘要发布/租约/证据语义；MySQL触发器另做独立环境验收。 */
class HourlyMonitoringRepositoryTest {
    JdbcTemplate jdbc;HourlyMonitoringRepository repo;
    final LocalDate day=LocalDate.of(2026,1,2);
    final long hour=day.atStartOfDay(HourlyMonitoringCalculator.ZONE).toInstant().toEpochMilli();
    final HourlyMonitoringRepository.Target target=new HourlyMonitoringRepository.Target("B",day,7);
    @BeforeEach void setup() throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:monitor-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        String sql=Files.readString(Path.of("src/env/init/V119__mysql_hourly_monitoring.sql")).split("DELIMITER")[0].replaceAll("(?s)ENGINE=InnoDB.*?;",";");
        jdbc.execute(sql);
        jdbc.execute("CREATE TABLE biz_carbon_display_source_revision(building_id VARCHAR(32),domain_key VARCHAR(32),scope_key VARCHAR(32),revision BIGINT,PRIMARY KEY(building_id,domain_key,scope_key))");
        repo=new HourlyMonitoringRepository(jdbc,new ObjectMapper().findAndRegisterModules());
        repo.register("B",day,7);
    }
    String version(){return repo.snapshot("B",day,Instant.now(),"policy").versions().get(hour);}
    HourlyMonitoringCalculator.Calculated result(){return new HourlyMonitoringCalculator.Calculated(HourlyMonitoringContracts.empty(0,Instant.ofEpochMilli(hour),"MISSING","TEST"),CarbonEvidence.object().put("source","SIMULATED"));}
    @Test void versionGuardRejectsChangedHourButNotUnrelatedHour() {
        String v=version();assertThat(repo.claim(target,"lease",System.currentTimeMillis())).isTrue();
        jdbc.update("INSERT INTO biz_hourly_monitor_revision VALUES('B',?,1)",hour+3600000);
        assertThat(version()).isEqualTo(v);
        assertThat(repo.publish(target,"lease",hour,v,"policy",result())).isTrue();
        jdbc.update("UPDATE biz_hourly_monitor_revision SET revision=revision+1 WHERE hour_ms=?",hour);
        assertThat(repo.publish(target,"lease",hour,v,"policy",result())).isFalse();
        assertThat(repo.snapshot("B",day,Instant.now(),"policy").results().get(hour).version()).isEqualTo(v);
    }
    @Test void restartRecoversExpiredLeaseAndCannotPublishWithOldToken() {
        assertThat(repo.claim(target,"old",1)).isTrue();
        var restarted=new HourlyMonitoringRepository(jdbc,new ObjectMapper().findAndRegisterModules());
        assertThat(restarted.claim(target,"new",System.currentTimeMillis())).isTrue();
        assertThat(repo.publish(target,"old",hour,version(),"policy",result())).isFalse();
        assertThat(restarted.publish(target,"new",hour,version(),"policy",result())).isTrue();
    }
    @Test void ruleChangesInvalidateHoursButGlobalZeroRowCreationDoesNot() {
        String v=version();repo.claim(target,"l",System.currentTimeMillis());
        assertThat(repo.publish(target,"l",hour,v,"policy",result())).isTrue();assertThat(version()).isEqualTo(v);
        jdbc.update("UPDATE biz_carbon_display_source_revision SET revision=1 WHERE building_id='*' AND domain_key='HOURLY_RULE'");
        assertThat(version()).isNotEqualTo(v);assertThat(repo.publish(target,"l",hour,v,"policy",result())).isFalse();
    }
    @Test void summaryAndEvidenceTamperingAreRejectedAndEvidenceIsBuildingScoped() {
        repo.claim(target,"l",System.currentTimeMillis());repo.publish(target,"l",hour,version(),"policy",result());
        var row=repo.snapshot("B",day,Instant.now(),"policy").results().get(hour);
        assertThat(repo.decode(row).evidenceId()).isEqualTo(row.evidence());
        assertThat(repo.evidence("B",row.evidence()).path("source").asText()).isEqualTo("SIMULATED");
        assertThatThrownBy(()->repo.evidence("OTHER",row.evidence())).isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getCode()).isEqualTo(404);
        assertThatThrownBy(()->repo.decode(new HourlyMonitoringRepository.Stored(row.hour(),row.version(),row.summary()+" ",row.hash(),row.evidence(),row.updatedAt()))).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE biz_hourly_monitor_evidence SET evidence_json='{}'");
        assertThatThrownBy(()->repo.evidence("B",row.evidence())).isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getErrorCode()).isEqualTo("HOURLY_MONITOR_INTEGRITY");
    }
    @Test void hourClosureChangesVersionWithoutAnyIncomingEvent() {
        var before=repo.snapshot("B",day,Instant.ofEpochMilli(hour+3599999),"policy");
        var after=repo.snapshot("B",day,Instant.ofEpochMilli(hour+3600000),"policy");
        assertThat(after.versions().get(hour)).isNotEqualTo(before.versions().get(hour));
        assertThat(after.versions()).hasSize(24);
    }
    @Test void todayAndHistoryUseSeparateQueuesAndFutureDatesStayIdle() {
        var now=Instant.parse("2026-01-03T04:00:00Z").toEpochMilli();
        repo.register("B",LocalDate.of(2026,1,3),7);repo.register("B",LocalDate.of(2026,1,4),7);
        assertThat(repo.due(now,true)).extracting(HourlyMonitoringRepository.Target::date).containsExactly(LocalDate.of(2026,1,3));
        assertThat(repo.due(now,false)).extracting(HourlyMonitoringRepository.Target::date).containsExactly(day);
    }
    @Test void stableCursorVisitsLaterBuildingsAndDatesWithoutUpdatingIdleRows() {
        long now=Instant.parse("2026-01-20T04:00:00Z").toEpochMilli();
        for(int i=1;i<=10;i++)repo.register("B",day.plusDays(i),7);
        repo.register("C",day,7);
        var first=repo.due(now,false,null);
        assertThat(first).hasSize(8);
        var second=repo.due(now,false,first.getLast());
        assertThat(second).hasSize(4).doesNotContainAnyElementsOf(first);
        assertThat(second.getLast().building()).isEqualTo("C");
        assertThat(repo.due(now,false,second.getLast())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_hourly_monitor_target WHERE lease_token IS NOT NULL OR next_check<>0",Integer.class)).isZero();
    }
}
