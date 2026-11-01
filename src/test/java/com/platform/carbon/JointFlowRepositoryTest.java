package com.platform.carbon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** H2执行实际选择SQL，独立验证候选/修订排除与精确期间，不连接运行实例。 */
class JointFlowRepositoryTest {
    private JdbcTemplate jdbc;
    private CarbonCalculationRepository repository;
    private final Instant from=Instant.parse("2023-12-31T16:00:00Z"),to=Instant.parse("2024-01-31T16:00:00Z");
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:joint_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("""
            CREATE TABLE biz_carbon_calculation_batch(calculation_batch_id VARCHAR(32),building_id VARCHAR(32),period_type VARCHAR(16),
            period_start TIMESTAMP,period_end TIMESTAMP,timezone_id VARCHAR(64),result_nature VARCHAR(32),publication_status VARCHAR(32),status VARCHAR(32),
            idempotency_key VARCHAR(80),request_hash VARCHAR(64),active_lock_key VARCHAR(64),rounding_policy_version_id VARCHAR(32),
            supersedes_calculation_batch_id VARCHAR(32),started_at TIMESTAMP,deadline_at TIMESTAMP,completed_at TIMESTAMP,duration_ms BIGINT,
            snapshot_count INT,detail_count INT,slow_calculation BOOLEAN,safe_error_code VARCHAR(64),safe_error_message VARCHAR(200),created_by BIGINT,created_at TIMESTAMP)
            """);
        jdbc.execute("CREATE TABLE biz_carbon_result_relation(old_calculation_batch_id VARCHAR(32),new_calculation_batch_id VARCHAR(32),relation_status VARCHAR(32))");
        repository=new CarbonCalculationRepository(jdbc);
    }
    private void batch(String id,String building,String nature,String publication,String zone) {
        jdbc.update("""
            INSERT INTO biz_carbon_calculation_batch(calculation_batch_id,building_id,period_type,period_start,period_end,timezone_id,
            result_nature,publication_status,status,snapshot_count,detail_count,slow_calculation,created_by)
            VALUES (?,?,'MONTH',?,?,?,?,?,'COMPLETED_COMPLETE',1,1,false,1)
            """,id,building,Timestamp.from(from),Timestamp.from(to),zone,nature,publication);
    }
    @Test void onlyReturnsCurrentFormalPublishedResultInExactBuildingWindowAndTimezone() {
        batch("old","B","FORMAL","DIRECT","Asia/Shanghai");batch("new","B","FORMAL","PUBLISHED","Asia/Shanghai");
        batch("candidate","B","FORMAL","PUBLISHED","Asia/Shanghai");batch("other","OTHER","FORMAL","DIRECT","Asia/Shanghai");
        batch("development","B","DEVELOPMENT_SIMULATION","DIRECT","Asia/Shanghai");batch("timezone","B","FORMAL","DIRECT","UTC");
        jdbc.update("INSERT INTO biz_carbon_result_relation VALUES ('old','new','SUPERSEDED')");
        jdbc.update("INSERT INTO biz_carbon_result_relation VALUES ('new','candidate','CANDIDATE')");
        assertThat(repository.findJointFlowMonth("B",from,to)).extracting(v->v.batchId()).containsExactly("new");
        assertThat(repository.findJointFlowMonth("B",from.plusSeconds(1),to)).isEmpty();
    }
    @Test void returnsAtMostTwoForConflictDetectionWithoutRecentBatchScan() {
        for(int i=0;i<105;i++)batch("B"+i,"B","FORMAL","DIRECT","Asia/Shanghai");
        assertThat(repository.findJointFlowMonth("B",from,to)).hasSize(2);
    }
}
