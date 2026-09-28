package com.platform.weather.source;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 来源适配与任务存储之间的数据契约，分别保留请求位置、来源网格、产品和采样时间。 */
public final class WeatherSourceModels {
    private WeatherSourceModels() {
    }

    public enum Source {
        OPEN_METEO,
        CHINA_WEATHER
    }

    public enum Product {
        CURRENT,
        FORECAST_HOURLY,
        FORECAST_DAILY,
        HISTORY_HOURLY,
        HISTORY_DAILY
    }

    public record Request(Source source, Product product, double latitude, double longitude,
                          String cityCode, LocalDate start, LocalDate end) {
    }

    public record Sample(Instant time, Map<String, Double> values, Map<String, String> text) {
        public Sample {
            values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
            text = text == null ? Map.of() : Map.copyOf(text);
        }
    }

    public record FetchResult(Source source, Product product, Instant fetchedAt, Instant sourcePublishedAt,
                              Double gridLatitude, Double gridLongitude, List<Sample> samples,
                              String parserVersion) {
        public FetchResult {
            samples = List.copyOf(samples);
        }
    }
}
