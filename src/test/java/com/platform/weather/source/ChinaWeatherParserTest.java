package com.platform.weather.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

class ChinaWeatherParserTest {
    private static final Instant FETCHED_AT = Instant.parse("2026-09-28T08:30:00Z");
    private final ChinaWeatherParser parser = new ChinaWeatherParser();

    @Test
    void parsesSevenDaysAcrossMonthBoundaryAndKeepsWindText() throws IOException {
        FetchResult result = parser.parse(resource(), request(), FETCHED_AT);

        assertEquals(7, result.samples().size());
        assertEquals(Instant.parse("2026-09-30T16:00:00Z"), result.samples().get(3).time());
        assertEquals("3-4级转<3级", result.samples().get(1).text().get("wind"));
        assertEquals("东北风/东风", result.samples().get(4).text().get("wind_directions"));
        assertNull(result.gridLatitude());
        assertNull(result.sourcePublishedAt());
    }

    @Test
    void missingHighTemperatureStaysNull() throws IOException {
        String body = resource().replaceFirst("<span>24</span>/", "/");
        FetchResult result = parser.parse(body, request(), FETCHED_AT);
        assertNull(result.samples().getFirst().values().get("temperature_2m_max"));
        assertEquals(20.0, result.samples().getFirst().values().get("temperature_2m_min"));
    }

    @Test
    void rejectsAnAmbiguousPageDateAndIncompleteWeek() throws IOException {
        WeatherSourceException dateError = assertThrows(WeatherSourceException.class,
                () -> parser.parse(resource().replace("2026-09-28 12:00:00.0", "2026-09-27 12:00:00.0"),
                        request(), FETCHED_AT));
        assertEquals(WeatherSourceException.Code.DATE_AMBIGUOUS, dateError.getCode());

        String sixDays = resource().replaceFirst("(?s)<li class=\"sky skyid lv2\">.*", "");
        WeatherSourceException structureError = assertThrows(WeatherSourceException.class,
                () -> parser.parse(sixDays, request(), FETCHED_AT));
        assertEquals(WeatherSourceException.Code.STRUCTURE_ERROR, structureError.getCode());
    }

    private static Request request() {
        return new Request(Source.CHINA_WEATHER, Product.FORECAST_DAILY, 32.06167, 118.77778,
                "101190101", null, null);
    }

    private static String resource() throws IOException {
        try (InputStream input = ChinaWeatherParserTest.class.getResourceAsStream("/weather/china-weather-7d.html")) {
            if (input == null) {
                throw new IOException("Missing China Weather fixture");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
