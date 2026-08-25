package com.platform.iot.deviceparameter;

import java.time.LocalDateTime;
import java.util.List;

/** MySQL 时间线提交后发送给 TDengine 状态投影的跨数据源事件。 */
public record DeviceParameterTimelinePublishedEvent(
        String timelineRevisionId,
        String equipmentId,
        List<String> indicatorIds,
        LocalDateTime from,
        LocalDateTime to) {

    public DeviceParameterTimelinePublishedEvent {
        indicatorIds = List.copyOf(indicatorIds);
    }
}
