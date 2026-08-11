package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizDeviceIdentity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** 设备外部身份预注册关系的 MySQL 持久化入口。 */
public interface BizDeviceIdentityMapper extends BaseMapper<BizDeviceIdentity> {
}
