package com.platform.alarm;

import java.util.List;

/**
 * 厂商或平台设备故障事实的只读适配边界。抛异常表示未知；active=false表示已知正常。
 * 非大金来源须返回所监测设备的正常事实以证明覆盖范围，空列表不冒充设备全部正常。
 */
public interface AlarmFaultSource {
    List<FaultReading> load(String buildingId);

    record FaultReading(String identityId, String buildingId, String deviceId, String code,
            boolean active, long eventAt, String message) { }
}
