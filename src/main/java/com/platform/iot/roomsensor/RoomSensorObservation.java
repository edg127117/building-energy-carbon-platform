package com.platform.iot.roomsensor;

/** 持久队列冻结的最小观测；receivedAt 是平台 UTC 毫秒，deviceTime 仅保留厂家原值。 */
public record RoomSensorObservation(
        String eventId, String sourceId, String protocol, long storageTs, long receivedAt,
        String deviceTime, String identityId, String equipId, String buildingId, String spaceId,
        String gatewayMac, String deviceMac, Integer targetCount, Integer deviceSeq, Boolean doorOpen) {
}
