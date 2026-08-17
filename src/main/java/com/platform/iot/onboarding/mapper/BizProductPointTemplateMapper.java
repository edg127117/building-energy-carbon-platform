package com.platform.iot.onboarding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.onboarding.model.entity.BizProductPointTemplate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** 产品测点默认模板的 MySQL 持久化入口。 */
public interface BizProductPointTemplateMapper extends BaseMapper<BizProductPointTemplate> {
}
