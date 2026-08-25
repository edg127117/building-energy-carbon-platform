package com.platform.iot.deviceparameter.formula;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** 参数治理向公式引擎查询依赖和触发追溯重算的端口。 */
public interface DeviceParameterFormulaImpactPort {
    List<String> affectedIndicatorIds(
            String equipmentId,
            Collection<String> changedParameterCodes,
            LocalDateTime from,
            LocalDateTime to);

    void markPending(
            String timelineRevisionId,
            String equipmentId,
            Collection<String> indicatorIds,
            LocalDateTime from,
            LocalDateTime to);

    void recalculateMinute(
            String buildingId,
            long minuteStart,
            Collection<String> indicatorIds);

    void markFailed(
            String timelineRevisionId,
            String equipmentId,
            Collection<String> indicatorIds,
            LocalDateTime from,
            LocalDateTime to);
}
