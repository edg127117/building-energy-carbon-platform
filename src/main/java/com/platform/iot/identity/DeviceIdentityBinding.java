package com.platform.iot.identity;

/**
 * MQTT 热路径使用的不可变设备归属。
 *
 * @param identityId 预注册记录 ID
 * @param identityKey 外部设备身份
 * @param equipmentId 平台物理设备 ID
 * @param equipmentCode 建筑内设备编码
 * @param buildingId 设备所属建筑
 * @param expectedProfileCode 允许该设备使用的协议模板代码
 */
public record DeviceIdentityBinding(
        String identityId,
        DeviceIdentityKey identityKey,
        String equipmentId,
        String equipmentCode,
        String buildingId,
        String expectedProfileCode
) {
}
