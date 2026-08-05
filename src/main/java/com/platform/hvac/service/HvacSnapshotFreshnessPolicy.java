package com.platform.hvac.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
/**
 * 按服务端统一时间和分钟冻结边界判定最新测点记录是否仍可作为实时快照。
 *
 * <p>分钟记录以分钟起点标识。策略先扣除聚合冻结等待，再定位最后一个已经完整结束的
 * 自然分钟；容忍窗口只吸收短时抖动，不能把长期停止更新的历史值继续标记为正常。</p>
 */
public class HvacSnapshotFreshnessPolicy {

    private static final long MINUTE_MILLIS = 60_000L;

    private final long finalizationDelayMillis;
    private final long toleranceMillis;

    public HvacSnapshotFreshnessPolicy(
            @Value("${aggregation.finalization-delay-seconds:30}")
            long finalizationDelaySeconds,
            @Value("${hvac.snapshot.freshness-tolerance-minutes:1}")
            long freshnessToleranceMinutes) {
        if (finalizationDelaySeconds < 0 || freshnessToleranceMinutes < 0) {
            throw new IllegalArgumentException("快照新鲜度时间配置不能为负数");
        }
        this.finalizationDelayMillis =
                Math.multiplyExact(finalizationDelaySeconds, 1_000L);
        this.toleranceMillis =
                Math.multiplyExact(freshnessToleranceMinutes, MINUTE_MILLIS);
    }

    /**
     * 允许记录落后最新应冻结分钟一个配置窗口，避免短时网络抖动被提前标记为过期。
     *
     * @param minuteStart 记录对应的分钟起点
     * @param generatedAt 本次快照使用的统一服务端时间
     * @return {@code NORMAL} 或 {@code STALE}
     */
    public String status(long minuteStart, long generatedAt) {
        long effectiveNow = generatedAt - finalizationDelayMillis;
        long currentEffectiveMinute =
                effectiveNow - Math.floorMod(effectiveNow, MINUTE_MILLIS);
        long latestDueMinute = currentEffectiveMinute - MINUTE_MILLIS;
        long staleBefore = latestDueMinute - toleranceMillis;
        return minuteStart < staleBefore ? "STALE" : "NORMAL";
    }
}
