package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizSpace;
import org.apache.ibatis.annotations.Mapper;

/**
 * 建筑空间档案的 MySQL 持久化入口。
 *
 * <p>空间服务通过 MyBatis-Plus 基础方法读取楼层、区域和房间并组装空间树，
 * 设备服务也会据此校验设备空间与建筑的一致性。该接口不构建树形结构，
 * 也不执行 Controller 层的建筑范围校验。</p>
 */
@Mapper
public interface BizSpaceMapper extends BaseMapper<BizSpace> {
}
