package com.platform.iot.roomsensor;

import com.platform.iot.roomsensor.RoomSensorHistoryStore.DoorReading;
import com.platform.iot.roomsensor.RoomSensorRoomQueryService.DoorEvent;
import com.platform.iot.roomsensor.RoomSensorRoomQueryService.DoorHour;
import com.platform.iot.roomsensor.RoomSensorRoomQueryService.DurationBucket;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static com.platform.iot.roomsensor.RoomSensorRoomQueryService.HOUR;
import static com.platform.iot.roomsensor.RoomSensorRoomQueryService.instant;

/** 根据 CA03 的状态变化重建开门事件和对齐北京时间的当日统计。 */
final class RoomSensorDoorActivity {
    private static final List<String> BUCKETS = List.of("SHORT_LE_15S", "MEDIUM_15_60S", "LONG_GT_60S");
    private static final int RECENT_LIMIT = 5;
    private static final long SHORT_MILLIS = 15_000;
    private static final long LONG_MILLIS = 60_000;

    private final long dayStart;
    private final Hour[] hours = new Hour[24];
    private final int[] bucketCounts = new int[BUCKETS.size()];
    private final long[] bucketMillis = new long[BUCKETS.size()];

    private RoomSensorDoorActivity(long dayStart) {
        this.dayStart = dayStart;
        for (int i = 0; i < hours.length; i++) hours[i] = new Hour();
    }

    static Summary summarize(List<DoorReading> rows, long day, long asOf, Boolean currentOpen, int freshnessSeconds) {
        var activity = new RoomSensorDoorActivity(day);
        var events = new ArrayDeque<DoorEvent>();
        DoorReading previous = null;
        Long transitionAt = null;
        for (var reading : rows) {
            long at = reading.receivedAt();
            // 午夜前还有效的状态只为当天提供覆盖，不能把午夜凭空当作一次开关。
            if (at < day && at + freshnessSeconds * 1000L > day) activity.hours[0].observed = true;
            if (at >= day) activity.hours[activity.hour(at)].observed = true;
            if (previous != null && previous.open() != reading.open()) {
                Double duration = null;
                if (reading.open()) {
                    if (at >= day) activity.hours[activity.hour(at)].openCount++;
                } else if (transitionAt != null) {
                    duration = seconds(at - transitionAt);
                    activity.openInterval(transitionAt, at, true);
                }
                events.addFirst(new DoorEvent(instant(at), reading.open(), duration));
                if (events.size() > RECENT_LIMIT) events.removeLast();
                transitionAt = at;
            }
            previous = reading;
        }

        if (transitionAt != null && previous.open()) {
            // 开着且状态仍有效时，累计到查询时刻。超期后仅累计到最后一次开门观测。
            activity.openInterval(transitionAt, Boolean.TRUE.equals(currentOpen) ? asOf : previous.receivedAt(), false);
        }
        var hourly = activity.hourlyRows();
        boolean observedToday = hourly.stream().anyMatch(hour -> hour.openCount() != null);
        return new Summary(transitionAt == null ? null : instant(transitionAt),
                currentOpen == null || transitionAt == null ? null : seconds(asOf - transitionAt),
                List.copyOf(events), hourly, activity.buckets(observedToday));
    }

    private void openInterval(long start, long end, boolean closed) {
        long duration = end - start;
        if (closed && start >= dayStart) {
            int bucket = duration <= SHORT_MILLIS ? 0 : duration <= LONG_MILLIS ? 1 : 2;
            bucketCounts[bucket]++;
            bucketMillis[bucket] += duration;
        }
        // 次数归到开启时段，持续时间按各小时实际重叠部分切分。
        long from = Math.max(start, dayStart);
        while (from < end) {
            int hour = hour(from);
            long until = Math.min(end, dayStart + (hour + 1L) * HOUR);
            var totals = hours[hour];
            totals.observed = true;
            totals.millis += until - from;
            totals.longOpen |= duration > LONG_MILLIS;
            from = until;
        }
    }

    private int hour(long millis) { return (int) ((millis - dayStart) / HOUR); }

    private List<DoorHour> hourlyRows() {
        var result = new ArrayList<DoorHour>(hours.length);
        for (int i = 0; i < hours.length; i++) {
            var hour = hours[i];
            result.add(new DoorHour(instant(dayStart + i * HOUR), hour.observed ? hour.openCount : null,
                    hour.observed ? seconds(hour.millis) : null, hour.observed ? hour.longOpen || hour.millis > LONG_MILLIS : null));
        }
        return result;
    }

    private List<DurationBucket> buckets(boolean observedToday) {
        var result = new ArrayList<DurationBucket>(BUCKETS.size());
        for (int i = 0; i < BUCKETS.size(); i++) {
            result.add(new DurationBucket(BUCKETS.get(i), observedToday ? bucketCounts[i] : null,
                    observedToday ? seconds(bucketMillis[i]) : null));
        }
        return result;
    }

    private static double seconds(long millis) { return millis / 1000.0; }

    private static final class Hour {
        private boolean observed;
        private int openCount;
        private long millis;
        private boolean longOpen;
    }

    record Summary(Instant lastTransitionAt, Double currentDurationSec, List<DoorEvent> recentEvents,
                   List<DoorHour> hourlyRows, List<DurationBucket> durationBuckets) { }
}
