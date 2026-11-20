package com.platform.iot.roomsensor;

import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 按设备归属读取房间传感器观测。调用方负责设备及建筑访问权限。 */
@Service
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorReadings {
    private static final int DOOR_HISTORY_LIMIT = 100;
    private final JdbcTemplate mysql;
    private final RoomSensorDoorStateService doors;
    private final RoomSensorRadarStateService radars;
    private final RoomSensorHistoryStore history;

    public RoomSensorReadings(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
                              RoomSensorDoorStateService doors, RoomSensorRadarStateService radars,
                              RoomSensorHistoryStore history) {
        this.mysql = mysql;
        this.doors = doors;
        this.radars = radars;
        this.history = history;
    }

    public enum Kind { DOOR, RADAR }
    public enum ContactKind { DOOR, WINDOW }
    public record ContactInstallation(String sourceId, ContactKind kind, String location) { }

    public Optional<ContactInstallation> contactInstallation(String buildingId, String equipmentId) {
        var bindings = RoomSensorStateBinding.forEquipmentContact(mysql, buildingId, equipmentId);
        return bindings.size() == 1 ? Optional.of(installation(bindings.getFirst())) : Optional.empty();
    }

    /** 同一设备事务内按原来源写入。重新绑定后需重新打开设备详情。 */
    public void updateContactInstallation(String buildingId, String equipmentId, String sourceId,
                                          ContactKind kind, String location) {
        int updated = mysql.update("""
                UPDATE biz_room_sensor_source SET contact_kind=?,contact_location=?
                WHERE source_id=? AND protocol='S4_DOOR' AND enabled=1 AND identity_id IN (
                    SELECT i.identity_id FROM biz_device_identity i
                    JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.building_id=i.building_id AND e.del_flag=0
                    JOIN biz_space p ON p.space_id=e.space_id AND p.building_id=e.building_id AND p.del_flag=0
                    WHERE e.building_id=? AND e.equip_id=? AND p.space_type='ROOM'
                        AND i.status=1 AND i.identity_type='MAC' AND i.expected_profile_code='S4_DOOR'
                )
                """, kind.name(), location.strip(), sourceId, buildingId, equipmentId);
        if (updated != 1) throw new BusinessException(409, "ROOM_SENSOR_BINDING_CHANGED", "门窗磁绑定已变更，请重新打开设备详情");
    }

    private static ContactInstallation installation(RoomSensorStateBinding binding) {
        return new ContactInstallation(binding.source().sourceId(), binding.contactKind(), binding.contactLocation());
    }

    // 当前值为空表示未观测或已过期，最近观测仍保留；0 和关门都是有效观测。
    public record Reading(String sourceId, Kind kind, Boolean currentOpen, Boolean lastObservedOpen,
                          Integer currentCount, Integer lastObservedCount, Long lastObservedAt) { }

    public record HistorySample(long observedAt, Integer count, Boolean open) { }
    public record History(String sourceId, Kind kind, Integer intervalSeconds,
                          boolean truncated, List<HistorySample> samples) { }

    public List<Reading> read(String buildingId, String equipmentId, long asOf) {
        return RoomSensorStateBinding.forEquipment(mysql, buildingId, equipmentId).stream()
                .map(binding -> reading(binding, asOf)).toList();
    }

    public Optional<Reading> defaultReading(String buildingId, String equipmentId, String typeCode, long asOf) {
        String protocol = switch (typeCode) {
            case "RADAR_SENSOR" -> "MSR01_RADAR";
            case "DOOR_SENSOR" -> "S4_DOOR";
            default -> null;
        };
        if (protocol == null) return Optional.empty();
        var bindings = RoomSensorStateBinding.forEquipment(mysql, buildingId, equipmentId).stream()
                .filter(binding -> protocol.equals(binding.source().protocol())).limit(2).toList();
        return bindings.size() == 1 ? Optional.of(reading(bindings.getFirst(), asOf)) : Optional.empty();
    }

    /** 历史按平台接收时间及当前设备绑定查询；人数每桶取最后一次观测，门磁合并连续相同状态。 */
    public List<History> readHistory(String buildingId, String equipmentId, long start, long end, int intervalSeconds) {
        return RoomSensorStateBinding.forEquipment(mysql, buildingId, equipmentId).stream()
                .map(binding -> history(binding.source(), start, end, intervalSeconds)).toList();
    }

    private History history(RoomSensorSource source, long start, long end, int intervalSeconds) {
        var observations = history.range(source, start, end);
        int size = Math.min(observations.size(), RoomSensorHistoryStore.RANGE_SCAN_LIMIT);
        boolean truncated = size < observations.size();
        List<HistorySample> samples = new ArrayList<>();
        if ("MSR01_RADAR".equals(source.protocol())) {
            // 从最新端取样，保留每个时间桶最后一条实际人数和它的观测时间。
            long previousBucket = -1;
            for (var event : observations.subList(0, size)) {
                long bucket = (event.receivedAt() - start) / (intervalSeconds * 1000L);
                if (bucket == previousBucket) continue;
                samples.add(new HistorySample(event.receivedAt(), event.count(), null));
                previousBucket = bucket;
            }
            return new History(source.sourceId(), Kind.RADAR, intervalSeconds, truncated,
                    List.copyOf(samples.reversed()));
        }
        Boolean lastOpen = null;
        for (var event : observations.subList(0, size).reversed()) {
            if (event.open().equals(lastOpen)) continue;
            samples.add(new HistorySample(event.receivedAt(), null, event.open()));
            lastOpen = event.open();
        }
        int first = Math.max(0, samples.size() - DOOR_HISTORY_LIMIT);
        return new History(source.sourceId(), Kind.DOOR, null, truncated || first > 0,
                List.copyOf(samples.subList(first, samples.size())));
    }

    private Reading reading(RoomSensorStateBinding binding, long asOf) {
        String sourceId = binding.source().sourceId();
        if ("S4_DOOR".equals(binding.source().protocol())) {
            var state = doors.read(binding, asOf);
            return new Reading(sourceId, Kind.DOOR, state.currentOpen(), state.lastObservedOpen(),
                    null, null, state.lastObservedAt());
        }
        var state = radars.read(binding, asOf);
        return new Reading(sourceId, Kind.RADAR, null, null,
                state.currentCount(), state.lastObservedCount(), state.lastObservedAt());
    }
}
