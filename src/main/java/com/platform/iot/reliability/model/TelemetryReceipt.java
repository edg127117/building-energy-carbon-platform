package com.platform.iot.reliability.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_telemetry_receipt")
/** 每个 V2 逻辑消息的一条轻量终态证据，不保存完整原始载荷。 */
public class TelemetryReceipt {
    @TableId
    private String canonicalMessageId;
    private String identityId;
    private String buildingId;
    private String equipId;
    private String profileCode;
    private String sourceMessageId;
    private Long sourceSeq;
    private LocalDateTime collectedAt;
    private LocalDateTime adapterReceivedAt;
    private LocalDateTime firstPlatformReceivedAt;
    private LocalDateTime lastPlatformReceivedAt;
    private LocalDateTime persistedAt;
    private LocalDateTime retransmittedAt;
    private String batchId;
    private String idSource;
    private String timeSource;
    private String dedupMode;
    private String payloadHash;
    private String configuredAckMode;
    private String actualAckMode;
    private String downgradeReason;
    private String receiptStatus;
    private String resultCode;
    private Integer metricCount;
    private Integer attemptCount;
    private String devicePubackState;
    private String adapterPublishPubackState;
    private String platformConsumerAckState;
    private String applicationAckPubackState;
    private LocalDateTime applicationAckPublishedAt;
}
