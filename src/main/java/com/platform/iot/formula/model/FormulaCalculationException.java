package com.platform.iot.formula.model;

import java.util.List;

/**
 * 写入 TDengine 的公式失败审计行。
 *
 * @param indicatorId MySQL 指标实例 ID
 * @param indicatorCode 稳定指标编码
 * @param buildingId 指标所属建筑
 * @param systemGroupId 所属系统组，可为空
 * @param equipId 所属设备，可为空
 * @param minuteStart 失败所对应的来源自然分钟起点
 * @param status 失败类型，不允许为 SUCCESS
 * @param reasonCode 稳定的机器可读失败原因
 * @param missingInputs 缺失的标准语义键
 * @param formulaVersion 本次尝试使用的公式版本
 * @param calculatedAt 实际执行时间，用于区分同一分钟的多次尝试
 */
public record FormulaCalculationException(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long minuteStart,
        FormulaCalculation.Status status,
        String reasonCode,
        List<String> missingInputs,
        String formulaVersion,
        long calculatedAt) {

    public FormulaCalculationException {
        missingInputs = List.copyOf(missingInputs);
    }
}
