package com.platform.iot.daikin.model;

import java.util.Objects;

/**
 * 字段原值使用有界 JSON 字符串，避免状态枚举被数值化或未知被解释为正常。
 * UNCONFIRMED 保留候选值但禁止作为已确认的测量值；缺失字段不得刷新旧值的时间。
 */
public record DaikinFieldValue(Status status, String rawJson, String normalizedValue) {
    public enum Status { PRESENT, MISSING, UNKNOWN, INVALID, UNCONFIRMED }

    public DaikinFieldValue {
        Objects.requireNonNull(status);
        if (rawJson != null && rawJson.length() > 1024) {
            throw new IllegalArgumentException("字段原值超限");
        }
        if (status != Status.PRESENT && normalizedValue != null) {
            throw new IllegalArgumentException("无效或未确认字段不能产生正式值");
        }
    }
}
