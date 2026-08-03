package com.platform.iot.temporal.model;

/**
 * 以“测点子表 + 设备采集时间”作为唯一键的原始事件写入结果。
 *
 * <p>{@link #DUPLICATE} 表示值和来源身份均未变化；{@link #CONFLICT_UPDATED} 表示
 * 同一唯一键的新上报覆盖旧内容。接入服务据此统计冲突并决定是否重新发布迟到修正
 * 线索，同时三种结果都代表 TDengine 已有可确认的持久化事实。</p>
 */
public enum RawEventWriteResult {
    INSERTED,
    DUPLICATE,
    CONFLICT_UPDATED
}
