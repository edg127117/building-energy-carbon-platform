package com.platform.modbus.model;

/** 记录边缘侧声明的设备身份；平台仍需通过可信绑定决定正式设备归属。 */
public record DeviceIdentity(String type, String value) {
}
