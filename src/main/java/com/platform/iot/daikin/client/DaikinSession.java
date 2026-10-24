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
import com.platform.iot.daikin.control.DaikinControlGateway;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 同一来源共享认证、限流与传输；读取可续期重试，控制仅通过独立单次入口发送。
 */
public final class DaikinSession {
    private final DaikinClientConfiguration configuration;
    private final DaikinWireCodec wireCodec;
    private final DaikinHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DaikinHttpBudget budget;
    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile DaikinWireCodec.Token token;
    private static final Duration AUTHENTICATION_BACKOFF = Duration.ofSeconds(30);
    // 由 tokenLock 保护；认证被拒后让后续采集轮次再试，避免并发请求逐个重新登录。
    private Instant authenticationRetryAt = Instant.MIN;

    public DaikinSession(DaikinClientConfiguration configuration, DaikinWireCodec wireCodec,
                                DaikinHttpTransport transport, ObjectMapper objectMapper, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.wireCodec = wireCodec;
        this.transport = Objects.requireNonNull(transport, "transport");
        // 外部响应中重复键或尾随第二份 JSON 不能被宽松读取为单一成功结果；不修改共享 Mapper。
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budget = new DaikinHttpBudget(configuration);
    }

    public JsonNode read(DaikinEndpoint endpoint, String resourceId, Map<String, String> parameters) {
        ensureReady();
        if (endpoint == null || !endpoint.publicRead()) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
        }
        String validatedPath = endpoint.path(resourceId);
        Map<String, String> safeParameters = validateParameters(parameters);
        DaikinWireCodec.Token current = validToken();
        try {
            return readWithToken(endpoint, validatedPath, safeParameters, current);
        } catch (DaikinClientException ex) {
            if (ex.code() != DaikinClientException.Code.AUTHENTICATION_REQUIRED) {
                throw ex;
            }
        }
        DaikinWireCodec.Token renewed = renewToken(current);
        try {
            return readWithToken(endpoint, validatedPath, safeParameters, renewed);
        } catch (DaikinClientException ex) {
            if (ex.code() == DaikinClientException.Code.AUTHENTICATION_REQUIRED) {
                rejectToken(renewed);
            }
            throw ex;
        }
    }

    private JsonNode readWithToken(DaikinEndpoint endpoint, String path, Map<String, String> parameters,
                                   DaikinWireCodec.Token current) {
        return requireReadSuccess(invoke(endpoint, path, parameters, current.accessToken()));
    }

    /** 只由控制适配器调用。即使收到认证失败也不重发有副作用的 PUT。 */
    JsonNode controlOnce(DaikinEndpoint endpoint, String resourceId, Map<String, String> parameters) {
        ensureReady();
        if (endpoint == null || !endpoint.method().equals("PUT")) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
        }
        return invoke(endpoint, endpoint.path(resourceId), validateParameters(parameters), controlToken());
    }

    List<DaikinControlGateway.SendPermit> prepareSendWave(int size) {
        // 波次等待覆盖同伴的预读超时，但小于操作发送意图的 30 秒只读恢复边界。
        Duration wait=configuration.requestTimeout().plus(configuration.concurrencyWait().multipliedBy(2));
        return DaikinSendWave.prepare(budget,size,wait.compareTo(Duration.ofSeconds(25))>0?Duration.ofSeconds(25):wait);
    }

    JsonNode controlOnce(DaikinEndpoint endpoint,String resourceId,Map<String,String> parameters,DaikinControlGateway.SendPermit permit) {
        ensureReady();
        if(endpoint==null || !endpoint.method().equals("PUT")) throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
        return invoke(endpoint,endpoint.path(resourceId),validateParameters(parameters),controlToken(),permit);
    }

    private String controlToken() {
        try { return validToken().accessToken(); }
        catch(RuntimeException failure) { throw new DaikinControlGateway.NotSentException("CONTROL_AUTH_UNAVAILABLE"); }
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
            return requestToken(token);
        } finally {
            tokenLock.unlock();
        }
    }

    private DaikinWireCodec.Token requestToken(DaikinWireCodec.Token previous) {
        if (clock.instant().isBefore(authenticationRetryAt)) {
            throw new DaikinClientException(DaikinClientException.Code.AUTHENTICATION_REQUIRED);
        }
        try {
            if (previous != null && !previous.refreshKey().isBlank()) {
                try {
                    return acquireToken(DaikinEndpoint.TOKEN_REFRESH,
                            Map.of("refreshKey", previous.refreshKey()), previous.accessToken());
                } catch (DaikinClientException ex) {
                    if (ex.code() != DaikinClientException.Code.AUTHENTICATION_REQUIRED) {
                        throw ex;
                    }
                    // 仅明确的认证拒绝触发一次完整登录；网络/响应错误不推断为凭据失效。
                    token = null;
                }
            }
            return acquireToken(DaikinEndpoint.TOKEN,
                    Map.of("pass", configuration.credentials().secret()), null);
        } catch (DaikinClientException ex) {
            if (ex.code() == DaikinClientException.Code.AUTHENTICATION_REQUIRED) {
                token = null;
                authenticationRetryAt = clock.instant().plus(AUTHENTICATION_BACKOFF);
            }
            throw ex;
        }
    }

    private DaikinWireCodec.Token acquireToken(DaikinEndpoint endpoint, Map<String, String> parameters,
                                               String accessToken) {
        JsonNode envelope = requireReadSuccess(invoke(endpoint, endpoint.path(null), parameters, accessToken));
        DaikinWireCodec.Token decoded = safeDecodeToken(endpoint, envelope);
        if (!decoded.expiresAt().isAfter(clock.instant())) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_TOKEN_RESPONSE);
        }
        token = decoded;
        authenticationRetryAt = Instant.MIN;
        return decoded;
    }

    private void rejectToken(DaikinWireCodec.Token rejected) {
        lockToken();
        try {
            // 迟到的失败不能清除其他请求刚取得的新一代凭据。
            if (token == rejected) {
                token = null;
                authenticationRetryAt = clock.instant().plus(AUTHENTICATION_BACKOFF);
            }
        } finally {
            tokenLock.unlock();
        }
    }

    private JsonNode invoke(DaikinEndpoint endpoint, String path, Map<String, String> parameters,
                            String accessToken) {
        return invoke(endpoint,path,parameters,accessToken,null);
    }

    private JsonNode invoke(DaikinEndpoint endpoint, String path, Map<String,String> parameters,
                            String accessToken, DaikinControlGateway.SendPermit sendPermit) {
        DaikinHttpBudget.Ticket ticket=null;
        boolean transportStarted=false;
        try {
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
            if(sendPermit instanceof DaikinSendWave.Member member) ticket=member.ticket(budget);
            else {
                if(sendPermit!=null) sendPermit.awaitReady();
                ticket=budget.acquire(1).getFirst();
            }
            ticket.start();
            transportStarted=true;
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
            if(!transportStarted && "PUT".equals(endpoint.method())
                    && (ex.code()==DaikinClientException.Code.CONCURRENCY_LIMIT || ex.code()==DaikinClientException.Code.INTERRUPTED))
                throw new DaikinControlGateway.NotSentException("HTTP_BUDGET_UNAVAILABLE");
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if(!transportStarted && "PUT".equals(endpoint.method())) throw new DaikinControlGateway.NotSentException("HTTP_BUDGET_UNAVAILABLE");
            throw new DaikinClientException(DaikinClientException.Code.INTERRUPTED, ex);
        } catch (IOException ex) {
            throw new DaikinClientException(DaikinClientException.Code.TRANSPORT_FAILURE, ex);
        } catch (RuntimeException ex) {
            throw new DaikinClientException(DaikinClientException.Code.TRANSPORT_FAILURE, ex);
        } finally {
            if (ticket!=null) ticket.close();
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
