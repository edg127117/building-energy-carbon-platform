package com.platform.iot.temporal.model;

/**
 * 设备测点在 TDengine 原始事件表中的最近一条事实。
 * eventTime 保留接入链事件时间语义；原始表没有 timeSource，不能据此声称是设备采样时间。
 */
public record LatestRawReading(
        String pointId,
        double value,
        long eventTime,
        long receivedTime,
        int dataQuality) {
}
