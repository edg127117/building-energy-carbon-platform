package com.platform.iot.roomsensor;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.communication.DeviceCommunicationService;
import com.platform.iot.roomsensor.RoomSensorReadings.ContactKind;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 舒适度室内传感器只读投影。调用方先核对菜单和房间所属建筑权限。 */
@Service
public class RoomSensorRoomQueryService {
    static final long HOUR = 3_600_000L;
    private static final long MINUTE = 60_000L;
    private static final int MINUTES_PER_HOUR = 60;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate mysql;
    private final ObjectProvider<RoomSensorHistoryStore> histories;
    private final DeviceCommunicationService communications;
    private final Clock clock;

    @Autowired
    public RoomSensorRoomQueryService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
                                      ObjectProvider<RoomSensorHistoryStore> histories,
                                      DeviceCommunicationService communications) {
        this(mysql, histories, communications, Clock.systemUTC());
    }

    RoomSensorRoomQueryService(JdbcTemplate mysql, ObjectProvider<RoomSensorHistoryStore> histories,
                               DeviceCommunicationService communications, Clock clock) {
        this.mysql = mysql;
        this.histories = histories;
        this.communications = communications;
        this.clock = clock;
    }

    public Occupancy occupancy(String buildingId, String spaceId) {
        long now = clock.millis();
        LocalDate date = localDate(now);
        long day = dayStart(date);
        long firstMinute = firstSlot(now, MINUTE, 120);
        long firstQuarter = firstSlot(now, 15 * MINUTE, 8);
        Selection selected = select(buildingId, spaceId, "MSR01_RADAR");
        var binding = selected.binding();
        long from = Math.min(day, Math.min(firstMinute, firstQuarter));
        var rows = binding == null ? List.<RoomSensorHistoryStore.RadarReading>of()
                : selected.history().radarHistory(binding.source(), from, now);
        var latest = !rows.isEmpty() ? rows.getLast() : binding != null
                ? selected.history().latestRadar(binding.source(), from - 1).filter(row -> row.targetCount() >= 0).orElse(null) : null;
        var current = latest == null ? null : RoomSensorRadarStateService.project(latest, now);

        var minutes = samples(rows, firstMinute, MINUTE, 120);
        var quarters = samples(rows, firstQuarter, 15 * MINUTE, 8);
        var averages = averages(rows, day);
        var communication = communication(binding);
        verifyBinding(selected, buildingId, spaceId);
        Integer currentCount = current == null ? null : current.currentCount();
        return new Occupancy(date, ZONE.getId(), instant(now), status(selected, latest != null, currentCount != null),
                communication, currentCount,
                latest == null ? null : latest.targetCount(), latest == null ? null : instant(latest.receivedAt()),
                minutes, quarters, averages);
    }

    public DoorStats doorStats(String buildingId, String spaceId) {
        return doorStats(buildingId, spaceId, null);
    }

    public DoorStats doorStats(String buildingId, String spaceId, String equipmentId) {
        long now = clock.millis();
        LocalDate date = localDate(now);
        long day = dayStart(date);
        var history = histories.getIfAvailable();
        var bindings = history == null ? List.<RoomSensorStateBinding>of() : contactBindings(buildingId, spaceId);
        var doors = doorBindings(bindings);
        var binding = equipmentId == null ? (doors.size() == 1 ? doors.getFirst() : null)
                : doors.stream().filter(door -> equipmentId.equals(door.source().equipId())).findFirst()
                        .orElseThrow(() -> new BusinessException(404, "COMFORT_DOOR_NOT_FOUND", "当前房间没有此门磁"));
        // 读保留期内的状态变化，以便接续跨日开启并显示最近几条事件。
        long from = now - RoomSensorWorker.RETENTION_MILLIS;
        var rows = binding == null ? List.<RoomSensorHistoryStore.DoorReading>of()
                : history.doorHistory(binding.source(), from, now);
        var latest = rows.isEmpty() ? null : rows.getLast();
        Boolean currentOpen = currentOpen(binding, latest, now);
        var activity = RoomSensorDoorActivity.summarize(rows, day, now, currentOpen, binding == null ? 0 : binding.freshnessSeconds());
        var otherBindings = bindings.stream().filter(item -> !item.equals(binding)).toList();
        var otherReadings = otherBindings.isEmpty() ? Map.<String, RoomSensorHistoryStore.DoorReading>of()
                : history.lastDoors(otherBindings, now);
        var contacts = bindings.stream().map(item -> contact(item, bindings, now,
                item.equals(binding) ? latest : otherReadings.get(item.source().sourceId()))).toList();
        var communication = communication(binding);
        if (history != null) verifyContacts(bindings, buildingId, spaceId);
        Status status = doorStatus(history != null, doors, bindings, binding != null, latest != null, currentOpen != null);
        return new DoorStats(date, ZONE.getId(), instant(now), status, communication, currentOpen,
                latest == null ? null : latest.open(), latest == null ? null : instant(latest.receivedAt()),
                activity.lastTransitionAt(), activity.currentDurationSec(), activity.recentEvents(),
                activity.hourlyRows(), activity.durationBuckets(), binding == null ? null : binding.source().equipId(),
                contacts, summarizeContacts(contacts, ContactKind.DOOR), summarizeContacts(contacts, ContactKind.WINDOW));
    }

    private static Status doorStatus(boolean enabled, List<RoomSensorStateBinding> doors, List<RoomSensorStateBinding> bindings,
                                     boolean selected, boolean observed, boolean current) {
        if (!enabled) return Status.DISABLED;
        if (doors.isEmpty()) return bindings.stream().anyMatch(item -> item.contactKind() == null) ? Status.AMBIGUOUS : Status.UNCONFIGURED;
        if (!selected) return Status.SELECTION_REQUIRED;
        if (observed) return current ? Status.AVAILABLE : Status.STALE;
        return Status.NO_OBSERVATION;
    }

    private static Boolean currentOpen(RoomSensorStateBinding binding, RoomSensorHistoryStore.DoorReading reading, long now) {
        return reading == null ? null : RoomSensorDoorStateService.project(reading, binding.freshnessSeconds(), now).currentOpen();
    }

    private static Contact contact(RoomSensorStateBinding binding, List<RoomSensorStateBinding> siblings,
                                   long now, RoomSensorHistoryStore.DoorReading reading) {
        var kind = binding.contactKind() == null && siblings.size() == 1 ? ContactKind.DOOR : binding.contactKind();
        var state = currentOpen(binding, reading, now);
        return new Contact(binding.source().equipId(), binding.displayName(), kind, state,
                reading == null ? null : reading.open(), reading == null ? null : instant(reading.receivedAt()));
    }

    private static ContactSummary summarizeContacts(List<Contact> contacts, ContactKind kind) {
        var items = contacts.stream().filter(contact -> kind == contact.kind()).toList();
        int open = (int) items.stream().filter(contact -> Boolean.TRUE.equals(contact.open())).count();
        int closed = (int) items.stream().filter(contact -> Boolean.FALSE.equals(contact.open())).count();
        return new ContactSummary(open, closed, items.size() - open - closed);
    }

    /** 每扇门单独读取开启和关闭证据，窗磁沿用同一采集协议但不参与门开报警。 */
    public List<DoorAlarmReading> doorsForAlarm(String buildingId, String spaceId, long asOf) {
        var history = histories.getIfAvailable();
        if (history == null) return List.of();
        var contacts = contactBindings(buildingId, spaceId);
        var readings = new ArrayList<DoorAlarmReading>();
        for (var binding : doorBindings(contacts)) {
            var reading = doorForAlarm(history, binding, asOf);
            if (reading != null) readings.add(reading);
        }
        verifyContacts(contacts, buildingId, spaceId);
        return readings;
    }

    private static DoorAlarmReading doorForAlarm(RoomSensorHistoryStore history, RoomSensorStateBinding binding, long asOf) {
        var source = binding.source();
        var latest = history.latestDoor(source, asOf).orElse(null);
        if (latest == null) return null;
        var closed = latest.open() ? history.lastClosedDoor(source, latest.receivedAt()).orElse(null) : latest;
        boolean open = Boolean.TRUE.equals(RoomSensorDoorStateService.project(latest, binding.freshnessSeconds(), asOf).currentOpen());
        var opening = open && closed != null ? history.openingAfterClose(source, closed.receivedAt(), latest.receivedAt())
                .orElse(null) : null;
        return new DoorAlarmReading(source.equipId(), binding.displayName(), closed == null ? null : closed.receivedAt(),
                opening == null ? null : opening.receivedAt(), latest.receivedAt());
    }

    public boolean matchesDoorBinding(RoomSensorObservation observed) {
        return doorBindings(contactBindings(observed.buildingId(), observed.spaceId())).stream()
                .map(RoomSensorStateBinding::source)
                .anyMatch(source -> source.equipId().equals(observed.equipId()) && source.identityId().equals(observed.identityId())
                && source.sourceId().equals(observed.sourceId()) && source.gatewayMac().equals(observed.gatewayMac())
                && source.deviceMac().equals(observed.deviceMac()));
    }

    public record DoorAlarmReading(String equipmentId, String name, Long closedAt, Long openedAt, long observedAt) {}

    private List<RoomSensorStateBinding> contactBindings(String buildingId, String spaceId) {
        return RoomSensorStateBinding.findRoom(mysql, buildingId, spaceId, "S4_DOOR");
    }

    private static List<RoomSensorStateBinding> doorBindings(List<RoomSensorStateBinding> bindings) {
        // 兼容只有一只旧门磁的房间。多设备房间只使用已确认安装对象的来源。
        return bindings.size() == 1 && bindings.getFirst().contactKind() == null ? bindings
                : bindings.stream().filter(binding -> binding.contactKind() == ContactKind.DOOR).toList();
    }

    private void verifyContacts(List<RoomSensorStateBinding> bindings, String buildingId, String spaceId) {
        if (!bindings.equals(contactBindings(buildingId, spaceId))) {
            throw new BusinessException(409, "COMFORT_ROOM_SENSOR_BINDING_CHANGED", "房间传感器来源已变更，请重新查询");
        }
    }

    /** 按当前绑定批量读取新鲜门状态。门磁过期或缺测计入未知。 */
    public Map<String, ContactSummary> buildingDoorSummaries(String building, Set<String> monitoredSpaces) {
        var history = histories.getIfAvailable();
        if (history == null || monitoredSpaces.isEmpty()) return Map.of();
        try {
            var grouped = RoomSensorStateBinding.findBuildingDoors(mysql, building).stream()
                    .filter(binding -> monitoredSpaces.contains(binding.source().spaceId()))
                    .collect(Collectors.groupingBy(binding -> binding.source().spaceId()));
            var doors = grouped.values().stream().flatMap(bindings -> doorBindings(bindings).stream()).toList();
            if (doors.isEmpty()) return Map.of();
            long now = clock.millis();
            var readings = history.recentDoors(doors, now);
            var confirmed = RoomSensorStateBinding.findBuildingDoors(mysql, building).stream()
                    .collect(Collectors.groupingBy(binding -> binding.source().spaceId()));
            var result = new HashMap<String, ContactSummary>();
            grouped.forEach((space, bindings) -> {
                if (!bindings.equals(confirmed.get(space))) return;
                var contacts = doorBindings(bindings).stream()
                        .map(binding -> contact(binding, bindings, now, readings.get(binding.source().sourceId()))).toList();
                if (!contacts.isEmpty()) result.put(space, summarizeContacts(contacts, ContactKind.DOOR));
            });
            return result;
        } catch (org.springframework.dao.DataAccessException unavailable) {
            return Map.of();
        }
    }

    private Selection select(String building, String space, String protocol) {
        var history = histories.getIfAvailable();
        if (history == null) return new Selection(protocol, null, null, Status.DISABLED);
        var bindings = RoomSensorStateBinding.findRoom(mysql, building, space, protocol);
        return switch (bindings.size()) {
            case 0 -> new Selection(protocol, history, null, Status.UNCONFIGURED);
            case 1 -> new Selection(protocol, history, bindings.getFirst(), Status.NO_OBSERVATION);
            default -> new Selection(protocol, history, null, Status.AMBIGUOUS);
        };
    }

    private void verifyBinding(Selection selected, String building, String space) {
        // MySQL 绑定和 TDengine 的历史无法共用事务。读取后再次确认房间和来源仍一致。
        if (selected.binding() != null && !RoomSensorStateBinding.findRoom(mysql, building, space, selected.protocol())
                .equals(List.of(selected.binding()))) {
            throw new BusinessException(409, "COMFORT_ROOM_SENSOR_BINDING_CHANGED", "房间传感器来源已变更，请重新查询");
        }
    }

    private Communication communication(RoomSensorStateBinding binding) {
        if (binding == null) return null;
        var source = binding.source();
        var view = communications.equipment(source.equipId(), source.buildingId());
        return new Communication(view.value(), view.reason(), view.observedAt() == null ? null : instant(view.observedAt()));
    }

    private static Status status(Selection selected, boolean observed, boolean current) {
        if (!observed) return selected.emptyStatus();
        return current ? Status.AVAILABLE : Status.STALE;
    }

    private static long firstSlot(long now, long width, int size) {
        return Math.floorDiv(now, width) * width - (size - 1L) * width;
    }

    private static List<CountSlot> samples(List<RoomSensorHistoryStore.RadarReading> rows, long start, long width, int size) {
        var values = lastSamples(rows, start, width, size);
        var result = new ArrayList<CountSlot>(size);
        for (int i = 0; i < size; i++) {
            var row = values[i];
            result.add(new CountSlot(instant(start + i * width), row == null ? null : row.targetCount(),
                    row == null ? null : instant(row.receivedAt())));
        }
        return result;
    }

    private static RoomSensorHistoryStore.RadarReading[] lastSamples(
            List<RoomSensorHistoryStore.RadarReading> rows, long start, long width, int size) {
        // 按接收时间和技术键排序的观测，每槽取最后一条。空槽保留缺测。
        var values = new RoomSensorHistoryStore.RadarReading[size];
        for (var row : rows) {
            if (row.receivedAt() >= start) values[(int) ((row.receivedAt() - start) / width)] = row;
        }
        return values;
    }

    private static List<AverageHour> averages(List<RoomSensorHistoryStore.RadarReading> rows, long day) {
        // 与实时 1 分钟曲线使用相同的样本，避免上报较密集的分钟在均值里占更大权重。
        var minutes = lastSamples(rows, day, MINUTE, 24 * MINUTES_PER_HOUR);
        var sums = new long[24];
        var counts = new int[24];
        for (int minute = 0; minute < minutes.length; minute++) {
            var sample = minutes[minute];
            if (sample == null) continue;
            int hour = minute / MINUTES_PER_HOUR;
            sums[hour] += sample.targetCount();
            counts[hour]++;
        }
        var result = new ArrayList<AverageHour>(24);
        for (int hour = 0; hour < 24; hour++) {
            // 按有效样本计算整点平均后四舍五入为人数，零样本的小时留空。
            result.add(new AverageHour(instant(day + hour * HOUR),
                    counts[hour] == 0 ? null : (int) Math.round((double) sums[hour] / counts[hour]), counts[hour]));
        }
        return result;
    }

    private static LocalDate localDate(long millis) { return instant(millis).atZone(ZONE).toLocalDate(); }
    private static long dayStart(LocalDate date) { return date.atStartOfDay(ZONE).toInstant().toEpochMilli(); }
    static Instant instant(long millis) { return Instant.ofEpochMilli(millis); }

    private record Selection(String protocol, RoomSensorHistoryStore history, RoomSensorStateBinding binding, Status emptyStatus) { }
    public enum Status { DISABLED, UNCONFIGURED, AMBIGUOUS, SELECTION_REQUIRED, NO_OBSERVATION, STALE, AVAILABLE }
    public record Communication(String state, String reason, Instant lastContactAt) { }
    public record Occupancy(LocalDate date, String timeZone, Instant asOf, Status status, Communication communication,
                            Integer currentCount, Integer lastObservedCount, Instant lastObservedAt,
                            List<CountSlot> series1m, List<CountSlot> series15m, List<AverageHour> hourlyAvg) { }
    public record CountSlot(Instant startsAt, Integer count, Instant observedAt) { }
    public record AverageHour(Instant startsAt, Integer averageCount, int sampleCount) { }
    public record DoorStats(LocalDate date, String timeZone, Instant asOf, Status status, Communication communication,
                            Boolean doorOpen, Boolean lastObservedOpen, Instant lastObservedAt, Instant lastTransitionAt,
                            Double currentDurationSec, List<DoorEvent> recentEvents, List<DoorHour> hourlyRows,
                            List<DurationBucket> durationBuckets, String selectedDoorEquipmentId,
                            List<Contact> contacts, ContactSummary doors, ContactSummary windows) { }
    public record Contact(String equipmentId, String name, ContactKind kind, Boolean open, Boolean lastObservedOpen,
                          Instant lastObservedAt) { }
    public record ContactSummary(int open, int closed, int unknown) {
        public Boolean singleOpen() {
            if (open + closed + unknown != 1 || unknown != 0) return null;
            return open == 1;
        }
    }
    public record DoorEvent(Instant observedAt, boolean open, Double durationSec) { }
    public record DoorHour(Instant startsAt, Integer openCount, Double durationSec, Boolean hasLongOpen) { }
    public record DurationBucket(String bucket, Integer count, Double durationSec) { }
}
