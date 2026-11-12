package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 大屏近似小时监测契约；每条序列有自己的可用状态，空值不代表零。 */
public final class HourlyMonitoringContracts {
    private HourlyMonitoringContracts() { }
    @io.swagger.v3.oas.annotations.media.Schema(name="HourlyMonitoringView")
    public record View(String buildingId, LocalDate date, String timezoneId, Instant asOf, List<Point> points) { }
    @io.swagger.v3.oas.annotations.media.Schema(name="HourlyMonitoringPoint")
    public record Point(int hour, Instant startInclusive, Instant endExclusive,
            Instant actualStart, Instant actualEnd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal electricityKwh,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal staticEmissionTco2e,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal staticFactorKgCo2ePerKwh,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dynamicEmissionTco2,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dynamicFactorKgCo2PerKwh,
            String electricityStatus, String staticStatus, String dynamicStatus, boolean approximate,
            String staticGasCoverage, List<String> sourceNatures, String evidenceId, String reasonCode) { }

    static Point empty(int hour, Instant from, String state, String reason) {
        return new Point(hour,from,from.plusSeconds(3600),null,null,null,null,null,null,null,
                state,state,state,false,null,List.of(),null,reason);
    }
}
