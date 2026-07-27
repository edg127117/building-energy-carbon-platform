package com.platform.iot.formula.model;

/**
 * 写入 TDengine 成功指标超级表的分钟结果。
 *
 * @param indicatorId MySQL 指标实例 ID
 * @param indicatorCode 稳定指标编码
 * @param buildingId 指标所属建筑
 * @param systemGroupId 所属系统组，可为空
 * @param equipId 所属设备，可为空
 * @param minuteStart 来源自然分钟起点，也是 TDengine 主时间戳
 * @param value 指标计算值
 * @param dataQuality 实际参与输入的最差质量等级
 * @param formulaVersion 生成该结果的公式版本
 * @param calculatedAt 实际计算时间
 */
public record IndicatorMinuteResult(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long minuteStart,
        double value,
        int dataQuality,
        String formulaVersion,
        long calculatedAt) {}
