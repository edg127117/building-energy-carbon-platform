package com.platform.energy.activity;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 多能源活动数据读取接口的稳定 DTO，不暴露 TDengine 表结构和内部策略索引。 */
public final class EnergyActivityDataContracts {
    private EnergyActivityDataContracts() {
    }

    @Schema(description = "活动数据 seek 游标")
    public record ActivityCursor(long eventTime, String pointId) {
    }

    @Schema(description = "通过能源专业属性和质量策略门禁的原始活动数据")
    public record RawActivityDataView(
            String pointId,
            String pointCode,
            String unit,
            String energyType,
            String energySubtype,
            String valueSemantics,
            String confirmationStatus,
            int profileRevision,
            String sourceSystem,
            String sourcePointCode,
            String sourceDeviceId,
            double rawValue,
            long eventTime,
            long receivedTime,
            String qualityLevel,
            boolean late,
            String policySource,
            Integer policyVersion,
            long policyConfigRevision) {
    }

    /**
     * 原始活动数据页同时声明事实身份和证据限制。
     *
     * <p>{@code blockedCount} 只统计本页被质量门禁拒绝的行；被拒绝行不进入 items。
     * 当前原始事实以“测点 + 事件时间”合并保存，因此不能声称保留重复次数或修正历史。</p>
     */
    @Schema(description = "有界、可追溯的多能源原始活动数据页")
    public record RawActivityDataPage(
            String buildingId,
            String factKind,
            String factSchemaVersion,
            String identityRule,
            String duplicateHandling,
            String correctionEvidence,
            String scenarioCode,
            long fromInclusive,
            long toExclusive,
            int requestedPointCount,
            int scannedCount,
            int blockedCount,
            boolean truncated,
            ActivityCursor nextCursor,
            List<RawActivityDataView> items) {
        public RawActivityDataPage {
            items = List.copyOf(items);
        }
    }

    @Schema(description = "活动数据读取错误响应")
    public record EnergyActivityApiError(
            int code,
            String errorCode,
            String msg,
            boolean success,
            String traceId) {
    }
}
