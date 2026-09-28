package com.platform.weather.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.platform.weather.source.WeatherSourceModels.FetchResult;
import com.platform.weather.source.WeatherSourceModels.Product;
import com.platform.weather.source.WeatherSourceModels.Request;
import com.platform.weather.source.WeatherSourceModels.Source;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OpenMeteoParserTest {
    private static final Instant FETCHED_AT = Instant.parse("2026-09-28T08:30:00Z");
    private final OpenMeteoParser parser = new OpenMeteoParser();

    @Test
    void parsesCurrentProductFromSavedSourceSample() throws IOException {
        FetchResult result = parser.parse(resource("open-current.json"), request(Product.CURRENT), FETCHED_AT);

        assertEquals(Source.OPEN_METEO, result.source());
        assertEquals(21.9, result.samples().getFirst().values().get("temperature_2m"));
        assertEquals(32.09139, result.gridLatitude());
        assertEquals(FETCHED_AT, result.fetchedAt());
        assertEquals(null, result.sourcePublishedAt());
    }

    @Test
    void preservesSevenHourlyFieldsAndShanghaiTimeFromSavedSamples() throws IOException {
        FetchResult forecast = parser.parse(resource("open-hourly.json"), request(Product.FORECAST_HOURLY), FETCHED_AT);
        FetchResult history = parser.parse(resource("open-history-hourly.json"), request(Product.HISTORY_HOURLY), FETCHED_AT);

        assertEquals(2, forecast.samples().size());
        assertEquals(Instant.parse("2026-09-25T16:00:00Z"), forecast.samples().getFirst().time());
        assertEquals(7, forecast.samples().getFirst().values().size());
        assertEquals("2026-09-25T15:00:00Z",forecast.samples().getFirst().text().get("precipitation_period_start"));
        assertEquals("SUM",forecast.samples().getFirst().text().get("precipitation_aggregation"));
        assertEquals("MEAN",forecast.samples().getFirst().text().get("shortwave_radiation_aggregation"));
        assertEquals("阴",forecast.samples().getFirst().text().get("weather"));
        assertEquals("未知天气代码",OpenMeteoParser.weatherText(999d));
        assertEquals("强雷暴",OpenMeteoParser.weatherText(97d));
        assertEquals(7, history.samples().getFirst().values().size());
        assertTrue(forecast.samples().getFirst().values().containsKey("shortwave_radiation"));
    }

    @Test
    void keepsDailySourceValuesAndNeverInventsADailyMean() {
        String body = """
                {"latitude":32.1,"longitude":118.8,"timezone":"Asia/Shanghai",
                "daily_units":{"time":"iso8601","temperature_2m_max":"°C","temperature_2m_min":"°C",
                "weather_code":"wmo code","wind_speed_10m_max":"m/s","wind_direction_10m_dominant":"°"},
                "daily":{"time":["2026-09-28"],
                "temperature_2m_max":[25.0],"temperature_2m_min":[18.0],"weather_code":[3],
                "wind_speed_10m_max":[4.5],"wind_direction_10m_dominant":[90]}}
                """;
        FetchResult forecast = parser.parse(body, request(Product.FORECAST_DAILY), FETCHED_AT);
        FetchResult history = parser.parse(body, request(Product.HISTORY_DAILY), FETCHED_AT);

        assertEquals(25.0, forecast.samples().getFirst().values().get("temperature_2m_max"));
        assertEquals(5, forecast.samples().getFirst().values().size());
        assertFalse(forecast.samples().getFirst().values().containsKey("temperature_2m_mean"));
        assertEquals(3, history.samples().getFirst().values().size());
        assertFalse(history.samples().getFirst().values().containsKey("temperature_2m_mean"));
    }

    @Test
    void rejectsMisalignedProviderArraysWithStableErrorCode() {
        String body = "{\"hourly\":{\"time\":[\"2026-09-28T00:00\"],\"temperature_2m\":[]}}";
        WeatherSourceException exception = assertThrows(WeatherSourceException.class,
                () -> parser.parse(body, request(Product.FORECAST_HOURLY), FETCHED_AT));
        assertEquals(WeatherSourceException.Code.STRUCTURE_ERROR, exception.getCode());
        assertEquals("STRUCTURE_ERROR", exception.getMessage());
    }

    private static Request request(Product product) {
        return new Request(Source.OPEN_METEO, product, 32.06167, 118.77778, null,
                LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 27));
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = OpenMeteoParserTest.class.getResourceAsStream("/weather/" + name)) {
            if (input == null) {
                throw new IOException("Missing fixture: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
