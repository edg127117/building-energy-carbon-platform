package com.platform.alarm;

import java.time.YearMonth;

/**
 * B 版统筹接线点。实现方复用年度计划和正式核算查询，返回同建筑同月的有效快照；
 * 不接受浏览器传入实际值，也不在报警模块重算碳排放或月配额。
 */
public interface MonthlyQuotaSource {
    AlarmModels.Quota load(String buildingId, YearMonth month);
}
