package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 仅允许协议白名单中的认证和读取调用。实例不注册调度器，也不会在构造或 Spring 启动时发出请求。
 */
public final class DaikinReadonlyClient {
    private final DaikinClientConfiguration configuration;
    private final DaikinWireCodec wireCodec;
    private final DaikinHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Semaphore concurrency;
    private final Object rateLock = new Object();
    private final ReentrantLock tokenLock = new ReentrantLock();
    private long nextRequestNanos;
    private boolean rateInitialized;
    private volatile DaikinWireCodec.Token token;

    public DaikinReadonlyClient(DaikinClientConfiguration configuration, DaikinWireCodec wireCodec,
                                DaikinHttpTransport transport, ObjectMapper objectMapper, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.wireCodec = wireCodec;
        this.transport = Objects.requireNonNull(transport, "transport");
        // 外部响应中重复键或尾随第二份 JSON 不能被宽松读取为单一成功结果；不修改共享 Mapper。
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.concurrency = new Semaphore(configuration.maxConcurrentRequests());
    }

    public JsonNode read(DaikinEndpoint endpoint, String resourceId, Map<String, String> parameters) {
        ensureReady();
        if (endpoint == null || !endpoint.publicRead()) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
        }
        String validatedPath = endpoint.path(resourceId);
        Map<String, String> safeParameters = validateParameters(parameters);
        DaikinWireCodec.Token current = validToken();
        JsonNode response;
        try {
            response = invoke(endpoint, validatedPath, safeParameters, current.accessToken());
        } catch (DaikinClientException ex) {
            if (ex.code() != DaikinClientException.Code.AUTHENTICATION_REQUIRED) {
                throw ex;
            }
            response = invoke(endpoint, validatedPath, safeParameters, renewToken(current).accessToken());
            return requireReadSuccess(response);
        }
        if (safeAuthenticationFailure(response)) {
            response = invoke(endpoint, validatedPath, safeParameters, renewToken(current).accessToken());
        }
        return requireReadSuccess(response);
    }

    private DaikinWireCodec.Token validToken() {
        Instant now = clock.instant();
        DaikinWireCodec.Token current = token;
        if (current != null && current.expiresAt().isAfter(now.plus(configuration.tokenRefreshSkew()))) {
            return current;
        }
        lockToken();
        try {
            now = clock.instant();
            current = token;
            if (current != null && current.expiresAt().isAfter(now.plus(configuration.tokenRefreshSkew()))) {
                return current;
            }
            return requestToken(current);
        } finally {
            tokenLock.unlock();
        }
    }

    private DaikinWireCodec.Token renewToken(DaikinWireCodec.Token rejected) {
        lockToken();
        try {
            if (token != rejected && token != null
                    && token.expiresAt().isAfter(clock.instant().plus(configuration.tokenRefreshSkew()))) {
                return token;
            }
            return requestToken(rejected);
        } finally {
            tokenLock.unlock();
        }
    }

    private DaikinWireCodec.Token requestToken(DaikinWireCodec.Token previous) {
        DaikinEndpoint endpoint = previous != null && !previous.refreshKey().isBlank()
                ? DaikinEndpoint.TOKEN_REFRESH : DaikinEndpoint.TOKEN;
        Map<String, String> tokenParameters = endpoint == DaikinEndpoint.TOKEN_REFRESH
                ? Map.of("refreshKey", previous.refreshKey())
                : Map.of("pass", configuration.credentials().secret());
        String refreshAccessToken = endpoint == DaikinEndpoint.TOKEN_REFRESH ? previous.accessToken() : null;
        JsonNode envelope = requireSuccess(invoke(endpoint, endpoint.path(null), tokenParameters,
                refreshAccessToken));
        DaikinWireCodec.Token decoded = safeDecodeToken(endpoint, envelope);
        if (!decoded.expiresAt().isAfter(clock.instant())) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_TOKEN_RESPONSE);
        }
        token = decoded;
        return decoded;
    }

    private JsonNode invoke(DaikinEndpoint endpoint, String path, Map<String, String> parameters,
                            String accessToken) {
        boolean acquired = false;
        try {
            acquired = concurrency.tryAcquire(configuration.concurrencyWait().toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new DaikinClientException(DaikinClientException.Code.CONCURRENCY_LIMIT);
            }
            awaitRateBudget();
            DaikinWireCodec.EncodedRequest encoded;
            try {
                encoded = Objects.requireNonNull(wireCodec.encode(endpoint, parameters,
                        configuration.credentials(), accessToken, clock.instant()));
            } catch (DaikinClientException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST, ex);
            }
            URI uri = buildUri(path, validateParameters(encoded.queryParameters()), encoded.encryptedQuery());
            DaikinHttpTransport.Response response = transport.execute(
                    new DaikinHttpTransport.Request(endpoint.method(), uri, encoded.headers(), encoded.body(),
                            configuration.requestTimeout()), configuration.maxResponseBytes());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new DaikinClientException(DaikinClientException.Code.AUTHENTICATION_REQUIRED);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DaikinClientException(DaikinClientException.Code.REMOTE_REJECTED);
            }
            byte[] responseBody = response.body();
            if (responseBody.length > configuration.maxResponseBytes()) {
                throw new DaikinClientException(DaikinClientException.Code.RESPONSE_TOO_LARGE);
            }
            try {
                return objectMapper.readTree(responseBody);
            } catch (IOException ex) {
                throw new DaikinClientException(DaikinClientException.Code.INVALID_RESPONSE, ex);
            }
        } catch (DaikinClientException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DaikinClientException(DaikinClientException.Code.INTERRUPTED, ex);
        } catch (IOException ex) {
            throw new DaikinClientException(DaikinClientException.Code.TRANSPORT_FAILURE, ex);
        } catch (RuntimeException ex) {
            throw new DaikinClientException(DaikinClientException.Code.TRANSPORT_FAILURE, ex);
        } finally {
            if (acquired) {
                concurrency.release();
            }
        }
    }

    private JsonNode requireSuccess(JsonNode response) {
        if (response == null || !response.isObject()) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_RESPONSE);
        }
        JsonNode code = response.get("code");
        if (code == null || !(code.isIntegralNumber() && code.bigIntegerValue().equals(BigInteger.valueOf(10000))
                || code.isTextual() && "10000".equals(code.asText()))) {
            throw new DaikinClientException(DaikinClientException.Code.REMOTE_REJECTED);
        }
        return response;
    }

    private JsonNode requireReadSuccess(JsonNode response) {
        if (safeAuthenticationFailure(response)) {
            throw new DaikinClientException(DaikinClientException.Code.AUTHENTICATION_REQUIRED);
        }
        return requireSuccess(response);
    }

    private URI buildUri(String path, Map<String, String> queryParameters, String encryptedQuery) {
        StringBuilder value = new StringBuilder(configuration.baseUri().toString().replaceAll("/$", ""))
                .append(path);
        if (encryptedQuery != null) {
            // 厂家 GET 要求整个业务参数 JSON 加密后作为无键查询串，不能改写成 page=1。
            value.append('?').append(urlEncode(encryptedQuery));
        } else if (!queryParameters.isEmpty()) {
            Map<String, String> ordered = new LinkedHashMap<>();
            queryParameters.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
            value.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : ordered.entrySet()) {
                if (!first) {
                    value.append('&');
                }
                first = false;
                value.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
            }
        }
        return URI.create(value.toString());
    }

    private void awaitRateBudget() throws InterruptedException {
        long intervalNanos = 1_000_000_000L / configuration.requestsPerSecond();
        synchronized (rateLock) {
            long now = System.nanoTime();
            if (!rateInitialized) {
                rateInitialized = true;
                nextRequestNanos = now + intervalNanos;
                return;
            }
            long remaining = nextRequestNanos - now;
            if (remaining > 0) {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
                now = System.nanoTime();
            }
            nextRequestNanos = now + intervalNanos;
        }
    }

    private void lockToken() {
        try {
            if (!tokenLock.tryLock(configuration.concurrencyWait().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new DaikinClientException(DaikinClientException.Code.CONCURRENCY_LIMIT);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DaikinClientException(DaikinClientException.Code.INTERRUPTED, ex);
        }
    }

    private DaikinWireCodec.Token safeDecodeToken(DaikinEndpoint endpoint, JsonNode envelope) {
        try {
            return Objects.requireNonNull(wireCodec.decodeToken(endpoint, envelope, clock.instant()));
        } catch (DaikinClientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_TOKEN_RESPONSE, ex);
        }
    }

    private boolean safeAuthenticationFailure(JsonNode response) {
        try {
            return wireCodec.isAuthenticationFailure(response);
        } catch (RuntimeException ex) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_RESPONSE, ex);
        }
    }

    private static Map<String, String> validateParameters(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        if (parameters.size() > 64) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 128
                    || entry.getValue() == null || entry.getValue().length() > 4096) {
                throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
            }
        }
        return Map.copyOf(parameters);
    }

    private void ensureReady() {
        if (!configuration.enabled()) {
            throw new DaikinClientException(DaikinClientException.Code.CLIENT_DISABLED);
        }
        if (!configuration.wireConfirmed() || wireCodec == null) {
            throw new DaikinClientException(DaikinClientException.Code.WIRE_NOT_CONFIRMED);
        }
    }

    private static String urlEncode(String value) {
        if (value == null) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
