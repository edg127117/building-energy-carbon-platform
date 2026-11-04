package com.platform.iot.ingest.v2;

import com.platform.carbon.DynamicElectricityContracts.DeviceIntervalRequest;
import com.platform.carbon.DynamicElectricityLiveService;
import com.platform.iot.identity.DeviceIdentityBinding;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointAliasKey;
import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;

/** V2点码按现有别名解析，冻结后复用周期计算服务；消息不能声明可信建筑或任务角色。 */
@Service
public class DynamicElectricityV2Input {
    private final DynamicElectricityLiveService live;
    private final DataPointConfigProvider points;
    private final String source;
    public DynamicElectricityV2Input(DynamicElectricityLiveService live,DataPointConfigProvider points,
            @Value("${ingestion.standard-source-system:MQTT_STANDARD_V1}") String source) {
        this.live=live;this.points=points;this.source=source;
    }
    public DeviceIntervalRequest prepare(TelemetryV2Message message,DeviceIdentityBinding binding) {
        var value=message.electricityInterval();value.validate();
        if(value.endMs()>System.currentTimeMillis())throw invalid("DYNAMIC_DEVICE_INTERVAL_INVALID");
        var key=new PointAliasKey(binding.buildingId(),source,message.deviceIdentity().type()+":"+message.deviceIdentity().value()+":"+value.metricCode());
        var point=points.find(key).orElseThrow(()->invalid("DYNAMIC_POINT_ALIAS_MISSING"));
        if(!binding.buildingId().equals(point.buildingId())||!binding.equipmentId().equals(point.equipId())
                ||!"ONLINE".equalsIgnoreCase(point.status())||!"kWh".equalsIgnoreCase(point.unit()))throw invalid("DYNAMIC_DEVICE_SCOPE");
        return new DeviceIntervalRequest(message.deviceIdentity(),message.profileCode(),point.pointId(),Instant.ofEpochMilli(value.startMs()),Instant.ofEpochMilli(value.endMs()),
                value.revision(),value.electricityKwh(),value.factorKgCo2PerKwh(),value.sourceNature(),value.sourceReference());
    }
    public void persist(DeviceIntervalRequest request,DeviceIdentityBinding binding) {live.ingestFromMqttV2(request,binding);}
    private static BusinessException invalid(String code){return new BusinessException(409,code,"设备周期点码、单位或归属无效");}
}
