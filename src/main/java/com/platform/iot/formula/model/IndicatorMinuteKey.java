package com.platform.iot.formula.model;

/**
 * 唯一标识一个指标实例的来源分钟，用于批量判断补算缺口。
 *
 * @param indicatorId 指标实例 ID
 * @param minuteStart 自然分钟起点，Unix 毫秒
 */
public record IndicatorMinuteKey(String indicatorId, long minuteStart) {}
