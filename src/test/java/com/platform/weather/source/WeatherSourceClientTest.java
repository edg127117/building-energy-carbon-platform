package com.platform.weather.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.platform.weather.source.WeatherSourceModels.Product;
import com.platform.weather.source.WeatherSourceModels.Request;
import com.platform.weather.source.WeatherSourceModels.Source;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WeatherSourceClientTest {
    private final WeatherSourceClient client = new WeatherSourceClient();

    @Test
    void rejectsUnsupportedProductsBeforeMakingAnyRequest() {
        Request request = new Request(Source.CHINA_WEATHER, Product.HISTORY_DAILY,
                32.0, 118.0, "101190101", null, null);
        WeatherSourceException exception = assertThrows(WeatherSourceException.class, () -> client.fetch(request));
        assertEquals(WeatherSourceException.Code.REQUEST_INVALID, exception.getCode());
    }

    @Test
    void classifiesOnlyRateLimitAndServerHttpResponsesAsRetryable() {
        assertTrue(WeatherSourceClient.isRetryableStatus(429));
        assertTrue(WeatherSourceClient.isRetryableStatus(503));
        assertFalse(WeatherSourceClient.isRetryableStatus(403));
        assertFalse(WeatherSourceClient.isRetryableStatus(404));

        WeatherSourceException forbidden = new WeatherSourceException(
                WeatherSourceException.Code.SOURCE_HTTP_ERROR, 403, false, null);
        assertEquals(403, forbidden.getStatus());
        assertFalse(forbidden.isRetryable());
        assertNull(forbidden.getRetryAfterMillis());

        WeatherSourceException rateLimited = new WeatherSourceException(
                WeatherSourceException.Code.SOURCE_HTTP_ERROR, 429, true, 30_000L);
        assertEquals(429, rateLimited.getStatus());
        assertTrue(rateLimited.isRetryable());
        assertEquals(30_000L, rateLimited.getRetryAfterMillis());

        WeatherSourceException timeout = new WeatherSourceException(WeatherSourceException.Code.SOURCE_TIMEOUT);
        assertTrue(timeout.isRetryable());
    }

    @Test
    void parsesRetryAfterAsSecondsOrHttpDate() {
        Instant now = Instant.parse("2015-10-21T07:26:00Z");

        assertEquals(120_000L, WeatherSourceClient.retryAfterMillis("120", now));
        assertEquals(120_000L, WeatherSourceClient.retryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT", now));
        assertEquals(0L, WeatherSourceClient.retryAfterMillis("Wed, 21 Oct 2015 07:25:00 GMT", now));
        assertNull(WeatherSourceClient.retryAfterMillis("not-a-date", now));
    }
}
