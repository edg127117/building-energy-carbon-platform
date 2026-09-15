package com.platform.iot.daikin.model;

import java.util.Objects;

/**
 * 厂家身份采用完整来源层级，不把厂家 ID 当作平台设备 ID，也不推断建筑归属。
 * 身份保留大小写与前导零；内机和外机不能因相同 unitId 合并。
 */
public record DaikinDeviceKey(String sourceId, String siteId, String controllerId,
                              Kind kind, String unitId) {
    public enum Kind { INDOOR, OUTDOOR }

    public DaikinDeviceKey {
        sourceId = requireIdentity(sourceId);
        siteId = requireIdentity(siteId);
        controllerId = requireIdentity(controllerId);
        Objects.requireNonNull(kind, "设备类型不能为空");
        unitId = requireIdentity(unitId);
    }

    public static String requireIdentity(String value) {
        if (value == null || value.isBlank() || value.length() > 200
                || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("大金设备身份无效");
        }
        return value;
    }
}
