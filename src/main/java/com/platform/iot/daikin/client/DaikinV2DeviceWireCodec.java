package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大金 V2 设备清单的实际 wire 格式：明文业务参数参与签名，完整 JSON 加密后作为
 * POST 请求体或 GET 无键查询串。仅装配已联调的认证及内外机清单，控制和统计不在此边界。
 */
public final class DaikinV2DeviceWireCodec implements DaikinWireCodec {
    private final ObjectMapper mapper;

    public DaikinV2DeviceWireCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy();
    }

    @Override
    public EncodedRequest encode(DaikinEndpoint endpoint, Map<String, String> logicalParameters,
                                 DaikinClientConfiguration.Credentials credentials,
                                 String accessToken, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (endpoint) {
            case TOKEN -> payload.put("pass", required(logicalParameters, "pass"));
            case TOKEN_REFRESH -> payload.put("refreshKey", required(logicalParameters, "refreshKey"));
            case INUNITS, OUTUNITS -> {
                String page = required(logicalParameters, "page");
                if (!page.matches("[1-9][0-9]{0,3}")) throw invalid();
                payload.put("page", Integer.parseInt(page));
            }
            default -> throw invalid();
        }
        if (logicalParameters == null || logicalParameters.size() != payload.size()) throw invalid();
        Map<String, String> signingValues = new LinkedHashMap<>();
        payload.forEach((key, value) -> signingValues.put(key, value.toString()));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("appId", credentials.account());
        headers.put("sign", DaikinProtocolCrypto.sign(signingValues, credentials.salt()));
        headers.put("Content-Type", "application/json");
        if (accessToken != null) headers.put("token", accessToken);
        try {
            String ciphertext = DaikinProtocolCrypto.encrypt(mapper.writeValueAsString(payload),
                    credentials.encryptionKey());
            if (endpoint.method().equals("GET")) {
                return new EncodedRequest(headers, Map.of(), new byte[0], ciphertext);
            }
            return new EncodedRequest(headers, Map.of(), ciphertext.getBytes(StandardCharsets.US_ASCII));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST, ex);
        }
    }

    @Override
    public Token decodeToken(DaikinEndpoint endpoint, JsonNode successfulEnvelope, Instant now) {
        if (endpoint != DaikinEndpoint.TOKEN && endpoint != DaikinEndpoint.TOKEN_REFRESH) throw invalid();
        JsonNode data = successfulEnvelope.path("data");
        String token = text(data, "token");
        String refreshKey = text(data, "refreshKey");
        // 当前厂家响应是 expireAt；旧协议样例写作 expiredAt，两者只接受可解析的带时区时间。
        String expiry = text(data, "expireAt");
        if (expiry == null) expiry = text(data, "expiredAt");
        if (token == null || expiry == null) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_TOKEN_RESPONSE);
        }
        try {
            return new Token(token, refreshKey, OffsetDateTime.parse(expiry).toInstant());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_TOKEN_RESPONSE, ex);
        }
    }

    @Override
    public boolean isAuthenticationFailure(JsonNode envelope) {
        String code = envelope == null ? "" : envelope.path("code").asText();
        return "10010".equals(code) || "10011".equals(code);
    }

    private static String required(Map<String, String> values, String key) {
        if (values == null || values.get(key) == null || values.get(key).isBlank()) throw invalid();
        return values.get(key);
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }

    private static DaikinClientException invalid() {
        return new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
    }
}
