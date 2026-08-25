package com.platform.iot.reliability;

import com.platform.iot.reliability.mapper.TelemetryReceiptFailureMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptMapper;
import com.platform.iot.reliability.mapper.MqttFailureAggregateMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
/** 以有界批次清理 V2 成功回执和异常明细，保留周期由部署配置决定。 */
public class TelemetryReceiptRetentionJob {

    private final TelemetryReceiptMapper receiptMapper;
    private final TelemetryReceiptFailureMapper failureMapper;
    private final MqttFailureAggregateMapper mqttFailureMapper;
    private final int receiptDays;
    private final int failureDays;
    private final int batchSize;
    private final int maxBatches;

    public TelemetryReceiptRetentionJob(
            TelemetryReceiptMapper receiptMapper,
            TelemetryReceiptFailureMapper failureMapper,
            MqttFailureAggregateMapper mqttFailureMapper,
            @Value("${telemetry-reliability.retention.receipt-days:7}") int receiptDays,
            @Value("${telemetry-reliability.retention.failure-days:180}") int failureDays,
            @Value("${telemetry-reliability.retention.batch-size:500}") int batchSize,
            @Value("${telemetry-reliability.retention.max-batches:20}") int maxBatches) {
        this.receiptMapper = receiptMapper;
        this.failureMapper = failureMapper;
        this.mqttFailureMapper = mqttFailureMapper;
        this.receiptDays = positive(receiptDays, "receipt-days");
        this.failureDays = positive(failureDays, "failure-days");
        this.batchSize = positive(batchSize, "batch-size");
        this.maxBatches = positive(maxBatches, "max-batches");
    }

    @Scheduled(cron = "${telemetry-reliability.retention.cleanup-cron:0 15 4 * * ?}")
    public void cleanup() {
        deleteInBatches(true, LocalDateTime.now().minusDays(receiptDays));
        deleteInBatches(false, LocalDateTime.now().minusDays(failureDays));
        deleteMqttFailures(LocalDateTime.now().minusDays(failureDays));
    }

    private void deleteMqttFailures(LocalDateTime before) {
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = mqttFailureMapper.deleteExpired(before, batchSize);
            if (deleted < batchSize) {
                return;
            }
        }
    }

    private void deleteInBatches(boolean receipt, LocalDateTime before) {
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = receipt
                    ? receiptMapper.deleteExpired(before, batchSize)
                    : failureMapper.deleteExpired(before, batchSize);
            if (deleted < batchSize) {
                return;
            }
        }
    }

    private int positive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
        return value;
    }
}
