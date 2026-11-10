package com.platform.adapter.model;

import java.util.List;

/**
 * 云端适配器发布给本地平台的标准多指标报文。
 *
 * <p>报文只携带协议来源、设备外部身份、时间和标准指标，不包含建筑或平台设备归属；
 * 这些可信业务关系由本地平台解析。</p>
 */
public record StandardTelemetryMessage(
        String standardVersion,
        String profileCode,
        int profileVersion,
        DeviceIdentity deviceIdentity,
        String canonicalMessageId,
        String sourceMessageId,
        String bootId,
        Long sourceSeq,
        Long collectedAt,
        long adapterReceivedAt,
        Long retransmittedAt,
        String batchId,
        Integer batchItemIndex,
        Integer batchItemCount,
        IdSource idSource,
        TimeSource timeSource,
        DedupMode dedupMode,
        String declaredAckMode,
        String correlationPolicy,
        List<StandardMetric> metrics,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        ElectricityInterval electricityInterval) {

    public StandardTelemetryMessage(String standardVersion,String profileCode,int profileVersion,DeviceIdentity deviceIdentity,
            String canonicalMessageId,String sourceMessageId,String bootId,Long sourceSeq,Long collectedAt,long adapterReceivedAt,
            Long retransmittedAt,String batchId,Integer batchItemIndex,Integer batchItemCount,IdSource idSource,TimeSource timeSource,
            DedupMode dedupMode,String declaredAckMode,String correlationPolicy,List<StandardMetric> metrics) {
        this(standardVersion,profileCode,profileVersion,deviceIdentity,canonicalMessageId,sourceMessageId,bootId,sourceSeq,collectedAt,
                adapterReceivedAt,retransmittedAt,batchId,batchItemIndex,batchItemCount,idSource,timeSource,dedupMode,declaredAckMode,correlationPolicy,metrics,null);
    }

    public StandardTelemetryMessage {
        metrics = List.copyOf(metrics);
    }
}
