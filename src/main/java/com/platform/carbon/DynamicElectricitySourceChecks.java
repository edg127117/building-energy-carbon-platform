package com.platform.carbon;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Objects;

/** 已完成来源核对的水位持久化；无来源修订时不再次读取历史人工事实和因子正文。 */
@Component
class DynamicElectricitySourceChecks {
    private final JdbcTemplate jdbc;
    private final DisplaySourceRevisionRepository revisions;
    DynamicElectricitySourceChecks(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,DisplaySourceRevisionRepository revisions) {
        this.jdbc=jdbc;this.revisions=revisions;
    }
    String capture(DynamicElectricityLiveRepository.Task task) {
        return revisions.version(task.building(),domain(task),Instant.ofEpochMilli(task.from()),Instant.ofEpochMilli(task.to()));
    }
    boolean unchanged(DynamicElectricityLiveRepository.Task task,String version) {
        if(!"READY".equals(task.state())||"DEVICE_INTERVAL".equals(task.sourceKind()))return false;
        var checks=jdbc.queryForList("SELECT source_version FROM biz_dynamic_input_check WHERE building_id=? AND start_ms=? AND end_ms=? AND task_revision=? AND metadata_version=?",String.class,
                task.building(),task.from(),task.to(),task.revision(),task.metadata());
        if(checks.size()!=1||!Objects.equals(version,checks.getFirst()))return false;
        return true;
    }
    void completed(DynamicElectricityLiveRepository.Task task,String version) {
        if(version==null||"DEVICE_INTERVAL".equals(task.sourceKind()))return;
        revisions.ifCurrent(task.building(),domain(task),Instant.ofEpochMilli(task.from()),Instant.ofEpochMilli(task.to()),version,()->
            jdbc.update("""
                    INSERT INTO biz_dynamic_input_check(building_id,start_ms,end_ms,task_revision,metadata_version,source_version)
                    SELECT building_id,start_ms,end_ms,revision,metadata_version,? FROM biz_dynamic_electricity_task
                    WHERE building_id=? AND start_ms=? AND end_ms=? AND revision=? AND state='READY'
                    ON DUPLICATE KEY UPDATE task_revision=VALUES(task_revision),metadata_version=VALUES(metadata_version),source_version=VALUES(source_version)
                    """,version,task.building(),task.from(),task.to(),task.revision()));
    }
    private static String domain(DynamicElectricityLiveRepository.Task task){return "RAW_CUMULATIVE".equals(task.sourceKind())?"DYNAMIC_RAW_INPUT":"DYNAMIC_INPUT";}
}
