package com.platform.alarm;

import java.util.List;

/** 读取设备侧已经持久化的采集事实，失败时必须返回不可用或抛错，不能伪造恢复。 */
public interface AlarmDeviceSource {
    AlarmModels.DeviceResult load(String buildingId, List<AlarmModels.Rule> rules, long now);
}
