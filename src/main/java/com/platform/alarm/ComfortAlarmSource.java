package com.platform.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.comfort.ComfortPmvService;
import com.platform.iot.comfort.ComfortPmvService.AlarmMeasurement;
import com.platform.iot.comfort.ComfortQueryService;
import com.platform.iot.comfort.ComfortQueryService.Space;
import com.platform.iot.roomsensor.RoomSensorObservation;
import com.platform.iot.roomsensor.RoomSensorRoomQueryService;
import com.platform.iot.roomsensor.RoomSensorRoomQueryService.DoorAlarmReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import static com.platform.alarm.AlarmModels.*;

/** 将房间 PMV 和逐门开关观测转换为平台报警判定所需的证据。 */
@Component
class ComfortAlarmSource {
    private static final Logger log = LoggerFactory.getLogger(ComfortAlarmSource.class);
    static final String PMV_RULE = "comfort-pmv-limit";
    static final String DOOR_RULE = "comfort-door-timeout";
    private static final BigDecimal PMV_LIMIT = new BigDecimal("0.5");
    private static final long DOOR_TIMEOUT_MILLIS = 60_000;
    static final List<Rule> RULES = List.of(
            new Rule(PMV_RULE, "CONDITION", "PMV 越限", "IMPORTANT", "Q0", null, null,
                    true, 1, 1, null, null, 1, 30, 10, "ALL", "VALUE", List.of()),
            new Rule(DOOR_RULE, "CONDITION", "门开超时", "GENERAL", "Q0", null, null,
                    true, 1, 1, null, null, 1, 0, 0, "ALL", "VALUE", List.of()));

    private final ComfortQueryService rooms;
    private final ComfortPmvService pmv;
    private final RoomSensorRoomQueryService sensors;
    private final ObjectMapper json;

    ComfortAlarmSource(ComfortQueryService rooms, ComfortPmvService pmv,
                       RoomSensorRoomQueryService sensors, ObjectMapper json) {
        this.rooms = rooms;
        this.pmv = pmv;
        this.sensors = sensors;
        this.json = json;
    }

    List<RoomEvidence> load(String building, long now) {
        return rooms.alarmSpaces(building).stream().map(room -> new RoomEvidence(room,
                read(room, PMV_RULE, () -> pmv.readForAlarm(rooms, room, now)),
                read(room, DOOR_RULE, () -> sensors.doorsForAlarm(building, room.spaceId(), now)))).toList();
    }

    boolean matchesDoorBinding(RoomSensorObservation observed) {
        return sensors.matchesDoorBinding(observed);
    }

    Candidate pmvCandidate(RoomEvidence room) {
        var measurement = room.pmv();
        var scope = room.space();
        String inputKey = AlarmService.shortHash(measurement.inputsAndBindings());
        boolean outside = measurement.value().abs().compareTo(PMV_LIMIT) > 0;
        var evidence = json.createObjectNode().put("observedAt", measurement.observedAt()).put("inputVersion", inputKey);
        evidence.putArray("observations").addObject().put("pointName", "PMV").put("value", measurement.value());
        return new Candidate("VALUE", PMV_RULE, scope.spaceId(), "房间环境", scope.spaceId(), null,
                scope.spaceName(), null, "IMPORTANT", "PMV 越限持续 30 分钟", evidence.toString(), outside,
                measurement.observedAt(), measurement.observedAt() + ":" + inputKey, inputKey);
    }

    Candidate doorCandidate(RoomEvidence room, DoorAlarmReading door, long now) {
        if (door.openedAt() == null || now - door.openedAt() <= DOOR_TIMEOUT_MILLIS) return null;
        var scope = room.space();
        long seconds = (now - door.openedAt()) / 1000;
        var evidence = json.createObjectNode().put("openedAt", door.openedAt()).put("observedAt", door.observedAt());
        evidence.putArray("observations").addObject().put("pointName", "开门时长").put("value", seconds).put("unit", "秒");
        return new Candidate("VALUE", DOOR_RULE, door.equipmentId(), door.name(), scope.spaceId(), null,
                scope.spaceName(), null, "GENERAL", "门开超时（超过 60 秒）", evidence.toString());
    }

    private static <T> T read(Space room, String rule, Supplier<T> source) {
        try {
            return source.get();
        } catch (RuntimeException failure) {
            log.warn("房间报警事实不可用，房间={}，规则={}，异常类型={}", room.spaceId(), rule,
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    record RoomEvidence(Space space, AlarmMeasurement pmv, List<DoorAlarmReading> doors) {
        RoomEvidence {
            if (doors == null) doors = List.of();
        }
    }
}
