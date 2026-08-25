package com.platform.iot.formula.model;

/**
 * 以 attemptId 关联的不可变公式成功结果修订。
 *
 * <p>同一指标分钟可以有多次成功修订；当前投影可以切换，但旧值、公式版本和参数版本证据
 * 永不因追溯重算被覆盖或删除。</p>
 */
public record FormulaResultRevision(
        String resultRevisionId,
        String attemptId,
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long minuteStart,
        double value,
        int dataQuality,
        String formulaVersion,
        String parameterEvidenceJson,
        long calculatedAt) {
}
