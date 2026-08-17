package com.platform.iot.identity;

import java.util.Optional;

/** 从本地设备预注册快照中解析可信建筑与设备归属。 */
public interface DeviceIdentityProvider {

    Optional<DeviceIdentityBinding> find(DeviceIdentityKey key);

    /** 返回身份是否已在业务库登记；停用身份仍属于已知身份，不能重新进入待绑定发现。 */
    boolean isKnown(DeviceIdentityKey key);
}
