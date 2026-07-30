package com.platform.iot.temporal.model;

/**
 * TDengine 从迟到原始事件中按测点和分钟聚合出的恢复线索。
 *
 * <p>它只携带重新触发完整分钟重聚合所需的定位信息，不复制原始样本值；
 * 质量修正服务收到线索后仍会回读该分钟全部真实证据。</p>
 */
public record LateRawMinuteEvidence(
        String pointId,
        String buildingId,
        long minuteStart) {
}
