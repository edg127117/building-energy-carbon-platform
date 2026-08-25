package com.platform.iot.formula.model;

/**
 * TDengine 追加式公式计算与质量策略判定事实。
 *
 * <p>{@code attemptId} 是业务幂等键；同一指标分钟可以有多次尝试，但任何一次历史
 * 尝试都不会因当前状态变化而被删除或覆盖。</p>
 */
public record FormulaCalculationAttempt(
        String attemptId,
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long minuteStart,
        long attemptedAt,
        String calcStatus,
        String reasonCode,
        String scenarioCode,
        String formulaVersion,
        String policyEvidenceJson,
        String parameterEvidenceJson,
        long configRevision) {

    /** 保留旧调用点的构造契约；没有参数依赖时证据固定为空数组。 */
    public FormulaCalculationAttempt(
            String attemptId,
            String indicatorId,
            String indicatorCode,
            String buildingId,
            String systemGroupId,
            String equipId,
            long minuteStart,
            long attemptedAt,
            String calcStatus,
            String reasonCode,
            String scenarioCode,
            String formulaVersion,
            String policyEvidenceJson,
            long configRevision) {
        this(attemptId, indicatorId, indicatorCode, buildingId, systemGroupId, equipId,
                minuteStart, attemptedAt, calcStatus, reasonCode, scenarioCode, formulaVersion,
                policyEvidenceJson, "[]", configRevision);
    }
}
