package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizPointAlias;
import org.apache.ibatis.annotations.Mapper;

/**
 * 外部测点别名的 MySQL 持久化入口。
 *
 * <p>测点配置提供者从这里加载启用的“来源系统 + 来源编码”映射，供 MQTT 接入链
 * 将设备报文中的地址解析为平台标准测点。Mapper 不解析 MQTT 报文、不校验时序值，
 * 也不写入 TDengine。</p>
 */
@Mapper
public interface BizPointAliasMapper extends BaseMapper<BizPointAlias> {
}
