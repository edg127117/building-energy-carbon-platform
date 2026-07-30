package com.platform.iot.temporal.model;

/**
 * 跨 TDengine 原始事件表和正式分钟表核验时使用的精确测点分钟键。
 */
public record PointMinuteKey(String pointId, long minuteStart) {
}
