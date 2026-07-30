package com.platform.iot.dataquality;

import com.platform.config.DataQualityProperties;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 人工数据质量重算批次的低频领取和故障恢复入口。
 *
 * <p>每轮只从 MySQL 领取少量 WAITING 或超时 RUNNING 批次，领取成功后才访问
 * TDengine 并执行一个分块。条件领取是多实例并发边界，不能用 JVM 本地锁代替。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "data-quality",
        name = {"enabled", "recalculation-enabled"},
        havingValue = "true")
public class DataQualityRecalculationScheduler {

    private static final int CLAIM_LIMIT = 10;
    private static final String FAILURE_SUMMARY = "人工重算批次执行失败";
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final RecalculationJobRepository jobRepository;
    private final FillTaskRepository fillTaskRepository;
    private final RecalculationVoidService voidService;
    private final DataQualityRecalculationService recalculationService;
    private final DataQualityProperties properties;

    public DataQualityRecalculationScheduler(
            RecalculationJobRepository jobRepository,
            FillTaskRepository fillTaskRepository,
            RecalculationVoidService voidService,
            DataQualityRecalculationService recalculationService,
            DataQualityProperties properties) {
        this.jobRepository =
                Objects.requireNonNull(jobRepository, "jobRepository 不能为空");
        this.fillTaskRepository =
                Objects.requireNonNull(fillTaskRepository, "fillTaskRepository 不能为空");
        this.voidService =
                Objects.requireNonNull(voidService, "voidService 不能为空");
        this.recalculationService =
                Objects.requireNonNull(recalculationService, "recalculationService 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    /**
     * 扫描间隔由独立开关控制；测试关闭后不会启动人工重算外部资源链路。
     */
    @Scheduled(
            fixedDelayString =
                    "${data-quality.recalculation-scan-delay-ms:10000}",
            initialDelayString =
                    "${data-quality.recalculation-scan-delay-ms:10000}")
    public void run() {
        runAt(System.currentTimeMillis());
    }

    /**
     * 使用同一个时钟快照完成本轮候选查询与条件领取，避免长扫描内超时水位漂移。
     */
    void runAt(long nowEpochMillis) {
        LocalDateTime now = toLocal(nowEpochMillis);
        LocalDateTime staleBefore = now.minus(
                properties.getRecalculationStaleMs(), ChronoUnit.MILLIS);
        List<BizDataQualityRecalcJob> candidates;
        try {
            candidates = jobRepository.findClaimable(staleBefore, CLAIM_LIMIT);
        } catch (RuntimeException exception) {
            log.error("人工重算批次扫描失败，等待下一轮", exception);
            return;
        }

        int candidateCount = Math.min(candidates.size(), CLAIM_LIMIT);
        for (int index = 0; index < candidateCount; index++) {
            BizDataQualityRecalcJob job = candidates.get(index);
            try {
                processCandidate(job, staleBefore, now, nowEpochMillis);
            } catch (RuntimeException exception) {
                // 候选行损坏或领取 SQL 异常时继续本轮其他任务；尚未成功领取时不能改状态。
                log.error("人工重算候选批次处理失败，等待后续恢复", exception);
            }
        }
    }

    private void processCandidate(
            BizDataQualityRecalcJob job,
            LocalDateTime staleBefore,
            LocalDateTime now,
            long nowEpochMillis) {
        String jobId = Objects.requireNonNull(job, "领取候选批次不能为空").getJobId();
        if (!jobRepository.claim(jobId, staleBefore, now)) {
            // 另一实例已先领取或状态已改变是正常竞争结果，当前实例不能继续执行。
            return;
        }

        try {
            if (job.getPhase() == RecalculationJobPhase.VOIDING) {
                BizDataQualityFillTask oldTask = fillTaskRepository.findById(
                                job.getSupersedesTaskId())
                        .orElseThrow(() ->
                                new IllegalStateException("待作废的旧补全任务不存在"));
                voidService.voidOldTask(job, oldTask, nowEpochMillis);
            } else if (job.getPhase() != RecalculationJobPhase.RECALCULATING) {
                throw new IllegalStateException("人工重算批次阶段无效");
            }
            recalculationService.processClaimedJob(jobId, nowEpochMillis);
        } catch (RuntimeException exception) {
            // 原因、测点 JSON 和证据均不写 INFO；内部异常只随 jobId 进入服务端错误日志。
            log.error("人工重算批次执行失败: jobId={}", jobId, exception);
            markFailedSafely(job);
        }
    }

    private void markFailedSafely(BizDataQualityRecalcJob job) {
        try {
            jobRepository.markFailed(
                    job.getJobId(), job.getCursorMinute(), FAILURE_SUMMARY);
        } catch (RuntimeException markException) {
            // 分块服务可能已经先将同一游标置为 FAILED，二次条件更新失败不能阻断后续批次。
            log.error(
                    "人工重算批次失败状态写入未生效: jobId={}",
                    job.getJobId(),
                    markException);
        }
    }

    private LocalDateTime toLocal(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }
}
