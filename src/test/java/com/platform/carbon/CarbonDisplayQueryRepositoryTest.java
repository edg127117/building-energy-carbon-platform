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
}
