package com.platform.weather;

import com.platform.weather.source.WeatherSourceModels.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

/** 天气 HTTP 契约与持久化任务模型；不接受调用方提交的天气数值。 */
public final class WeatherModels {
    private WeatherModels() {}
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalDate HISTORY_START = LocalDate.of(2024, 1, 1);

    public record BindingRequest(@NotBlank @Size(max=100) String name,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Pattern(regexp="[0-9]{9}") String cityCode,
            @NotBlank @Pattern(regexp="DISTRICT|CITY") String coverage,
            @NotNull Instant effectiveFrom, @Min(0) long expectedVersion,
            boolean enabled) {}
    public record Binding(String id, String buildingId, long version, String name,
            double latitude, double longitude, String cityCode, String coverage,
            Instant effectiveFrom, Instant effectiveTo, boolean enabled) {}
    public record FetchRequest(@NotBlank String buildingId, @NotNull Source source,
            @NotNull Product product, @NotNull LocalDate start, @NotNull LocalDate end) {}
    public record EnergyRequest(@NotBlank String systemId, @NotBlank String pointId,
            @NotNull LocalDate start, @NotNull LocalDate end) {}
    public record Job(String id, String kind, String buildingId, String bindingId,
            Source source, Product product, LocalDate start, LocalDate end,
            String systemId, String pointId, long actorId, String state,
            int attempts, long fence, long leaseUntil, long nextAttempt,
            String datasetId, String reason, long createdAt) {}
    public record JobView(String id, String kind, String buildingId, Source source,
            Product product, LocalDate start, LocalDate end, String state,
            int attempts, String reason, Instant createdAt) {
        public static JobView of(Job j) { return new JobView(j.id(),j.kind(),j.buildingId(),j.source(),
                j.product(),j.start(),j.end(),j.state(),j.attempts(),j.reason(),Instant.ofEpochMilli(j.createdAt())); }
    }
    public record Dataset(String id, String bindingId, Source source, Product product,
            Instant fetchedAt, String state, String tableName, FetchResult result) {}
    public record WeatherSeries(Binding location, Source source, Product product, String status,
            String updateStatus, Instant fetchedAt, String datasetId, Provenance provenance, List<Sample> samples) {}
    public record Provenance(String dataKind,Instant sourcePublishedAt,Double gridLatitude,Double gridLongitude,
            String parserVersion,String timezone,Map<String,String> units) {
        static Provenance of(Dataset d) {
            if(d==null)return null;
            String kind=d.source()==Source.CHINA_WEATHER?"PUBLISHED_FORECAST":switch(d.product()) {
                case HISTORY_HOURLY,HISTORY_DAILY -> "MODEL_HISTORY_BEST_MATCH";
                case CURRENT -> "MODEL_CURRENT";
                default -> "MODEL_FORECAST";
            };
            return new Provenance(kind,d.result().sourcePublishedAt(),d.result().gridLatitude(),d.result().gridLongitude(),d.result().parserVersion(),ZONE.getId(),
                    Map.of("temperature_2m","°C","temperature_2m_max","°C","temperature_2m_min","°C","relative_humidity_2m","%","precipitation","mm","shortwave_radiation","W/m²","wind_speed_10m","m/s","wind_direction_10m","°"));
        }
    }
    public record DaySlot(LocalDate date, Sample sample) {}
    public record Forecast(Source preferredSource, Source selectedSource, String fallbackReason,
            String status, String updateStatus, Instant fetchedAt, String datasetId, String coverage, Provenance provenance, List<DaySlot> days) {}
    public record Overview(Binding location, WeatherSeries current, Forecast forecast) {}
    public record ComparisonDay(LocalDate date, com.platform.weather.energy.DailyElectricityAdapter.DailyResult energy, Double weatherValue,
            String weatherUnit, int validHours, String weatherStatus, String datasetId) {
        @com.fasterxml.jackson.annotation.JsonProperty("energyStatus")
        public String energyStatus() { return energy==null?"MISSING":energy.status(); }
        @com.fasterxml.jackson.annotation.JsonProperty("energyKwh")
        public java.math.BigDecimal energyKwh() { return energy==null?null:energy.energyKwh(); }
    }
    public record Comparison(String buildingId, String systemId, String pointId, String metric,
            List<ComparisonDay> days) {}
}
