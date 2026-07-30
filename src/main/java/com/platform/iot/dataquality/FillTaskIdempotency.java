package com.platform.iot.dataquality;

/**
 * 为补全任务生成确定性的业务幂等键。
 *
 * <p>MySQL 通过该键的唯一约束吸收并发重复创建。时间统一使用 Unix 毫秒，
 * 且必须落在对应的分钟或自然小时边界，避免同一业务区间因时间精度不同产生
 * 多个任务。</p>
 */
public final class FillTaskIdempotency {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final int MAX_KEY_LENGTH = 160;

    private FillTaskIdempotency() {
    }

    public static String q1(
            String pointId,
            long leftMinute,
            long rightMinute,
            String algorithmVersion) {
        String normalizedPointId = requireIdentifier(pointId, "pointId");
        String normalizedAlgorithmVersion =
                requireIdentifier(algorithmVersion, "algorithmVersion");
        requireMinuteAligned(leftMinute, "leftMinute");
        requireMinuteAligned(rightMinute, "rightMinute");
        if (rightMinute <= leftMinute) {
            throw new IllegalArgumentException("rightMinute 必须晚于 leftMinute");
        }
        return verifyLength("Q1:" + normalizedPointId + ":" + leftMinute + ":"
                + rightMinute + ":" + normalizedAlgorithmVersion);
    }

    public static String q2(
            String pointId,
            String typicalConfigId,
            int typicalConfigVersion,
            long hourStart) {
        String normalizedPointId = requireIdentifier(pointId, "pointId");
        String normalizedConfigId =
                requireIdentifier(typicalConfigId, "typicalConfigId");
        if (typicalConfigVersion <= 0) {
            throw new IllegalArgumentException("typicalConfigVersion 必须大于 0");
        }
        if (Math.floorMod(hourStart, HOUR_MILLIS) != 0L) {
            throw new IllegalArgumentException("hourStart 必须对齐到自然小时");
        }
        return verifyLength("Q2:" + normalizedPointId + ":" + normalizedConfigId
                + ":" + typicalConfigVersion + ":" + hourStart);
    }

    /**
     * 生成人工重算批次内的质量 1 子任务键。
     *
     * <p>jobId 使人工重算与自动插值使用不同命名空间，同一分块重试仍会复用原子任务，
     * 但不会误命中已经作废的标准 Q1 任务。</p>
     */
    public static String recalculationQ1(
            String jobId,
            String pointId,
            long leftMinute,
            long rightMinute,
            String algorithmVersion) {
        String normalizedJobId = requireIdentifier(jobId, "jobId");
        String normalizedPointId = requireIdentifier(pointId, "pointId");
        String normalizedAlgorithmVersion =
                requireIdentifier(algorithmVersion, "algorithmVersion");
        requireMinuteAligned(leftMinute, "leftMinute");
        requireMinuteAligned(rightMinute, "rightMinute");
        if (rightMinute <= leftMinute) {
            throw new IllegalArgumentException("rightMinute 必须晚于 leftMinute");
        }
        return verifyLength("RECALC_Q1:" + normalizedJobId + ":"
                + normalizedPointId + ":" + leftMinute + ":" + rightMinute
                + ":" + normalizedAlgorithmVersion);
    }

    /**
     * 生成人工重算批次内的质量 2 子任务键。
     *
     * <p>配置版本和自然小时共同冻结典型值来源，jobId 则保证不同人工批次各自保留
     * 独立、可追溯的补全任务。</p>
     */
    public static String recalculationQ2(
            String jobId,
            String pointId,
            String typicalConfigId,
            int typicalConfigVersion,
            long hourStart) {
        String normalizedJobId = requireIdentifier(jobId, "jobId");
        String normalizedPointId = requireIdentifier(pointId, "pointId");
        String normalizedConfigId =
                requireIdentifier(typicalConfigId, "typicalConfigId");
        if (typicalConfigVersion <= 0) {
            throw new IllegalArgumentException("typicalConfigVersion 必须大于 0");
        }
        if (Math.floorMod(hourStart, HOUR_MILLIS) != 0L) {
            throw new IllegalArgumentException("hourStart 必须对齐到自然小时");
        }
        return verifyLength("RECALC_Q2:" + normalizedJobId + ":"
                + normalizedPointId + ":" + normalizedConfigId + ":"
                + typicalConfigVersion + ":" + hourStart);
    }

    public static String regeneration(
            String oldTaskId,
            String pointId,
            long startMinute,
            long endMinute) {
        String normalizedOldTaskId = requireIdentifier(oldTaskId, "oldTaskId");
        String normalizedPointId = requireIdentifier(pointId, "pointId");
        requireMinuteAligned(startMinute, "startMinute");
        requireMinuteAligned(endMinute, "endMinute");
        if (endMinute <= startMinute) {
            throw new IllegalArgumentException("endMinute 必须晚于 startMinute");
        }
        return verifyLength("REGEN:" + normalizedOldTaskId + ":"
                + normalizedPointId + ":" + startMinute + ":" + endMinute);
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static void requireMinuteAligned(long timestamp, String field) {
        if (Math.floorMod(timestamp, MINUTE_MILLIS) != 0L) {
            throw new IllegalArgumentException(field + " 必须对齐到分钟");
        }
    }

    private static String verifyLength(String key) {
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("补全任务幂等键不能超过 160 个字符");
        }
        return key;
    }
}
