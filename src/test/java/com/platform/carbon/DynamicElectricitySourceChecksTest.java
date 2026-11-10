package com.platform.carbon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DynamicElectricitySourceChecksTest {
    JdbcTemplate jdbc;
    DisplaySourceRevisionRepository versions;
    DynamicElectricitySourceChecks checks;
    final long from=Instant.parse("2026-09-10T00:00:00Z").toEpochMilli(),to=from+3_600_000;
    final DynamicElectricityLiveRepository.Task task=new DynamicElectricityLiveRepository.Task("B",from,to,1,"input",1,"READY",null,0,"meta","result","REVIEWED_INTERVAL","HOUR",Map.of());
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:checks-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","") );
        jdbc.execute("CREATE TABLE biz_carbon_display_source_revision(building_id VARCHAR(32),domain_key VARCHAR(32),scope_key VARCHAR(16),revision BIGINT,PRIMARY KEY(building_id,domain_key,scope_key))");
        jdbc.execute("CREATE TABLE biz_dynamic_input_check(building_id VARCHAR(32),start_ms BIGINT,end_ms BIGINT,task_revision BIGINT,metadata_version VARCHAR(64),source_version VARCHAR(64),PRIMARY KEY(building_id,start_ms,end_ms))");
        jdbc.execute("CREATE TABLE biz_dynamic_electricity_task(building_id VARCHAR(32),start_ms BIGINT,end_ms BIGINT,revision BIGINT,metadata_version VARCHAR(64),state VARCHAR(16),next_check BIGINT,PRIMARY KEY(building_id,start_ms,end_ms))");
        jdbc.update("INSERT INTO biz_dynamic_electricity_task VALUES ('B',?,?,1,'meta','READY',0)",from,to);
        versions=new DisplaySourceRevisionRepository(jdbc);checks=new DynamicElectricitySourceChecks(jdbc,versions);
    }
    @Test void successfulCheckSurvivesRestartButSourceRevisionRequiresRecheck() {
        String version=checks.capture(task);assertThat(checks.unchanged(task,version)).isFalse();
        checks.completed(task,version);
        var restarted=new DynamicElectricitySourceChecks(jdbc,versions);
        assertThat(restarted.unchanged(task,restarted.capture(task))).isTrue();
        jdbc.update("UPDATE biz_carbon_display_source_revision SET revision=revision+1 WHERE building_id='B' AND domain_key='DYNAMIC_INPUT' AND scope_key='U:2026-09'");
        assertThat(restarted.unchanged(task,restarted.capture(task))).isFalse();
        checks.completed(task,version);
        assertThat(restarted.unchanged(task,restarted.capture(task))).isFalse();
    }
    @Test void failedOrRevisedTaskCannotBeStampedAsSuccessfullyChecked() {
        String version=checks.capture(task);
        jdbc.update("UPDATE biz_dynamic_electricity_task SET state='WAITING'");checks.completed(task,version);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_dynamic_input_check",Integer.class)).isZero();
        jdbc.update("UPDATE biz_dynamic_electricity_task SET state='READY',revision=2");checks.completed(task,version);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_dynamic_input_check",Integer.class)).isZero();
    }
    @Test void rawWatermarkInvalidatesRawChecksWithoutInvalidatingReviewedChecks() {
        String reviewed=checks.capture(task);
        var raw=new DynamicElectricityLiveRepository.Task("B",from,to,1,"input",1,"READY",null,0,"meta","result","RAW_CUMULATIVE","HOUR",Map.of());
        String before=checks.capture(raw);checks.completed(raw,before);
        assertThat(checks.unchanged(raw,checks.capture(raw))).isTrue();
        jdbc.update("UPDATE biz_carbon_display_source_revision SET revision=revision+1 WHERE building_id='B' AND domain_key='DYNAMIC_RAW' AND scope_key='U:2026-09'");
        assertThat(checks.unchanged(raw,checks.capture(raw))).isFalse();
        assertThat(checks.capture(task)).isEqualTo(reviewed);
    }
}
