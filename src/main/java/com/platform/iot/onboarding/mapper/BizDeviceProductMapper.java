package com.platform.iot.onboarding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.onboarding.model.entity.BizDeviceProduct;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** 可复用设备产品接入模板的 MySQL 持久化入口。 */
public interface BizDeviceProductMapper extends BaseMapper<BizDeviceProduct> {
}
