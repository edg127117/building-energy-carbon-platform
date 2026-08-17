package com.platform.iot.onboarding;

/** 已执行字段、字符串和 UTF-8 总大小限制的待绑定指标样例。 */
public record PendingDeviceSample(String json, boolean truncated) {
}
