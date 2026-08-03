package com.platform.iot.temporal.model;

/**
 * 单个测点分钟经过质量优先级判断后的写入结果。
 *
 * <p>{@code INSERTED/UPGRADED/UPDATED_REAL/IDEMPOTENT} 表示仓储已有与本次处理一致的
 * 正式分钟，可以进入质量完成事件；两个拒绝结果表示已有数据更可信或同质量任务没有
 * 替代权，调用方不得把被拒绝的内存值交给公式。旧质量和任务 ID 用于补全任务审计。</p>
 *
 * @param pointId 标准测点 ID
 * @param minuteStart 自然分钟起点，Unix 毫秒
 * @param outcome 本次质量优先写入判定
 * @param previousQuality 写入前已有质量；新插入时为空
 * @param previousTaskId 写入前持有该分钟的质量任务 ID；Q0 或新插入时可为空
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
