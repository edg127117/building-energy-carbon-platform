package com.platform.adapter.model;

/** 适配器从设备原始报文中提取的不透明外部身份。 */
public record DeviceIdentity(String type, String value) {
}
