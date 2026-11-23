package com.platform.iot.roomsensor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 按平台接收时间判断当前人数，保留上一次观测供调用方解释数据缺失。 */
@Service
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorRadarStateService {
    private static final long FRESHNESS_MILLIS = 10_000L;

    private final JdbcTemplate mysql;
    private final RoomSensorHistoryStore history;

    public RoomSensorRadarStateService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql, RoomSensorHistoryStore history) {
        this.mysql = mysql;
        this.history = history;
    }

    public record RadarState(Integer currentCount, Integer lastObservedCount, Long lastObservedAt) { }

    /** 调用方须先核对建筑访问权限；绑定已启用但没有有效人数时，currentCount 为空。 */
    public Optional<RadarState> read(String buildingId, String sourceId, long asOf) {
        return RoomSensorStateBinding.find(mysql, buildingId, sourceId, "MSR01_RADAR")
                .map(binding -> read(binding, asOf));
    }

    RadarState read(RoomSensorStateBinding binding, long asOf) {
        return history.latestRadar(binding.source(), asOf)
                .map(reading -> project(reading, asOf))
                .orElseGet(() -> new RadarState(null, null, null));
    }

    static RadarState project(RoomSensorHistoryStore.RadarReading reading, long asOf) {
        Integer currentCount = asOf - reading.receivedAt() < FRESHNESS_MILLIS ? reading.targetCount() : null;
        return new RadarState(currentCount, reading.targetCount(), reading.receivedAt());
    }
}
