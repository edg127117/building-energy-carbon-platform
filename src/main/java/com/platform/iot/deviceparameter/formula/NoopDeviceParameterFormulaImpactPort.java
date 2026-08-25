package com.platform.iot.deviceparameter.formula;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Component
@ConditionalOnMissingBean(DeviceParameterFormulaImpactPort.class)
/** 没有公式声明参数依赖时的安全空实现；它不会猜测任何公式绑定。 */
public class NoopDeviceParameterFormulaImpactPort implements DeviceParameterFormulaImpactPort {
    @Override
    public List<String> affectedIndicatorIds(
            String equipmentId, Collection<String> changedParameterCodes,
            LocalDateTime from, LocalDateTime to) {
        return List.of();
    }

    @Override
    public void markPending(
            String timelineRevisionId, String equipmentId, Collection<String> indicatorIds,
            LocalDateTime from, LocalDateTime to) {
        // 当前没有已确认参数依赖时没有可标记的公式结果。
    }

    @Override
    public void recalculateMinute(
            String buildingId, long minuteStart, Collection<String> indicatorIds) {
        // 当前没有已确认参数依赖时没有公式需要重算。
    }

    @Override
    public void markFailed(
            String timelineRevisionId, String equipmentId, Collection<String> indicatorIds,
            LocalDateTime from, LocalDateTime to) {
        // 当前没有已确认参数依赖时没有公式结果需要标记失败。
    }
}
