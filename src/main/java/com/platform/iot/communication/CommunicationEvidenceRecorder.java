package com.platform.iot.communication;

import com.platform.iot.identity.DeviceIdentityBinding;
import com.platform.iot.ingest.standard.StandardTelemetryMessage;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.model.DaikinFieldValue;
import com.platform.iot.daikin.monitoring.DaikinMonitoringTargets;
import com.platform.iot.roomsensor.RoomSensorSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static com.platform.iot.communication.CommunicationModels.Signal;

/** 将现有入口的已验证身份/实时观测转换为通讯证据；副作用失败不改写既有存储 ACK 语义。 */
@Component
@Slf4j
public class CommunicationEvidenceRecorder {
    private static final ThreadLocal<Boolean> LIVE = new ThreadLocal<>();
    private final DeviceCommunicationService communication;
    private final JdbcTemplate jdbc;
    private final AtomicBoolean mqttReady=new AtomicBoolean();
    private final AtomicLong handlingSince=new AtomicLong();
    private final java.util.concurrent.ConcurrentHashMap<String,Boolean> evidenceFailures=new java.util.concurrent.ConcurrentHashMap<>();
    public CommunicationEvidenceRecorder(DeviceCommunicationService communication,@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.communication=communication;this.jdbc=jdbc;
    }
    public void beginMqtt(boolean fresh,long now) { LIVE.set(fresh);handlingSince.set(now); }
    public void endMqtt() { LIVE.remove();handlingSince.set(0); }
    public void mqttReady(boolean ready) { mqttReady.set(ready); }
    public boolean evidenceHealthy(String source) { return !evidenceFailures.containsKey(source); }
    public boolean mqttHealthy(long now,long lease) { return mqttReady.get()&&evidenceHealthy("MQTT")&&(handlingSince.get()==0||now-handlingSince.get()<lease); }

