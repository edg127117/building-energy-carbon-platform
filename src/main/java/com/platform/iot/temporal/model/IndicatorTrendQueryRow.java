package com.platform.iot.temporal.model;

/**
 * TDengine 返回的一个指标趋势分钟或降采样窗口。
 *
 * <p>该模型只服务图表，不携带公式版本；5/30 分钟窗口不代表可以审计的精确公式分钟。</p>
 */
public record IndicatorTrendQueryRow(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long time,
        double average,
        double minimum,
        double maximum,
        long sampleCount,
        int dataQuality) {
}
