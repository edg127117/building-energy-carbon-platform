package com.platform.iot.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.iot.core.model.entity.IotDevice;


/**
 * 设备业务接口
 */
public interface IotDeviceService extends IService<IotDevice> {
    /**
     * 根据设备物理编号更新在线状态
     */
    void updateStatusByDeviceId(String deviceId, Integer status);
}
