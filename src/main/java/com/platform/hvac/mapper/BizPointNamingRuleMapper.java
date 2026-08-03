package com.platform.hvac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.hvac.model.entity.BizPointNamingRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 标准测点命名规则的 MySQL 持久化入口。
 *
 * <p>测点档案服务在新增或更新测点时读取规则版本、设备族、部件角色和编码模板，
 * 再交给命名校验器验证标准测点身份。该接口只提供规则数据，不解释编码模板，
 * 也不处理 MQTT 上报别名。</p>
 */
@Mapper
public interface BizPointNamingRuleMapper extends BaseMapper<BizPointNamingRule> {
}
