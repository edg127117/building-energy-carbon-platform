package com.platform.alarm;

import java.util.List;

/**
 * 厂商或平台设备故障事实的只读适配边界。抛异常或 active=null 表示未知，active=false 表示已知正常。
 * 非大金来源须返回所监测设备的正常事实以证明覆盖范围，空列表不冒充设备全部正常。
 */
public interface AlarmFaultSource {
    List<FaultReading> load(String buildingId);

    record FaultReading(String identityId, String buildingId, String deviceId, String code,
            Boolean active, long eventAt, String message, Long observationCycle) {
        public FaultReading(String identityId, String buildingId, String deviceId, String code,
                boolean active, long eventAt, String message) {
            this(identityId, buildingId, deviceId, code, active, eventAt, message, null);
        }
    }
}
