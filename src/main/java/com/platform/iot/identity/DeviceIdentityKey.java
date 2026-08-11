package com.platform.iot.identity;

import java.util.Locale;

/**
 * 外部设备身份的规范化查找键。
 *
 * <p>身份类型不区分大小写，身份值只去除首尾空白，不改变 MAC、序列号等设备原值。</p>
 */
public record DeviceIdentityKey(String type, String value) {

    public DeviceIdentityKey {
        if (type == null || type.isBlank() || value == null || value.isBlank()) {
            throw new IllegalArgumentException("设备身份类型和值不能为空");
        }
        type = type.trim().toUpperCase(Locale.ROOT);
        value = value.trim();
    }
}
