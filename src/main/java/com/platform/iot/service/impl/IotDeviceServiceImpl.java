package com.platform.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.iot.core.model.entity.IotDevice;
import com.platform.iot.core.model.entity.IotDeviceStatusLog;
import com.platform.iot.mapper.IotDeviceMapper;
import com.platform.iot.mapper.IotDeviceStatusLogMapper;
import com.platform.iot.service.IotDeviceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;


/**
 设备业务实现类
 **/
@Service
public class IotDeviceServiceImpl extends ServiceImpl<IotDeviceMapper, IotDevice> implements IotDeviceService {

    @Autowired
    private IotDeviceStatusLogMapper statusLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)//开启事务，确保更新和插入同时成功或失败
    public void updateStatusByDeviceId(String deviceId, Integer status) {
        // 相当于：UPDATE iot_device SET status = ? WHERE device_id = ?
        // 更新设备主表的当前状态（UPDATE）
        LambdaUpdateWrapper<IotDevice> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(IotDevice::getDeviceId, deviceId)
                .set(IotDevice::getStatus, status);
        this.update(updateWrapper);

        // 追加一条历史轨迹日志 (INSERT)
        // 由于外层的 DeviceMessageConsumer 有防暴击缓存拦截，
        // 所以这个方法只有在状态真正发生“翻转”时才会被调用，不会产生垃圾日志！
        IotDeviceStatusLog log = new IotDeviceStatusLog();
        log.setDeviceId(deviceId);
        log.setStatus(status);
        log.setCreateTime(new Date());

        statusLogMapper.insert(log);
    }
}
