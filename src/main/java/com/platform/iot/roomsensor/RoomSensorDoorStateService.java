package com.platform.iot.roomsensor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 从当前可信绑定读取最近已入库门状态，按平台接收时间判定有效性。 */
@Service
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorDoorStateService {
    private final JdbcTemplate mysql;
    private final RoomSensorHistoryStore history;

    public RoomSensorDoorStateService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql, RoomSensorHistoryStore history) {
        this.mysql = mysql;
        this.history = history;
    }

    public record DoorState(Boolean currentOpen, Boolean lastObservedOpen, Long lastObservedAt) { }
    /** 调用方须先核对建筑访问权限；来源未启用时返回空。 */
    public Optional<DoorState> read(String buildingId, String sourceId, long asOf) {
        return RoomSensorStateBinding.find(mysql, buildingId, sourceId, "S4_DOOR")
                .map(binding -> read(binding, asOf));
    }

    DoorState read(RoomSensorStateBinding binding, long asOf) {
        return history.latestDoor(binding.source(), asOf)
                .map(reading -> project(reading, binding.freshnessSeconds(), asOf))
                .orElseGet(() -> new DoorState(null, null, null));
    }

    private static DoorState project(RoomSensorHistoryStore.DoorReading reading, int freshnessSeconds, long asOf) {
        boolean fresh = asOf - reading.receivedAt() < freshnessSeconds * 1000L;
        return new DoorState(fresh ? reading.open() : null, reading.open(), reading.receivedAt());
    }
}
