package com.platform.hvac.asset.api;

import java.time.LocalDate;
import java.util.List;

/** 单块电表的历史用电分析；日量和比较值均由服务端计算。 */
public final class MeterElectricityContracts {
    private MeterElectricityContracts() { }

    public record Day(LocalDate date, Double kwh, String status, String reason,
                      Long startSampleTime, Long endSampleTime,
                      Double changeKwh, Double changePercent) { }

    /** 仅累计可计算的自然日；availableDays 小于 requestedDays 时不是完整区间总量。 */
    public record PeriodSummary(Double measuredKwh, int availableDays, int requestedDays) { }

    public record View(String equipmentId, String equipmentCode, String equipmentName,
                       String buildingId, String pointCode, String unit, String timeZone,
                       int boundaryWindowMinutes, MeterCoverageContracts.View currentCoverage,
                       List<Day> days, PeriodSummary periodSummary) { }
}
