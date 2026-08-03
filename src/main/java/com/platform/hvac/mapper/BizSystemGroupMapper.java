package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizSystemGroup;
import org.apache.ibatis.annotations.Mapper;

/**
 * HVAC 系统分组档案的 MySQL 持久化入口。
 *
 * <p>设备和测点档案服务通过本 Mapper 校验系统分组是否存在且属于同一建筑；
 * 系统分组管理服务则使用 MyBatis-Plus 基础方法完成分页和维护。该接口不读取
 * TDengine，也不负责建筑权限校验或设备关系编排。</p>
 */
@Mapper
public interface BizSystemGroupMapper extends BaseMapper<BizSystemGroup> {
}
