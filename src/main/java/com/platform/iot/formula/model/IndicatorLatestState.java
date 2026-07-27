package com.platform.iot.formula.model;

import java.util.List;

/**
 * Redis 和 WebSocket 共用的指标最新状态。
 *
 * <p>成功和失败使用同一模型，失败时 {@code value} 为空，避免前端继续展示
 * 过期成功值；输入和步骤用于最新分钟的快速公式解释。</p>
 *
 * @param indicatorId 指标实例 ID，也是 Redis 键的一部分
 * @param indicatorCode 稳定指标编码
 * @param buildingId 指标所属建筑
 * @param equipId 指标所属设备
 * @param minuteStart 来源自然分钟起点
 * @param status 最新计算状态
 * @param value 成功值，失败时为空
 * @param dataQuality 成功值质量，失败时为空
 * @param formulaVersion 本次使用的公式版本
 * @param reasonCode 失败原因，成功时为空
 * @param missingInputs 缺失的标准语义键
 * @param inputs 实际输入快照
 * @param steps 公式解释步骤
 */
public record IndicatorLatestState(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String equipId,
        long minuteStart,
        FormulaCalculation.Status status,
        Double value,
        Integer dataQuality,
        String formulaVersion,
        String reasonCode,
        List<String> missingInputs,
        List<FormulaCalculation.Input> inputs,
        List<FormulaCalculation.Step> steps) {

    public IndicatorLatestState {
        missingInputs = List.copyOf(missingInputs);
        inputs = List.copyOf(inputs);
        steps = List.copyOf(steps);
    }
}
