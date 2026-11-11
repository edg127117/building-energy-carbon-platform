package com.platform.carbon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 仅H2任务/租约语义；MySQL迁移与实际结果等价另在本机隔离副本验证。 */
class CarbonDisplayQueryRepositoryTest {
    JdbcTemplate jdbc;CarbonDisplayQueryRepository repo;
    final CarbonDisplayQueryContracts.Query q=CarbonDisplayQueryContracts.intensity("B","MONTHLY_YTD",2026,10,"Asia/Shanghai");
    @BeforeEach void setup()throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:display-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("src/env/init/V103__mysql_carbon_display_read_model.sql")).replaceAll("(?s)ENGINE=InnoDB.*?;",";"));
        repo=new CarbonDisplayQueryRepository(jdbc,new CarbonDisplayQueryProperties());
    }
    @Test void restartLeaseRecoveryAndSourceRevisionRejectOldPublisher(){
        repo.request(q,"{}",1,"v1",1000);var h=repo.head(q.key());String first=repo.claim(h,1000);
        assertThat(repo.claim(h,1001)).isNull();String recovery=repo.claim(h,200000);assertThat(recovery).isNotNull();
        assertThat(repo.publish(h,first,"{}","h",200001)).isFalse();assertThat(repo.publish(h,recovery,"{}","h",200001)).isTrue();
        repo.request(q,"{}",2,"v2",200002);assertThat(repo.head(q.key()).state()).isEqualTo("PENDING");
        assertThat(repo.publish(h,recovery,"old","h",200003)).isFalse();
        assertThat(new CarbonDisplayQueryRepository(jdbc,new CarbonDisplayQueryProperties()).due(200004)).hasSize(1);
    }
    @Test void queriesDoNotResetRunningLeaseOrFailedRetryBackoff(){
        repo.request(q,"{}",1,"v",1000);var h=repo.head(q.key());String token=repo.claim(h,1000);
        repo.request(q,"{}",2,"v",1001);assertThat(repo.head(q.key()).token()).isEqualTo(token);
        repo.failed(h,token,"SOURCE_CONFLICT",409,1002);repo.request(q,"{}",3,"v",1003);
        assertThat(repo.head(q.key()).state()).isEqualTo("FAILED");assertThat(repo.due(1003)).isEmpty();
        assertThat(repo.head(q.key()).errorStatus()).isEqualTo(409);
    }
    @Test void revokedBackgroundActorDoesNotPoisonAnotherAuthorizedReadersTask(){
        repo.request(q,"{}",1,"v",1000);var h=repo.head(q.key());String token=repo.claim(h,1000);
        repo.failed(h,token,"REVOKED",403,1001);repo.request(q,"{}",2,"v",1002);
        assertThat(repo.head(q.key()).state()).isEqualTo("PENDING");assertThat(repo.head(q.key()).user()).isEqualTo(2);
    }
    @Test void readyInternalLeavesStayIdleUntilParentRequestsChangedSourceButUnfinishedLeavesRecover(){
        for(String kind:List.of("DAILY_TREND_DAY","ELECTRICITY_YEAR_MONTH","DAILY_TREND_DETAIL","ELECTRICITY_YEAR")) {
            repo.request(kind,"B",kind,"{}",1,"v1",1000);
            var head=repo.head(kind);String token=repo.claim(head,1000);
            assertThat(repo.publish(head,token,"{}","hash",1001)).isTrue();
        }
        assertThat(repo.duePeriods(40000)).extracting(CarbonDisplayQueryRepository.Head::kind)
                .containsExactlyInAnyOrder("DAILY_TREND_DETAIL","ELECTRICITY_YEAR");
        repo.request("DAILY_TREND_DAY","B","DAILY_TREND_DAY","{}",1,"v2",40001);
        var changed=repo.head("DAILY_TREND_DAY");repo.claim(changed,40001);
        assertThat(repo.duePeriods(40002)).extracting(CarbonDisplayQueryRepository.Head::kind).doesNotContain("DAILY_TREND_DAY");
        assertThat(repo.duePeriods(200002)).extracting(CarbonDisplayQueryRepository.Head::kind).contains("DAILY_TREND_DAY");
    }
    @Test void periodCursorVisitsEveryPageThenRescansAfterExhaustionOrRepositoryRestart(){
        for(int i=0;i<205;i++) {
            String key=String.format(Locale.ROOT,"%064d",i);
            repo.request(key,"B","ELECTRICITY_MONTH","{}",1,"v1",1000);
        }

        var first=repo.duePeriods(1000);assertThat(first).hasSize(100);
        assertThat(first.getFirst().key()).isEqualTo(String.format(Locale.ROOT,"%064d",0));
        assertThat(first.getLast().key()).isEqualTo(String.format(Locale.ROOT,"%064d",99));
        first.forEach(repo::advance);

        var second=repo.duePeriods(1000);assertThat(second).hasSize(100);
        assertThat(second.getFirst().key()).isEqualTo(String.format(Locale.ROOT,"%064d",100));
        second.forEach(repo::advance);
        var last=repo.duePeriods(1000);assertThat(last).hasSize(5);
        assertThat(last.getFirst().key()).isEqualTo(String.format(Locale.ROOT,"%064d",200));
        last.forEach(repo::advance);
        assertThat(repo.duePeriods(1000)).isEmpty();

        var restarted=new CarbonDisplayQueryRepository(jdbc,new CarbonDisplayQueryProperties());
        assertThat(restarted.duePeriods(1000)).hasSize(100)
                .first().extracting(CarbonDisplayQueryRepository.Head::key).isEqualTo(String.format(Locale.ROOT,"%064d",0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_display_read_model WHERE updated_at<>1000",Integer.class)).isZero();
    }
    @Test void changedPublicPeriodReturnsToTheQueueAndCanBePublished(){
        String key="changed-period";repo.request(key,"B","ELECTRICITY_MONTH","{}",1,"v1",1000);
        var original=repo.head(key);String first=repo.claim(original,1000);
        assertThat(repo.publish(original,first,"old","old-hash",1001)).isTrue();

        var observed=repo.head(key);
        repo.requestPeriod(key,"B","ELECTRICITY_MONTH","{}",1,"v2",40000,observed);
        assertThat(repo.head(key).state()).isEqualTo("PENDING");
        var due=repo.duePeriods(40000);
        assertThat(due).extracting(CarbonDisplayQueryRepository.Head::key).contains(key);
        var changed=due.stream().filter(h->h.key().equals(key)).findFirst().orElseThrow();
        String second=repo.claim(changed,40000);assertThat(second).isNotNull();
        assertThat(repo.publish(changed,second,"new","new-hash",40001)).isTrue();
        assertThat(repo.head(key).built()).isEqualTo("v2");
    }
}
