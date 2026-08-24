package com.platform.iot.formula.model;

import java.util.List;

/**
 * 一次纯公式执行的完整结果，不包含数据库指标实例信息。
 *
 * @param status 成功、缺失输入、非法输入或引擎异常
 * @param indicatorCode 公式对应的稳定指标编码
 * @param formulaVersion 用于历史重放的公式版本
 * @param value 成功时的指标值，失败时为 {@code null}
 * @param dataQuality 成功时实际参与输入的最差质量等级，失败时为 {@code null}
 * @param inputs 实际参与或已收到的输入快照
 * @param steps 成功时可向前端解释的计算步骤
 * @param reasonCode 失败时稳定的机器可读原因
 * @param missingInputs 缺失的标准语义键；非缺失类失败为空
 */
public record FormulaCalculation(
        Status status,
        String indicatorCode,
        String formulaVersion,
        Double value,
        Integer dataQuality,
        List<Input> inputs,
        List<Step> steps,
        String reasonCode,
        List<String> missingInputs) {

    /** 公式执行状态；失败状态必须持久化到异常审计表。 */
    public enum Status {
        SUCCESS,
        MISSING_INPUT,
        INVALID_INPUT,
        QUALITY_NOT_ALLOWED,
        POLICY_SNAPSHOT_UNAVAILABLE,
        ENGINE_ERROR
    }

    /**
     * 一个公式输入及其来源身份。
     *
     * @param key 组件/后缀或环境族/后缀组成的标准语义键
     * @param pointId MySQL 测点实例 ID
     * @param pointCode 内部标准测点编码
     * @param value 冻结分钟平均值
     * @param unit 测点单位；当前分钟聚合未携带时允许为空
     * @param dataQuality 数据质量：0 真实、1 插值、2 典型值
     */
    public record Input(
            String key, String pointId, String pointCode,
            double value, String unit, int dataQuality) {}

    /**
     * 前端解释公式时展示的一个计算步骤。
     *
     * @param code 设计公式或输入来源步骤编码
     * @param expression 使用标准语义名称表示的表达式
     * @param value 该步骤计算结果
     * @param unit 结果单位，无量纲时允许为空
     */
    public record Step(
            String code, String expression, double value, String unit) {}

    public FormulaCalculation {
        inputs = List.copyOf(inputs);
        steps = List.copyOf(steps);
        missingInputs = List.copyOf(missingInputs);
    }
}
