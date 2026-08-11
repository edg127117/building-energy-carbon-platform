package com.platform.iot.identity;

import java.util.Optional;

/** 从本地设备预注册快照中解析可信建筑与设备归属。 */
public interface DeviceIdentityProvider {

    Optional<DeviceIdentityBinding> find(DeviceIdentityKey key);
}
