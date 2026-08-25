package com.platform.iot.deviceparameter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.RecalculationJob;
import com.platform.iot.deviceparameter.DeviceParameterModels.RecalculationStatus;
import com.platform.iot.deviceparameter.formula.DeviceParameterFormulaImpactPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "device-parameter.recalculation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
/**
 * 参数追溯重算的 MySQL 游标、幂等领取、失败保留和 TDengine 公式重放服务。
 *
 * <p>该分支只读取已经冻结的分钟输入并重算受影响指标，不重新聚合 Q0/Q1/Q2，也不调用
 * 质量作废删除入口。每个成功值先追加不可变结果修订，再更新当前状态投影。</p>
 */
public class DeviceParameterRecalculationService {
    private static final Logger log = LoggerFactory.getLogger(DeviceParameterRecalculationService.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final ZoneId PROJECT_ZONE = DeviceParameterSupport.PROJECT_ZONE;

    private final DeviceParameterJdbcRepository repository;
    private final DeviceParameterFormulaImpactPort formulaPort;
    private final DeviceParameterRecalculationProperties properties;
    private final ObjectMapper objectMapper;

    /** MySQL 发布提交后再写 TDengine 待重算投影，避免跨数据源伪事务。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTimelinePublished(DeviceParameterTimelinePublishedEvent event) {
        try {
            formulaPort.markPending(event.timelineRevisionId(), event.equipmentId(),
                    event.indicatorIds(), event.from(), event.to());
        } catch (RuntimeException exception) {
            repository.updateTimelineRecalculationStatus(
                    event.timelineRevisionId(), RecalculationStatus.RECALC_FAILED);
            log.warn("设备参数追溯待重算状态写入失败: timelineRevisionId={}",
                    event.timelineRevisionId(), exception);
        }
    }

    @Scheduled(fixedDelayString = "${device-parameter.recalculation.delay-ms:60000}")
    public void processWaitingJobs() {
        for (RecalculationJob job : repository.listClaimableRecalculationJobs(
                properties.getBatchSize())) {
            if (("FAILED".equals(job.status())
                    && job.retryCount() >= properties.getMaxRetries())
                    || repository.claimRecalculationJob(job.jobId()) != 1) {
                continue;
            }
            processClaimed(repository.findRecalculationJob(job.jobId()).orElse(job));
        }
    }

    void processClaimed(RecalculationJob job) {
        List<String> indicatorIds;
        try {
            indicatorIds = objectMapper.readValue(job.indicatorIdsJson(), STRING_LIST);
            if (indicatorIds == null || indicatorIds.isEmpty()) {
                throw new IllegalStateException("受影响指标集合为空");
            }
            repository.updateTimelineRecalculationStatus(
                    job.timelineRevisionId(), RecalculationStatus.RECALCULATING);
            formulaPort.markPending(job.timelineRevisionId(), job.equipmentId(), indicatorIds,
                    job.cursorMinute(), job.toMinute());
            LocalDateTime chunkEnd = job.cursorMinute().plusMinutes(properties.getChunkMinutes());
            if (chunkEnd.isAfter(job.toMinute())) {
                chunkEnd = job.toMinute();
            }
            for (LocalDateTime minute = job.cursorMinute(); minute.isBefore(chunkEnd);
                 minute = minute.plusMinutes(1)) {
                formulaPort.recalculateMinute(job.buildingId(),
                        minute.atZone(PROJECT_ZONE).toInstant().toEpochMilli(), indicatorIds);
            }
            boolean complete = !chunkEnd.isBefore(job.toMinute());
            if (repository.advanceRecalculationJob(
                    job.jobId(), job.cursorMinute(), chunkEnd, complete) != 1) {
                throw new IllegalStateException("重算游标并发推进失败");
            }
            repository.updateTimelineRecalculationStatus(job.timelineRevisionId(),
                    complete ? RecalculationStatus.SUCCEEDED
                            : RecalculationStatus.RECALCULATING);
        } catch (Exception exception) {
            repository.failRecalculationJob(job.jobId(), "PARAMETER_RECALCULATION_CHUNK_FAILED");
            repository.updateTimelineRecalculationStatus(
                    job.timelineRevisionId(), RecalculationStatus.RECALC_FAILED);
            try {
                List<String> ids = objectMapper.readValue(job.indicatorIdsJson(), STRING_LIST);
                formulaPort.markFailed(job.timelineRevisionId(), job.equipmentId(), ids,
                        job.fromMinute(), job.toMinute());
            } catch (Exception markException) {
                log.warn("设备参数重算失败状态投影写入失败: jobId={}", job.jobId(), markException);
            }
            log.warn("设备参数追溯重算分块失败: jobId={}", job.jobId(), exception);
        }
    }
}
