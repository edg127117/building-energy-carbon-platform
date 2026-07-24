package com.platform.iot.temporal.model;

/**
 * HVAC 冻结分钟查询结果。
 *
 * @param pointId 测点内部 ID
 * @param time 分钟或降采样窗口起始时间（Unix 毫秒）
 * @param average 平均值
 * @param minimum 最小值
 * @param maximum 最大值
 * @param sampleCount 原始采样总数
 * @param dataQuality 数据质量等级，数值越大质量越差
 */
public record HvacMinuteQueryRow(
        String pointId,
        long time,
        double average,
        double minimum,
        double maximum,
        long sampleCount,
        int dataQuality) {
}
