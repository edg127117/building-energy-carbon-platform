package com.platform.alarm;

import com.platform.alarm.AlarmEmissionModels.Metric;
import com.platform.alarm.AlarmEmissionModels.ResolvedPeriod;
import com.platform.alarm.AlarmModels.Rule;

/**
 * 可插拔的排放只读来源。实现只能读取已持久化且已确认的数据/模型，不得复制年度计划或核算业务逻辑。
 * QUOTA_PROGRESS 的 value/reference 是正式排放量与评价期计划配额，进度百分比由评估器计算；
 * YEAR 还须分别提供配额进度与年度预算进度证据，MONTH只要求当前计划批次证据；
 * INTENSITY 的 value/unit/evidence 应对应正式强度、单位及面积版本；COMPLETENESS 只能依据已确认缺点或因子事实。
 * MOM、YOY、BASELINE 以 value 表示当前值、reference 表示对照值并保留有符号差值；BASELINE 必须对应已生效模型快照。
 * YEAR_END_FORECAST 以 reference 提供年度配额，通过 ForecastInput 提供年累计值和模型证据；评估器按闭月已过天数/日历年天数
 * 执行LINEAR_DAY外推，再计算预测值/年度配额百分比。来源、模型或专业口径未确认时须返回 available=false 和原因，不能生成替代指标。
 */
public interface AlarmEmissionSource {
    boolean supports(AlarmModels.EmissionSettings settings);

    AlarmEmissionModels.Metric load(String buildingId, Rule rule, ResolvedPeriod period, long now);
}
