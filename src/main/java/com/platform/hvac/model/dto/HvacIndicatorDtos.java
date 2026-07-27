package com.platform.hvac.model.dto;

import com.platform.iot.formula.model.FormulaCalculation;

import java.util.List;

/** HVAC 公式指标查询接口的稳定响应模型。 */
public final class HvacIndicatorDtos {

    private HvacIndicatorDtos() {
    }

    public record LatestResponse(
            String buildingId,
            long generatedAt,
            List<LatestIndicator> indicators) {

        public LatestResponse {
            indicators = List.copyOf(indicators);
        }
    }

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

    public record HistoryRecord(
            long minuteStart,
            double value,
            int dataQuality,
            String formulaVersion) {
    }

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
