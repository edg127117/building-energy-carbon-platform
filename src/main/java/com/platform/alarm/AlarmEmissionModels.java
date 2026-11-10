package com.platform.alarm;

import java.math.BigDecimal;
import java.time.YearMonth;

/** 排放只读来源和评估器之间的边界；指标单位及专业核算由来源按已确认口径提供。 */
public final class AlarmEmissionModels {
    private AlarmEmissionModels() { }

    public record ResolvedPeriod(String period, YearMonth fromMonth, YearMonth toMonth) { }

    /** 年末外推只接受来源给出的已确认年累计值；按闭月天数和日历年天数由评估器执行LINEAR_DAY。 */
    public record ForecastInput(BigDecimal yearToDateValue, boolean methodConfirmed, String modelEvidence) { }

    /**
     * 原始正式指标及其来源身份。buildingId/period须与请求一致，observedAt须为不晚于本轮now的有效观测时间；
     * batchId、planVersion、modelVersion按模板携带正式版本依据。同比、环比和基线由value/reference计算有符号差值。
     */
    public record Metric(boolean available, String unavailableReason, BigDecimal value, BigDecimal reference,
            String unit, String evidence, long observedAt, String observationKey, String observationCycleKey,
            String objectName, String quotaProgressEvidence, String annualBudgetProgressEvidence, ForecastInput forecastInput,
            String buildingId, String period, String batchId, String planVersion, String modelVersion,
            String confirmedMissingFactorsEvidence) {
        public Metric(boolean available, String unavailableReason, BigDecimal value, BigDecimal reference,
                String unit, String evidence, long observedAt, String observationKey, String observationCycleKey,
                String objectName) {
            this(available, unavailableReason, value, reference, unit, evidence, observedAt, observationKey,
                    observationCycleKey, objectName, null, null, null, null, null, null, null, null, null);
        }
        public Metric(boolean available, String unavailableReason, BigDecimal value, BigDecimal reference,
                String unit, String evidence, long observedAt, String observationKey, String observationCycleKey,
                String objectName, String quotaProgressEvidence, String annualBudgetProgressEvidence) {
            this(available, unavailableReason, value, reference, unit, evidence, observedAt, observationKey,
                    observationCycleKey, objectName, quotaProgressEvidence, annualBudgetProgressEvidence, null, null, null,
                    null, null, null, null);
        }
    }
}
