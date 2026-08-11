package com.platform.iot.identity;

/** 本地设备身份配置尚未成功加载，当前报文应保留等待重投。 */
public class DeviceIdentitySnapshotUnavailableException extends RuntimeException {

    public DeviceIdentitySnapshotUnavailableException(String message) {
        super(message);
    }
}
