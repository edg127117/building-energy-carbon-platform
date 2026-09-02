package com.platform.carbon;

import com.platform.carbon.CarbonModels.ActivitySegment;
import com.platform.carbon.CarbonModels.PeriodType;

import java.time.Instant;
import java.util.List;

/**
 * 碳模块唯一的活动数据入口。
 *
 * <p>实现只能提供第七闭环已封账并完成计量边界汇总的权威活动量；不得返回 tce，
 * 不得让碳计算扫描 TDengine 原始时序或读取能源模块内部表。</p>
 */
public interface CarbonActivityInputPort {
    List<ActivitySegment> read(String buildingId, PeriodType periodType,
                               Instant startInclusive, Instant endExclusive, int limit);
}
