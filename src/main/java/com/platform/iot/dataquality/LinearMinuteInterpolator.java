package com.platform.iot.dataquality;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据两个真实分钟端点生成中间分钟的线性插值结果。
 *
 * <p>该类只负责纯数学计算，不访问数据库、不判断测点状态，也不把生成值写入正式分钟表。
 * 调用方必须另外确认两个端点均为质量 0，并对结果执行量程校验。</p>
 */
@Component
public final class LinearMinuteInterpolator {

    private static final long MINUTE_MILLIS = 60_000L;

    /**
     * 生成左右端点之间的每个完整分钟。
     *
     * <p>最多允许 {@code maxGapMinutes} 个缺失分钟。左右端点相邻或缺口超过上限时
     * 返回空列表；时间未按分钟对齐、端点顺序非法或端点值不是有限数时明确拒绝。</p>
     */
    public List<InterpolatedMinute> interpolate(
            long leftMinute,
            double leftValue,
            long rightMinute,
            double rightValue,
            int maxGapMinutes) {
        requireMinuteAligned(leftMinute, "leftMinute");
        requireMinuteAligned(rightMinute, "rightMinute");
        if (rightMinute <= leftMinute) {
            throw new IllegalArgumentException("rightMinute 必须晚于 leftMinute");
        }
        if (!Double.isFinite(leftValue) || !Double.isFinite(rightValue)) {
            throw new IllegalArgumentException("插值端点值必须是有限数");
        }
        if (maxGapMinutes < 1) {
            throw new IllegalArgumentException("maxGapMinutes 必须大于 0");
        }

        long intervalCount = (rightMinute - leftMinute) / MINUTE_MILLIS;
        long missingCount = intervalCount - 1L;
        if (missingCount < 1L || missingCount > maxGapMinutes) {
            return List.of();
        }

        List<InterpolatedMinute> result =
                new ArrayList<>(Math.toIntExact(missingCount));
        double span = rightMinute - leftMinute;
        for (long index = 1L; index <= missingCount; index++) {
            long targetMinute = leftMinute + index * MINUTE_MILLIS;
            double value = leftValue
                    + (rightValue - leftValue)
                    * (targetMinute - leftMinute)
                    / span;
            result.add(new InterpolatedMinute(targetMinute, value));
        }
        return List.copyOf(result);
    }

    private void requireMinuteAligned(long timestamp, String field) {
        if (Math.floorMod(timestamp, MINUTE_MILLIS) != 0L) {
            throw new IllegalArgumentException(field + " 必须对齐到分钟");
        }
    }

    /**
     * 一个待写入的历史分钟及其线性插值值。
     */
    public record InterpolatedMinute(long minuteStart, double value) {
    }
}
