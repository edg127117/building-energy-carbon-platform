package com.platform.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.core.model.entity.IotDeviceStatusLog;
import org.apache.ibatis.annotations.Mapper;
/**
 * 状态轨迹日志 Mapper 接口
 */
@Mapper
public interface IotDeviceStatusLogMapper extends BaseMapper<IotDeviceStatusLog> {
}