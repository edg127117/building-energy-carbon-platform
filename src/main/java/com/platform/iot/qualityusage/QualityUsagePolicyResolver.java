package com.platform.iot.qualityusage;

import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyInterval;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;
import com.platform.iot.qualityusage.QualityUsageModels.PolicySource;
import com.platform.iot.qualityusage.QualityUsageModels.QualityLevel;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.RuntimeSnapshot;
import com.platform.iot.qualityusage.QualityUsageModels.Scenario;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
/** 三个消费入口唯一使用的场景化 Q0/Q1/Q2 解析器。 */
public class QualityUsagePolicyResolver {
    private final QualityUsageRuntimeStateService runtimeState;
    private Timer resolutionTimer;
    private Counter blockedCounter;

    @Autowired
    public QualityUsagePolicyResolver(QualityUsageRuntimeStateService runtimeState) {
        this.runtimeState = runtimeState;
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        resolutionTimer = registry.timer("quality.usage.policy.resolve");
        blockedCounter = registry.counter("quality.usage.policy.blocked");
    }

    public Resolution resolve(
            String pointId, String scenarioCode, long minuteStart, int actualQuality) {
        return resolve(runtimeContext(), pointId, scenarioCode,
                minuteStart, actualQuality);
    }

    /** 为一批实时或公式判断固定同一份不可变策略修订。 */
    public ResolutionContext runtimeContext() {
        return new ResolutionContext(runtimeState.requireSnapshot());
    }

    /** 按请求范围批量加载历史区间，并隐藏底层策略索引。 */
    public ResolutionContext historyContext(
            Set<String> pointIds, String scenarioCode, long from, long to) {
        return new ResolutionContext(runtimeState.loadRange(
                pointIds, scenarioCode, from, to));
    }

    public Resolution resolve(
            ResolutionContext context,
            String pointId,
            String scenarioCode,
            long minuteStart,
            int actualQuality) {
        long started = System.nanoTime();
        Objects.requireNonNull(context, "context");
        RuntimeSnapshot snapshot = context.snapshot();
        QualityLevel quality = QualityLevel.fromCode(actualQuality);
        if (minuteStart != alignMinute(minuteStart)) {
            throw new IllegalArgumentException("minuteStart must align to a full minute");
        }
        Scenario scenario = snapshot.scenarios().get(scenarioCode);
        if (scenario == null || !scenario.enabled()) {
            throw QualityUsageErrors.error(
                    503, QualityUsageErrors.SCENARIO_DISABLED,
                    "质量使用场景暂不可用");
        }

        List<PolicyInterval> intervals = snapshot.policies()
                .getOrDefault(new PolicyKey(pointId, scenarioCode), List.of());
        PolicyInterval matched = null;
        for (PolicyInterval interval : intervals) {
            if (interval.contains(minuteStart)) {
                matched = interval;
            }
        }
        if (matched == null) {
            boolean allowed = quality == QualityLevel.Q0;
            Resolution resolution = new Resolution(
                    allowed ? Decision.ALLOW : Decision.BLOCK,
                    actualQuality,
                    scenarioCode,
                    PolicySource.SYSTEM_DEFAULT_Q0_ONLY,
                    null,
                    snapshot.revision(),
                    allowed ? "NO_ACTIVE_POLICY_DEFAULT" : "QUALITY_NOT_ALLOWED_BY_DEFAULT");
            recordMetrics(resolution, started);
            return resolution;
        }
        boolean allowed = matched.allowedQualities().contains(quality);
        Resolution resolution = new Resolution(
                allowed ? Decision.ALLOW : Decision.BLOCK,
                actualQuality,
                scenarioCode,
                PolicySource.PUBLISHED_POLICY,
                matched.versionNo(),
                snapshot.revision(),
                allowed ? "QUALITY_ALLOWED" : "QUALITY_NOT_ALLOWED");
        recordMetrics(resolution, started);
        return resolution;
    }

    private void recordMetrics(Resolution resolution, long started) {
        if (resolutionTimer != null) {
            resolutionTimer.record(System.nanoTime() - started,
                    java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        if (blockedCounter != null && resolution.decision() == Decision.BLOCK) {
            blockedCounter.increment();
        }
    }

    public static long alignMinute(long timestamp) {
        return timestamp - Math.floorMod(timestamp, 60_000L);
    }
}
