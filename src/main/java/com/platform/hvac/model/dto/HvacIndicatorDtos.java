package com.platform.hvac.model.dto;

import com.platform.iot.formula.model.FormulaCalculation;

import java.util.List;

/**
 * HVAC 公式指标查询接口的稳定响应模型集合。
 *
 * <p>DTO 只表达 API 业务语义，不暴露 TDengine 行结构或 Redis 序列化格式。</p>
 */
public final class HvacIndicatorDtos {

    private HvacIndicatorDtos() {
    }

    /**
     * 一个建筑全部活动指标的最新状态。
     *
     * @param buildingId 已完成权限校验的建筑 ID
     * @param generatedAt 服务端组装响应的时间，Unix 毫秒
     * @param indicators 按指标编码和实例 ID 稳定排序的结果
     */
    public record LatestResponse(
            String buildingId,
            long generatedAt,
            List<LatestIndicator> indicators) {

        public LatestResponse {
            indicators = List.copyOf(indicators);
        }
    }

    /**
     * 单个指标的最新成功、失败或无数据状态。
     *
     * @param indicatorId 指标实例 ID
     * @param indicatorCode 稳定指标编码
     * @param equipId 指标所属设备
     * @param minuteStart 来源分钟；NO_DATA 时为空
     * @param status SUCCESS、MISSING_INPUT、INVALID_INPUT、ENGINE_ERROR 或 NO_DATA
     * @param value 成功值，失败或无数据时为空
     * @param unit 指标展示单位，无量纲时为空
     * @param dataQuality 成功值质量，失败或无数据时为空
     * @param formulaVersion 计算使用的公式版本
     * @param reasonCode 失败原因
     * @param missingInputs 缺失的标准语义键
     */
    public record LatestIndicator(
            String indicatorId,
            String indicatorCode,
            String equipId,
            Long minuteStart,
            String status,
            Double value,
            String unit,
            Integer dataQuality,
            String formulaVersion,
            String reasonCode,
            List<String> missingInputs) {

        public LatestIndicator {
            missingInputs = List.copyOf(missingInputs);
        }
    }

    /**
     * 单个指标在半开区间 {@code [from,to)} 内的成功历史。
     *
     * @param indicatorId 指标实例 ID
     * @param indicatorCode 稳定指标编码
     * @param from 查询起点，包含
     * @param to 查询终点，不包含
     * @param records 按来源分钟升序排列的成功记录
     */
    public record HistoryResponse(
            String indicatorId,
            String indicatorCode,
            long from,
            long to,
            List<HistoryRecord> records) {

        public HistoryResponse {
            records = List.copyOf(records);
        }
    }

    /**
     * 一个成功指标分钟。
     *
     * @param minuteStart 来源自然分钟起点
     * @param value 指标值
     * @param dataQuality 实际参与输入的最差质量
     * @param formulaVersion 生成该值的公式版本
     */
    public record HistoryRecord(
            long minuteStart,
            double value,
            int dataQuality,
            String formulaVersion) {
    }

    /**
     * 指定指标分钟的计算解释或失败审计。
     *
     * @param indicatorId 指标实例 ID
     * @param indicatorCode 稳定指标编码
     * @param equipId 指标所属设备
     * @param minuteStart 请求的来源分钟
     * @param status 计算状态或 NO_DATA
     * @param value 成功值，失败或无数据时为空
     * @param unit 指标展示单位
     * @param dataQuality 成功值质量
     * @param formulaVersion 历史结果实际使用的公式版本
     * @param inputs 参与计算的分钟输入
     * @param steps 成功结果的分步解释
     * @param reasonCode 失败原因
     * @param missingInputs 缺失的标准语义键
     */
    public record CalculationDetail(
            String indicatorId,
            String indicatorCode,
            String equipId,
            long minuteStart,
            String status,
            Double value,
            String unit,
            Integer dataQuality,
            String formulaVersion,
            List<FormulaCalculation.Input> inputs,
            List<FormulaCalculation.Step> steps,
            String reasonCode,
            List<String> missingInputs) {

        public CalculationDetail {
            inputs = List.copyOf(inputs);
            steps = List.copyOf(steps);
            missingInputs = List.copyOf(missingInputs);
        }
    }
}
