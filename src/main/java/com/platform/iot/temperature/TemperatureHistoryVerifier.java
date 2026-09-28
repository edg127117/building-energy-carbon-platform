package com.platform.iot.temperature;

import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 只核对检查点确认的那次温度，按历史展示策略验证可用性；建点或收到报文不等于曲线可用。 */
@Service
@RequiredArgsConstructor
public class TemperatureHistoryVerifier {
    private final HvacRawEventRepository history;
    private final QualityUsagePolicyResolver quality;

    public String verify(String building, String equipment, String point, String sourceSystem, long observedAt) {
        try {
            var values = history.findPointHistory(building, equipment, point, observedAt, observedAt + 1, null, 1);
            if (values.isEmpty()) return "HISTORY_UNAVAILABLE";
            var value = values.getFirst();
            if (!building.equals(value.buildingId()) || !equipment.equals(value.equipId())
                    || !point.equals(value.pointId()) || !sourceSystem.equals(value.sourceSystem())
                    || observedAt != value.eventTime()) return "HISTORY_UNAVAILABLE";
            var decision = quality.resolve(point, "POINT_HISTORY_VIEW", observedAt / 60_000 * 60_000, value.dataQuality());
            return decision.decision() == Decision.ALLOW ? "VALID_SAMPLE" : "QUALITY_BLOCKED";
        } catch (RuntimeException unavailable) {
            // 独立存储或策略查询失败只影响数据结果，不能回滚已提交配置或误报采样成功。
            return "HISTORY_UNAVAILABLE";
        }
    }
}
