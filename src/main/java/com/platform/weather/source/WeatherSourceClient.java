package com.platform.weather.source;

import com.platform.weather.source.WeatherSourceModels.FetchResult;
import com.platform.weather.source.WeatherSourceModels.Product;
import com.platform.weather.source.WeatherSourceModels.Request;
import com.platform.weather.source.WeatherSourceModels.Source;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** 对固定天气来源执行有超时和响应大小限制的读取；返回解析结果，持久化和重试由任务层负责。 */
@Component
public class WeatherSourceClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final List<String> HOURLY_FIELDS = List.of("temperature_2m", "relative_humidity_2m",
            "precipitation", "weather_code", "wind_speed_10m", "wind_direction_10m", "shortwave_radiation");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 禁止跟随跳转，避免固定来源地址将请求转发到未批准的主机。
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final OpenMeteoParser openMeteoParser = new OpenMeteoParser();
    private final ChinaWeatherParser chinaWeatherParser = new ChinaWeatherParser();
    private final Clock clock;
    private final HttpClient http;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WeatherSourceClient.class);

    public WeatherSourceClient() {
        this(Clock.systemUTC());
    }

    WeatherSourceClient(Clock clock) {
        this(clock, HTTP);
    }

    WeatherSourceClient(Clock clock, HttpClient http) {
        this.clock = clock;
        this.http = http;
    }

    public FetchResult fetch(Request request) {
        validate(request);
        Instant fetchedAt = clock.instant();
        URI uri = request.source() == Source.OPEN_METEO ? openMeteoUri(request) : chinaWeatherUri(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", request.source() == Source.OPEN_METEO ? "application/json" : "text/html")
                .GET().build();
        long started = System.nanoTime();
        try {
            HttpResponse<byte[]> response = http.send(httpRequest, info -> new BoundedBodySubscriber());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                int status = response.statusCode();
                Long retryAfterMillis = response.headers().firstValue("Retry-After")
                        .map(value -> retryAfterMillis(value, clock.instant())).orElse(null);
                throw new WeatherSourceException(WeatherSourceException.Code.SOURCE_HTTP_ERROR,
                        status, isRetryableStatus(status), retryAfterMillis);
            }
            String body = new String(response.body(), StandardCharsets.UTF_8);
            return request.source() == Source.OPEN_METEO
                    ? openMeteoParser.parse(body, request, fetchedAt)
                    : chinaWeatherParser.parse(body, request, fetchedAt);
        } catch (WeatherSourceException exception) {
            logFailure(request, uri, started, exception.getCode(), exception, exception.getStatus());
            throw exception;
        } catch (java.net.http.HttpTimeoutException | java.net.SocketTimeoutException exception) {
            logFailure(request, uri, started, WeatherSourceException.Code.SOURCE_TIMEOUT, exception, null);
            throw new WeatherSourceException(WeatherSourceException.Code.SOURCE_TIMEOUT, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logFailure(request, uri, started, WeatherSourceException.Code.SOURCE_INTERRUPTED, exception, null);
            throw new WeatherSourceException(WeatherSourceException.Code.SOURCE_INTERRUPTED, exception);
        } catch (IOException exception) {
            if (hasCause(exception, ResponseTooLargeException.class)) {
                logFailure(request, uri, started, WeatherSourceException.Code.SOURCE_TOO_LARGE, exception, null);
                throw new WeatherSourceException(WeatherSourceException.Code.SOURCE_TOO_LARGE);
            }
            logFailure(request, uri, started, WeatherSourceException.Code.SOURCE_HTTP_ERROR, exception, null);
            throw new WeatherSourceException(WeatherSourceException.Code.SOURCE_HTTP_ERROR);
        }
    }

    /** 只输出固定主机及异常类型，不输出异常消息、完整 URI 或响应正文。 */
    private void logFailure(Request request, URI uri, long started, WeatherSourceException.Code code,
                            Exception error, Integer status) {
        log.warn("Weather source request failed source={} product={} host={} elapsedMs={} code={} type={} status={}",
                request.source(), request.product(), uri.getHost(),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),
                code, error.getClass().getSimpleName(), status);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int totalBytes;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int count = buffer.remaining();
                if ((long) totalBytes + count > MAX_RESPONSE_BYTES) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[count];
                buffer.get(chunk);
                output.write(chunk, 0, chunk.length);
                totalBytes += count;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }

    private static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static URI openMeteoUri(Request request) {
        String base = request.product() == Product.HISTORY_DAILY || request.product() == Product.HISTORY_HOURLY
                ? "https://archive-api.open-meteo.com/v1/archive"
                : "https://api.open-meteo.com/v1/forecast";
        String common = "latitude=" + request.latitude() + "&longitude=" + request.longitude()
                + "&timezone=Asia%2FShanghai&wind_speed_unit=ms&temperature_unit=celsius&precipitation_unit=mm";
        String fields = switch (request.product()) {
            case CURRENT -> "current=temperature_2m%2Crelative_humidity_2m%2Cweather_code%2Cwind_speed_10m%2Cwind_direction_10m";
            case FORECAST_HOURLY -> "hourly=" + encoded(String.join(",", HOURLY_FIELDS)) + "&forecast_days=7";
            case FORECAST_DAILY -> "daily=temperature_2m_max%2Ctemperature_2m_min%2Cweather_code%2Cwind_speed_10m_max%2Cwind_direction_10m_dominant&forecast_days=7";
            case HISTORY_HOURLY -> "hourly=" + encoded(String.join(",", HOURLY_FIELDS)) + historyRange(request);
            case HISTORY_DAILY -> "daily=temperature_2m_max%2Ctemperature_2m_min%2Cweather_code" + historyRange(request);
        };
        return URI.create(base + "?" + common + "&" + fields);
    }

    private static String historyRange(Request request) {
        return "&start_date=" + request.start() + "&end_date=" + request.end();
    }

    private static URI chinaWeatherUri(Request request) {
        return URI.create("https://www.weather.com.cn/weather/" + request.cityCode() + ".shtml");
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500 && status <= 599;
    }

    static Long retryAfterMillis(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d+")) {
            try {
                return Math.multiplyExact(Long.parseLong(trimmed), 1_000L);
            } catch (NumberFormatException | ArithmeticException exception) {
                return Long.MAX_VALUE;
            }
        }
        try {
            long millis = Duration.between(now,
                    ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
            return Math.max(0L, millis);
        } catch (DateTimeParseException | ArithmeticException exception) {
            return null;
        }
    }

    private static void validate(Request request) {
        if (request == null || request.source() == null || request.product() == null) {
            throw invalidRequest();
        }
        if (request.source() == Source.CHINA_WEATHER) {
            if (request.product() != Product.FORECAST_DAILY || request.cityCode() == null
                    || !request.cityCode().matches("\\d{6,12}")) {
                throw invalidRequest();
            }
            return;
        }
        if (!Double.isFinite(request.latitude()) || request.latitude() < -90 || request.latitude() > 90
                || !Double.isFinite(request.longitude()) || request.longitude() < -180 || request.longitude() > 180) {
            throw invalidRequest();
        }
        boolean history = request.product() == Product.HISTORY_DAILY || request.product() == Product.HISTORY_HOURLY;
        if (history && (request.start() == null || request.end() == null || request.start().isAfter(request.end()))) {
            throw invalidRequest();
        }
    }

    private static WeatherSourceException invalidRequest() {
        return new WeatherSourceException(WeatherSourceException.Code.REQUEST_INVALID);
    }
}
