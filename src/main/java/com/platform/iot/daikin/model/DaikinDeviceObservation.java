package com.platform.iot.daikin.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 尚未绑定的平台输入：observedAt 仅代表平台成功解析该页的时间，不是设备测量时间。
 * resTime 是厂家响应时间原文，不用于新鲜度判断。白名单字段不包含凭据或整份原报文。
 */
public record DaikinDeviceObservation(DaikinDeviceKey key, String equipmentId,
                                      String siteName, String deviceName,
                                      Instant observedAt, String responseTime,
                                      Map<String, DaikinFieldValue> fields) {
    public DaikinDeviceObservation {
        Objects.requireNonNull(key);
        Objects.requireNonNull(observedAt);
        fields = Map.copyOf(fields);
    }
}
