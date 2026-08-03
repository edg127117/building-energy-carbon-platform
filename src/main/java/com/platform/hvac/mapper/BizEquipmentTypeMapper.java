package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizEquipmentType;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备类型字典的 MySQL 持久化入口。
 *
 * <p>设备新增服务读取启用类型的资产编码前缀和设备分类，用于生成建筑内业务编码
 * 并固化设备类别。该接口不分配编号，也不维护具体设备实例。</p>
 */
@Mapper
public interface BizEquipmentTypeMapper extends BaseMapper<BizEquipmentType> {
}
