package com.platform.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.core.model.entity.ControlCommand;
import org.apache.ibatis.annotations.Mapper;

/**
 *指令下方接口
 **/

@Mapper
public interface ControlCommandMapper extends BaseMapper<ControlCommand> {
}