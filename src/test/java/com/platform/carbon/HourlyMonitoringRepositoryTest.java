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
    List<HourlyMonitoringRepository.Publication> batch(int count) {
        var snapshot=repo.snapshot("B",day,Instant.now(),"policy");
        var values=new ArrayList<HourlyMonitoringRepository.Publication>();
        for(int h=0;h<count;h++) {
            long at=hour+h*3_600_000L;
            var calculated=new HourlyMonitoringCalculator.Calculated(HourlyMonitoringContracts.empty(h,Instant.ofEpochMilli(at),"MISSING","TEST"),CarbonEvidence.object().put("source","SIMULATED"));
            values.add(new HourlyMonitoringRepository.Publication(at,snapshot.versions().get(at),calculated));
        }
        return values;
    }
    @Test void groupedPublicationKeepsEveryHourEvidenceAndRejectsAnyChangedMember() {
        var values=batch(4);repo.claim(target,"lease",System.currentTimeMillis());
        jdbc.update("INSERT INTO biz_hourly_monitor_revision VALUES('B',?,1)",hour+3_600_000);
        assertThat(repo.publishBatch(target,"lease",values,"policy")).isFalse();
        assertThat(repo.snapshot("B",day,Instant.now(),"policy").results()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_hourly_monitor_evidence",Integer.class)).isZero();
        assertThat(repo.publishBatch(target,"lease",batch(4),"policy")).isTrue();
        var rows=repo.snapshot("B",day,Instant.now(),"policy").results();assertThat(rows).hasSize(4);
        rows.values().forEach(row->{
            assertThat(repo.decode(row).evidenceId()).isEqualTo(row.evidence());
            assertThat(repo.evidence("B",row.evidence()).path("source").asText()).isEqualTo("SIMULATED");
        });
    }
    @Test void groupedPublicationRollsBackAllEvidenceAndSummariesOnLaterWriteFailure() {
        var values=batch(2);repo.claim(target,"lease",System.currentTimeMillis());
        jdbc.execute("ALTER TABLE biz_hourly_monitor_result ADD CONSTRAINT reject_second CHECK (hour_ms<>"+(hour+3_600_000)+")");
        assertThatThrownBy(()->repo.publishBatch(target,"lease",values,"policy")).isInstanceOf(RuntimeException.class);
        assertThat(repo.snapshot("B",day,Instant.now(),"policy").results()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_hourly_monitor_evidence",Integer.class)).isZero();
    }
    @Test void groupedPublicationPreservesLeaseOwnershipAndRejectsInvalidBatchBounds() {
        var values=batch(2);repo.claim(target,"lease",System.currentTimeMillis());
        jdbc.update("UPDATE biz_hourly_monitor_target SET actor_id=8");
        assertThat(repo.publishBatch(target,"lease",values,"policy")).isFalse();
        assertThatThrownBy(()->repo.publishBatch(target,"lease",List.of(),"policy")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repo.publishBatch(target,"lease",batch(5),"policy")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repo.publishBatch(target,"lease",List.of(values.getFirst(),values.getFirst()),"policy")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repo.publishBatch(target,"lease",List.of(new HourlyMonitoringRepository.Publication(hour+86_400_000,"v",result())),"policy")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_hourly_monitor_evidence",Integer.class)).isZero();
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
    @Test void firstHistoricalQueryBypassesIdlePagesButHonorsDateResultsAndLease() {
        long now=Instant.parse("2026-01-03T04:00:00Z").toEpochMilli();
        repo.register("B",LocalDate.of(2026,1,3),7);repo.register("B",LocalDate.of(2026,1,4),7);
        assertThat(repo.unpreparedHistory(now)).isEqualTo(target);
        assertThat(repo.claim(target,"lease",now)).isTrue();
        assertThat(repo.unpreparedHistory(now)).isNull();
        repo.release(target,"lease",0);
        jdbc.update("UPDATE biz_hourly_monitor_target SET next_check=0");
        // Any stored hour makes this an existing target; it continues through the ordinary fair cursor.
        jdbc.update("INSERT INTO biz_hourly_monitor_result(building_id,hour_ms,source_version,evidence_id,summary_json,summary_hash,updated_at) VALUES('B',?,'v','e','{}','h',0)",hour+23*3_600_000);
        assertThat(repo.unpreparedHistory(now)).isNull();
        assertThat(repo.due(now,false,null)).containsExactly(target);
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
