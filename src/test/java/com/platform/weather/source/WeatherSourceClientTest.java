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

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class WeatherSourceClientTest {
    private final WeatherSourceClient client = new WeatherSourceClient();

    @Test
    void distinguishesConnectionTimeoutRequestTimeoutAndInterruptionWithoutNetwork(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var http = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        var isolated = new WeatherSourceClient(java.time.Clock.systemUTC(), http);
        var request = new Request(Source.OPEN_METEO, Product.CURRENT,32.0,118.0,null,null,null);
        Exception[] failures = {new java.net.http.HttpConnectTimeoutException("SECRET_CONNECT"),
                new java.net.http.HttpTimeoutException("SECRET_TIMEOUT"), new InterruptedException("SECRET_INTERRUPT")};
        try {
            for (Exception failure:failures) {
                org.mockito.Mockito.doThrow(failure).when(http).send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                        org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<byte[]>>any());
                var error=assertThrows(WeatherSourceException.class, () -> isolated.fetch(request));
                assertEquals(failure instanceof InterruptedException ? WeatherSourceException.Code.SOURCE_INTERRUPTED
                        : WeatherSourceException.Code.SOURCE_TIMEOUT,error.getCode());
                assertEquals(failure,error.getCause());
            }
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        org.assertj.core.api.Assertions.assertThat(output.getAll()).contains("type=HttpConnectTimeoutException",
                "type=HttpTimeoutException", "type=InterruptedException", "elapsedMs=", "host=api.open-meteo.com")
                .doesNotContain("SECRET_CONNECT", "SECRET_TIMEOUT", "SECRET_INTERRUPT");
    }

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
