package com.platform.iot.dataquality.model;

/**
 * 一个已完整完成的重算分块最终测点分钟分类计数。
 *
 * <p>计数与游标在同一条 MySQL 条件更新中累计，防止分块失败时只推进部分审计结果。</p>
 */
public record RecalculationChunkStats(
        int q0Count,
        int q1Count,
        int q2Count,
        int missingCount) {

    public RecalculationChunkStats {
        if (q0Count < 0 || q1Count < 0
                || q2Count < 0 || missingCount < 0) {
            throw new IllegalArgumentException("重算计数不能为负数");
        }
    }
}
