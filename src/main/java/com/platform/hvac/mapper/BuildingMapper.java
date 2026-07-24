package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.Building;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuildingMapper extends BaseMapper<Building> {
}
