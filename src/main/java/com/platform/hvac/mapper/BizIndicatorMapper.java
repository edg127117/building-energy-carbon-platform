package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizIndicator;
import org.apache.ibatis.annotations.Mapper;

/**
 * HVAC 指标实例配置的 MySQL 持久化入口。
 *
 * <p>指标配置提供者从这里加载启用的建筑、设备及系统作用域，公式计算和查询服务
 * 再据此确定应计算或展示哪些指标。该接口不执行公式、不读取 Redis 最新值，
 * 也不访问 TDengine 指标结果。</p>
 */
@Mapper
public interface BizIndicatorMapper extends BaseMapper<BizIndicator> {
}
