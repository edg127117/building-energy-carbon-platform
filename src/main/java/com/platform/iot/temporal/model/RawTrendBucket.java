package com.platform.iot.temporal.model;

/**
 * TDengine 原始事件按测点和时间窗口聚合后的内部查询行。
 *
 * @param pointId 平台内部测点 ID
 * @param time 分桶起始时间（Unix 毫秒）
 * @param average 窗口内原始采样平均值
 * @param dataQuality 窗口内最差数据质量等级
 */
public record RawTrendBucket(
        String pointId,
        long time,
        double average,
        int dataQuality) {
}
