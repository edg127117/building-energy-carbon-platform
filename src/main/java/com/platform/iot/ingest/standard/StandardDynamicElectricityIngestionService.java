package com.platform.iot.ingest.standard;

import com.platform.carbon.DynamicElectricityContracts.DeviceIntervalRequest;
import com.platform.carbon.DynamicElectricityLiveService;
import com.platform.framework.exception.BusinessException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 将标准主题的设备时段载荷接到MySQL持久任务。成功仅表示已保存，数值完成由结果查询确认。 */
@Service
@RequiredArgsConstructor
public class StandardDynamicElectricityIngestionService {
    private final DynamicElectricityLiveService live;
    private final Validator validator;

    public StandardTelemetryResult ingest(StandardTelemetryMessage message,long receivedAt) {
        String envelope=StandardTelemetryIngestionService.validateIdentityEnvelope(message,receivedAt);
        if(envelope!=null)return StandardTelemetryResult.rejected(envelope);
        var interval=message.electricityInterval();
        if(interval==null||message.metrics()!=null&&!message.metrics().isEmpty())
            return StandardTelemetryResult.rejected("DYNAMIC_MQTT_PAYLOAD_INVALID: 时段载荷与普通指标互斥");
        if(!validator.validate(interval).isEmpty())
            return StandardTelemetryResult.rejected("DYNAMIC_MQTT_PAYLOAD_INVALID: 时段字段或单位无效");
        try {
            if(interval.startInclusive()!=null&&interval.startInclusive().getNano()%1000000!=0
                    ||interval.endExclusive()!=null&&interval.endExclusive().getNano()%1000000!=0)
                throw new IllegalArgumentException("DYNAMIC_DEVICE_TIME_PRECISION");
            // 简化周期契约的设备eventTime表示区间结束；接收时间绝不参与推导。
            var normalized=new com.platform.adapter.model.ElectricityInterval("Q",interval.startInclusive()==null?0:interval.startInclusive().toEpochMilli(),
                    interval.endExclusive()==null?0:interval.endExclusive().toEpochMilli(),interval.revision(),interval.electricityKwh(),interval.factorKgCo2PerKwh(),
                    interval.electricityUnit(),interval.factorUnit(),interval.sourceNature(),interval.sourceReference())
                    .boundaries("DEVICE_REPORTED".equals(message.timeSource())?message.eventTime():null);
            var request=new DeviceIntervalRequest(message.deviceIdentity(),message.profileCode(),interval.pointId(),
                    java.time.Instant.ofEpochMilli(normalized.startMs()),java.time.Instant.ofEpochMilli(normalized.endMs()),interval.revision(),interval.electricityKwh(),
                    interval.factorKgCo2PerKwh(),interval.sourceNature(),interval.sourceReference(),interval.revision()==0?
                    new com.platform.carbon.DynamicElectricityContracts.SourceOrder(message.bootId(),message.seq(),message.sourceMessageId(),"DEVICE_FIELDS"):null);
            live.ingestFromMqtt(request);
            return StandardTelemetryResult.accepted((request.electricityKwh()==null?0:1)+(request.factorKgCo2PerKwh()==null?0:1));
        } catch(BusinessException failure) {
            // 可修复的依赖故障保留MQTT重投，非法归属或同版本异值稳定拒绝，不能先确认后丢失任务。
            return failure.getCode()>=500?StandardTelemetryResult.retryable(0,failure.getErrorCode())
                    :StandardTelemetryResult.rejected(failure.getErrorCode());
        } catch(IllegalArgumentException failure) {
            return StandardTelemetryResult.rejected(failure.getMessage());
        }
    }
}
