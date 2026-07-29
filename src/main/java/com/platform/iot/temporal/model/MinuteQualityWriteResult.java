package com.platform.iot.temporal.model;

/**
 * 单个测点分钟经过质量优先级判断后的写入结果。
 *
 * <p>调用方只把成功写入或幂等确认的分钟交给下游公式，拒绝结果用于任务审计，
 * 从而避免低质量补全数据覆盖真实数据。</p>
 */
public record MinuteQualityWriteResult(
        String pointId,
        long minuteStart,
        Outcome outcome,
        Integer previousQuality,
        String previousTaskId) {

    public enum Outcome {
        INSERTED,
        UPGRADED,
        UPDATED_REAL,
        IDEMPOTENT,
        REJECTED_HIGHER_QUALITY,
        REJECTED_SAME_QUALITY
    }
}
