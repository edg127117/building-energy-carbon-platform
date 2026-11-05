package com.platform.iot.ingest.v2;

import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.ingest.standard.StandardMetric;

import java.util.List;

/** 适配器发往平台内部 Topic 的 V2 标准多测点契约。 */
public record TelemetryV2Message(
        String standardVersion,
        String profileCode,
        int profileVersion,
        DeviceIdentityKey deviceIdentity,
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
        String idSource,
        String timeSource,
        String dedupMode,
        String declaredAckMode,
        String correlationPolicy,
        List<StandardMetric> metrics,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        com.platform.adapter.model.ElectricityInterval electricityInterval
) {
    public TelemetryV2Message(String standardVersion,String profileCode,int profileVersion,DeviceIdentityKey deviceIdentity,
            String canonicalMessageId,String sourceMessageId,String bootId,Long sourceSeq,Long collectedAt,long adapterReceivedAt,
            Long retransmittedAt,String batchId,Integer batchItemIndex,Integer batchItemCount,String idSource,String timeSource,
            String dedupMode,String declaredAckMode,String correlationPolicy,List<StandardMetric> metrics) {
        this(standardVersion,profileCode,profileVersion,deviceIdentity,canonicalMessageId,sourceMessageId,bootId,sourceSeq,collectedAt,
                adapterReceivedAt,retransmittedAt,batchId,batchItemIndex,batchItemCount,idSource,timeSource,dedupMode,declaredAckMode,correlationPolicy,metrics,null);
    }
}
