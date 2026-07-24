package com.platform.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.core.model.entity.IotDevice;
import org.apache.ibatis.annotations.Mapper;


/**
 * 设备 Mapper 接口
 * 继承 BaseMapper 即可获得免费的 insert, delete, update, selectById 等方法
 */
@Mapper
public interface IotDeviceMapper extends BaseMapper<IotDevice> {

}
