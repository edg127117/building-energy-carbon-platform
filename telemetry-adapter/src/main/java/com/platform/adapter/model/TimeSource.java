package com.platform.adapter.model;

/** 标准事件时间来自设备，或在设备未提供时间时退化为云端接收时间。 */
public enum TimeSource {
    DEVICE_REPORTED,
    ADAPTER_RECEIVED
}