    public void standard(DeviceIdentityBinding binding,StandardTelemetryMessage message,long received) {
        if(Boolean.FALSE.equals(LIVE.get()))return;
        long observed="SERVER_RECEIVED".equals(message.timeSource())?message.receivedTime():message.eventTime();
        safe(new Signal(binding.identityId(),binding.equipmentId(),binding.buildingId(),"MQTT",
                "STANDARD:"+message.eventTime()+":"+message.seq(),observed,Math.min(message.receivedTime(),received),false,"LIVE_TELEMETRY",null,binding.expectedProfileCode()));
    }
    public void legacy(RawTelemetryEvent event) {
        if(Boolean.FALSE.equals(LIVE.get())||event.equipId()==null)return;
        try {
            var identities=jdbc.queryForList("SELECT identity_id,expected_profile_code FROM biz_device_identity WHERE equip_id=? AND building_id=? AND status=1 AND identity_type<>'DAIKIN_UNIT'",event.equipId(),event.buildingId());
            if(identities.size()!=1)return;
            safe(new Signal((String)identities.getFirst().get("identity_id"),event.equipId(),event.buildingId(),"MQTT","LEGACY:"+event.eventTime(),event.eventTime(),event.receivedTime(),false,"LIVE_TELEMETRY",null,(String)identities.getFirst().get("expected_profile_code")));
        } catch(RuntimeException failed) { log.warn("通讯证据身份查询失败 equipmentId={} cause={}",event.equipId(),failed.getClass().getSimpleName()); }
    }
    /** 房间传感器设备时间原样归档，通讯超时使用 MQTT 回调接收时刻。 */
    public void roomSensor(RoomSensorSource source, long receivedAt) {
        if (Boolean.FALSE.equals(LIVE.get())) return;
        String protocol = source.protocol();
        if (!"S4_DOOR".equals(protocol) && !"MSR01_RADAR".equals(protocol)) return;
        safe(new Signal(source.identityId(), source.equipId(), source.buildingId(), "MQTT",
                protocol + ":" + receivedAt, receivedAt, receivedAt, false, "LIVE_TELEMETRY", null, protocol));
    }
    /** 继承单点先校验正式别名/设备身份，量程错误仍可证明通讯；不接受未归属载荷续租。 */
    public void legacyEnvelope(java.util.Map<String,Object> payload,long received,String sourceSystem) {
        if(Boolean.FALSE.equals(LIVE.get())||payload==null)return;
        if(!(payload.get("buildingId") instanceof String building)||!(payload.get("deviceId") instanceof String equipmentCode)
                ||!(payload.get("pointCode") instanceof String pointCode)||!(payload.get("timestamp") instanceof Number stamp)
                ||!(payload.get("val") instanceof Number number)||!Double.isFinite(number.doubleValue()))return;
        long observed;
        try {observed=new java.math.BigDecimal(stamp.toString()).longValueExact();}
        catch(NumberFormatException|ArithmeticException malformed){return;}
        try {
            var identities=jdbc.queryForList("""
                SELECT i.identity_id,i.expected_profile_code,e.equip_id
                FROM biz_point_alias a JOIN biz_data_point p ON p.point_id=a.point_id AND p.building_id=a.building_id
                JOIN biz_equipment e ON e.equip_id=p.equip_id AND e.building_id=p.building_id AND e.del_flag=0
                JOIN biz_device_identity i ON i.equip_id=e.equip_id AND i.building_id=e.building_id AND i.status=1 AND i.identity_type<>'DAIKIN_UNIT'
                LEFT JOIN biz_data_source ds ON ds.source_id=a.source_id AND ds.building_id=a.building_id
                WHERE a.building_id=? AND a.source_system=? AND a.source_point_code=? AND a.status=1
                  AND p.del_flag=0 AND p.status='ONLINE' AND e.equip_code=? AND (a.source_id IS NULL OR ds.status='ENABLED')
                """,building,sourceSystem,pointCode,equipmentCode);
            if(identities.size()!=1)return;
            var identity=identities.getFirst();
            safe(new Signal((String)identity.get("identity_id"),(String)identity.get("equip_id"),building,"MQTT",
                    "LEGACY:"+observed,observed,received,false,"LIVE_TELEMETRY",null,(String)identity.get("expected_profile_code")));
        } catch(RuntimeException unavailable) {
            evidenceFailures.put("MQTT",true);
            try {communication.sourceHealth("MQTT","FAILED",System.currentTimeMillis());}
            catch(RuntimeException ignored){ /* 健康租约过期后仍会回到未知，不能补造在线。 */ }
            log.warn("继承单点通讯归属暂不可用 cause={}",unavailable.getClass().getSimpleName());
        }
    }
    /** 厂家当轮设备观测先于温度落盘记录；重放箱不会再次调用这里。 */
    public void daikin(DaikinMonitoringTargets.Target target,long round,DaikinDeviceObservation observation) {
        if(!target.key().equals(observation.key()))return;
        DaikinFieldValue error=observation.fields().get("inCommunicationError");
        boolean offline=error!=null && error.status()==DaikinFieldValue.Status.PRESENT && "true".equals(error.normalizedValue());
        boolean usable=observation.fields().entrySet().stream().anyMatch(e->!e.getKey().startsWith("controller.")&&e.getValue().status()==DaikinFieldValue.Status.PRESENT);
        if(!offline&&!usable)return;
        safe(new Signal(target.identityId(),target.equipmentId(),target.buildingId(),"DAIKIN:"+target.key().sourceId(),
                "DAIKIN:"+target.mappingVersion()+":"+round,observation.observedAt().toEpochMilli(),System.currentTimeMillis(),offline,offline?"DEVICE_REPORTED_OFFLINE":"LIVE_POLL",target.mappingVersion(),target.profileCode()));
    }
    private void safe(Signal signal) {
        try { if(communication.observe(signal))evidenceFailures.remove(signal.source()); }
        catch(RuntimeException failure){
            // 连接正常不等于状态存储正常；只有后续成功保存的新证据才解除故障锁存。
            evidenceFailures.put(signal.source(),true);
            try { communication.sourceHealth(signal.source(),"FAILED",System.currentTimeMillis()); }
            catch(RuntimeException healthUnavailable){log.warn("通讯故障租约暂不可写 source={}",signal.source());}
            log.warn("通讯证据暂不可用 identityId={} cause={}",signal.identityId(),failure.getClass().getSimpleName());
        }
    }
}
