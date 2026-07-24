package com.platform.iot.temporal.model;

/**
 * TDengine HVAC 冻结分钟查询返回的内部行模型。
 *
 * <p>该类型只在 Repository 与 Service 之间传递查询结果，不是数据库实体，
 * 也不是直接暴露给前端的 API DTO。Service 会结合 MySQL 中的测点元数据，
 * 将其转换为快照或历史趋势响应。</p>
 *
 * @param pointId 测点内部 ID
 * @param time 冻结分钟或降采样窗口的起始时间（Unix 毫秒）
 * @param average 当前分钟或窗口的平均值
 * @param minimum 当前分钟或窗口的最小值
 * @param maximum 当前分钟或窗口的最大值
 * @param sampleCount 参与当前分钟或窗口计算的原始采样总数
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
