package com.platform.iot.qualityusage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.formula.model.IndicatorMinuteState;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
/**
 * 持久化并幂等对账 TDengine 当前状态投影失败和超窗人工恢复标记。
 *
 * <p>MySQL 任务只保存有限状态或脱敏范围证据，不复制 Q0/Q1/Q2 数值。失败任务
 * 可以重复领取；TDengine 投影以指标分钟覆盖，因此重放不会产生重复业务状态。</p>
 */
public class QualityUsageRecoveryTaskService {
    private static final Logger log = LoggerFactory.getLogger(QualityUsageRecoveryTaskService.class);
    private static final ZoneId MYSQL_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IndicatorMinuteRepository indicatorRepository;
    private final QualityUsageProperties properties;

    public QualityUsageRecoveryTaskService(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IndicatorMinuteRepository indicatorRepository,
            QualityUsageProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.indicatorRepository = indicatorRepository;
        this.properties = properties;
    }

    public void recordProjectionFailure(
            List<IndicatorMinuteState> states, RuntimeException failure) {
        for (IndicatorMinuteState state : states) {
            try {
                enqueue(
                        "INDICATOR_STATE_RECONCILIATION",
                        state.indicatorId() + ':' + state.minuteStart(),
                        objectMapper.writeValueAsString(state),
                        "TDENGINE_STATE_WRITE_FAILED");
            } catch (Exception exception) {
                log.error("Unable to persist quality usage projection recovery task: indicatorId={}, minute={}",
                        state.indicatorId(), state.minuteStart(), exception);
            }
        }
    }

    public void recordManualRecoveryRequired(
            long revision, int omittedMinuteCount) {
        enqueue(
                "QUALITY_POLICY_RECOVERY_REQUIRED",
                "REVISION:" + revision,
                "{\"revision\":" + revision + ",\"omittedMinuteCount\":"
                        + omittedMinuteCount + '}',
                QualityUsageErrors.RECOVERY_REQUIRED);
    }

    public void recordRecoveryWindowExceeded(
            long revision, long affectedFrom, long automaticFrom) {
        enqueue(
                "QUALITY_POLICY_RECOVERY_REQUIRED",
                "WINDOW:" + revision,
                "{\"revision\":" + revision + ",\"affectedFrom\":" + affectedFrom
                        + ",\"automaticFrom\":" + automaticFrom + '}',
                QualityUsageErrors.RECOVERY_REQUIRED);
    }

    public void recordQueueOverflow(long revision, String queueName) {
        enqueue(
                "QUALITY_POLICY_RECOVERY_REQUIRED",
                "QUEUE:" + queueName + ':' + revision,
                "{\"revision\":" + revision + ",\"queue\":\"" + queueName + "\"}",
                "QUALITY_POLICY_RECOVERY_QUEUE_FULL");
    }

    @Scheduled(fixedDelayString = "${quality-usage.reconciliation-delay-ms:60000}")
    public void reconcileProjectionFailures() {
        List<RecoveryTask> tasks = jdbc.query(
                "SELECT task_id,payload_json,retry_count FROM biz_quality_usage_recovery_task "
                        + "WHERE task_type='INDICATOR_STATE_RECONCILIATION' "
                        + "AND status IN ('WAITING','FAILED') ORDER BY update_time,task_id LIMIT ?",
                (rs, row) -> new RecoveryTask(
                        rs.getString("task_id"), rs.getString("payload_json"),
                        rs.getInt("retry_count")),
                properties.getReconciliationBatchSize());
        for (RecoveryTask task : tasks) {
            reconcile(task);
        }
    }

    private void reconcile(RecoveryTask task) {
        try {
            IndicatorMinuteState state = objectMapper.readValue(
                    task.payloadJson(), IndicatorMinuteState.class);
            indicatorRepository.saveStates(List.of(state));
            jdbc.update(
                    "UPDATE biz_quality_usage_recovery_task SET status='DONE',last_error=NULL,"
                            + "update_time=? WHERE task_id=? AND status IN ('WAITING','FAILED')",
                    now(), task.taskId());
        } catch (Exception exception) {
            jdbc.update(
                    "UPDATE biz_quality_usage_recovery_task SET status='FAILED',retry_count=?,"
                            + "last_error='TDENGINE_STATE_RETRY_FAILED',update_time=? WHERE task_id=?",
                    task.retryCount() + 1, now(), task.taskId());
        }
    }

    private void enqueue(String type, String businessKey, String payload, String error) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_quality_usage_recovery_task WHERE business_key=?",
                Integer.class, businessKey);
        if (count != null && count > 0) {
            jdbc.update(
                    "UPDATE biz_quality_usage_recovery_task SET payload_json=?,status='WAITING',"
                            + "last_error=?,update_time=? WHERE business_key=?",
                    payload, error, now(), businessKey);
            return;
        }
        jdbc.update(
                "INSERT INTO biz_quality_usage_recovery_task "
                        + "(task_id,task_type,business_key,payload_json,status,retry_count,last_error,"
                        + "create_time,update_time) VALUES (?,?,?,?, 'WAITING',0,?,?,?)",
                id(), type, businessKey, payload, error, now(), now());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(MYSQL_ZONE);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record RecoveryTask(String taskId, String payloadJson, int retryCount) {
    }
}
