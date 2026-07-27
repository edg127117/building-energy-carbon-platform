package com.platform.iot.formula;

import com.platform.hvac.model.entity.BizIndicator;

import java.util.Collection;
import java.util.Optional;

/**
 * 公式模块读取活动指标配置的边界接口。
 *
 * <p>指标定义属于 MySQL 业务配置，公式引擎只依赖该接口而不直接调用 Mapper，
 * 从而保持计算编排与关系数据库访问边界清晰。</p>
 */
public interface IndicatorConfigProvider {

    /** 返回当前完整且可用的活动指标快照。 */
    Collection<BizIndicator> findAllActive();

    /** 按指标实例 ID 查询当前活动配置。 */
    Optional<BizIndicator> findActive(String indicatorId);
}
