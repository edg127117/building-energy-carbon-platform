package com.platform.iot.ingest;

/**
 * HVAC MQTT 报文完成业务处理后的稳定结果编码。
 *
 * <p>{@link #ACCEPTED} 是新事件落盘，{@link #DUPLICATE} 是同一来源事实已存在，
 * {@link #CONFLICT_UPDATED} 是同测点同采集时间已由最后上报覆盖，{@link #REJECTED}
 * 表示载荷或测点规则不通过；这四类均可确认。只有 {@link #STORAGE_FAILED} 表示
 * TDengine 尚无可确认事实，需要保留 Broker 重投。</p>
 */
public enum IngestionOutcome {
    ACCEPTED,
    DUPLICATE,
    CONFLICT_UPDATED,
    REJECTED,
    STORAGE_FAILED
}
