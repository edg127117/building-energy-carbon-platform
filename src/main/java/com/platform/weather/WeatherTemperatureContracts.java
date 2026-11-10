package com.platform.weather;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** 仅完整历史温度参与均值；覆盖数和来源独立返回，不将模型历史称作现场实测。 */
public final class WeatherTemperatureContracts {
    private WeatherTemperatureContracts() { }
    @Schema(name="WeatherTemperatureTrendPoint")
    public record Point(LocalDate date, Instant startInclusive, Instant endExclusive,
                        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal value,
                        String status, int validHours, int expectedHours,
                        List<String> reasons, List<String> datasetIds) { }
    @Schema(name="WeatherTemperatureTrendSource")
    public record Source(String datasetId, String bindingId, long locationVersion,
                         String provider, String product, Instant fetchedAt,
                         String contentHash, WeatherModels.Provenance provenance) { }
    @Schema(name="WeatherTemperatureTrend")
    public record View(String buildingId, String periodType, String period, String timezoneId,
                       String unit, String aggregation, LocalDate asOfDate, String status,
                       List<Point> points, List<Source> sources, String resultVersion) { }
}
