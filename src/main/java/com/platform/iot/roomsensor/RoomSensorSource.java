package com.platform.iot.roomsensor;

/** 来自启用身份、资产及空间联查的归属；报文 mark_name 不参与房间解析。 */
public record RoomSensorSource(String sourceId, String protocol, String topic, String gatewayMac,
        String deviceMac, String identityId, String equipId, String buildingId, String spaceId,
        long lastStorageTs) {
}
