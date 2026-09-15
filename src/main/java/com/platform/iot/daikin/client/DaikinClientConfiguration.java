package com.platform.iot.daikin.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 只读客户端的显式启用和资源上限。wire 未确认时，即使配置了目标和凭据也不能发出请求。
 */
public record DaikinClientConfiguration(
        boolean enabled,
        boolean wireConfirmed,
        URI baseUri,
        Credentials credentials,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration concurrencyWait,
        Duration tokenRefreshSkew,
        int maxResponseBytes,
        int maxConcurrentRequests,
        int requestsPerSecond) {

    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_CONCURRENT_REQUESTS = 64;
    private static final int MAX_REQUESTS_PER_SECOND = 1_000;

    public DaikinClientConfiguration {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(credentials, "credentials");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(concurrencyWait, "concurrencyWait");
        Objects.requireNonNull(tokenRefreshSkew, "tokenRefreshSkew");
        if (tokenRefreshSkew.isNegative() || tokenRefreshSkew.compareTo(Duration.ofHours(24)) > 0
                || maxResponseBytes < 1 || maxResponseBytes > MAX_RESPONSE_BYTES
                || maxConcurrentRequests < 1 || maxConcurrentRequests > MAX_CONCURRENT_REQUESTS
                || requestsPerSecond < 1 || requestsPerSecond > MAX_REQUESTS_PER_SECOND) {
            throw new IllegalArgumentException("大金客户端限制参数超出安全范围");
        }
        if (!"https".equalsIgnoreCase(baseUri.getScheme()) || baseUri.getHost() == null
                || baseUri.getRawUserInfo() != null || baseUri.getRawQuery() != null || baseUri.getRawFragment() != null
                || (baseUri.getPath() != null && !baseUri.getPath().matches("/?"))) {
            throw new IllegalArgumentException("大金目标必须是无路径、查询和片段的 HTTPS 地址");
        }
    }

    public static DaikinClientConfiguration disabled(URI baseUri) {
        return new DaikinClientConfiguration(false, false, baseUri, Credentials.empty(),
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofMinutes(1), 1024 * 1024, 4, 4);
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        Duration maximum = "connectTimeout".equals(name) ? Duration.ofSeconds(30)
                : "requestTimeout".equals(name) ? Duration.ofSeconds(60) : Duration.ofSeconds(10);
        if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    @Override
    public String toString() {
        return "DaikinClientConfiguration[enabled=" + enabled + ", wireConfirmed=" + wireConfirmed
                + ", baseUri=" + baseUri + ", credentials=<redacted>, limits=<configured>]";
    }

    public record Credentials(String account, String secret, String salt, String encryptionKey) {
        public Credentials {
            account = requireValue(account, "account");
            secret = requireValue(secret, "secret");
            salt = requireValue(salt, "salt");
            encryptionKey = requireValue(encryptionKey, "encryptionKey");
        }

        static Credentials empty() {
            return new Credentials("disabled", "disabled", "disabled", "disabled00000000");
        }

        @Override
        public String toString() {
            return "Credentials[<redacted>]";
        }

        private static String requireValue(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
