package com.platform.iot.roomsensor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.platform.iot.temporal.model.RawTelemetryEvent;

/** 持久队列冻结的最小观测；receivedAt 是平台 UTC 毫秒，deviceTime 仅保留厂家原值。 */
public record RoomSensorObservation(
        String eventId, String sourceId, String protocol, long storageTs, long receivedAt,
        String deviceTime, String identityId, String equipId, String buildingId, String spaceId,
        String gatewayMac, String deviceMac, Integer targetCount, Integer deviceSeq, Boolean doorOpen,
        @JsonInclude(JsonInclude.Include.NON_NULL) RawTelemetryEvent unifiedEvent,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean unifiedOnly) {
    public RoomSensorObservation(String eventId, String sourceId, String protocol, long storageTs, long receivedAt,
            String deviceTime, String identityId, String equipId, String buildingId, String spaceId,
            String gatewayMac, String deviceMac, Integer targetCount, Integer deviceSeq, Boolean doorOpen) {
        this(eventId, sourceId, protocol, storageTs, receivedAt, deviceTime, identityId, equipId, buildingId,
                spaceId, gatewayMac, deviceMac, targetCount, deviceSeq, doorOpen, null, null);
    }

    String scope() {
        return RoomSensorHistoryStore.scope(sourceId, identityId, equipId, buildingId, spaceId, gatewayMac, deviceMac);
    }
}
