package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

/**
 * 厂家 wire 封装边界。客户端不自行猜测签名或 GET 密文格式；生产封装只覆盖已联调端点。
 */
public interface DaikinWireCodec {

    EncodedRequest encode(DaikinEndpoint endpoint, Map<String, String> logicalParameters,
                          DaikinClientConfiguration.Credentials credentials, String accessToken, Instant now);

    Token decodeToken(DaikinEndpoint endpoint, JsonNode successfulEnvelope, Instant now);

    boolean isAuthenticationFailure(JsonNode envelope);

    record EncodedRequest(Map<String, String> headers, Map<String, String> queryParameters,
                          byte[] body, String encryptedQuery) {
        public EncodedRequest(Map<String, String> headers, Map<String, String> queryParameters, byte[] body) {
            this(headers, queryParameters, body, null);
        }

        public EncodedRequest {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            queryParameters = queryParameters == null ? Map.of() : Map.copyOf(queryParameters);
            body = body == null ? new byte[0] : body.clone();
            if (encryptedQuery != null && (!queryParameters.isEmpty()
                    || !encryptedQuery.matches("[A-Za-z0-9+/]+={0,2}"))) {
                throw new IllegalArgumentException("厂家密文查询参数无效");
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "EncodedRequest[headers=<redacted>, queryParameters=<redacted>, body=<redacted>, encryptedQuery=<redacted>]";
        }
    }

    record Token(String accessToken, String refreshKey, Instant expiresAt) {
        public Token {
            if (accessToken == null || accessToken.isBlank() || expiresAt == null) {
                throw new DaikinClientException(DaikinClientException.Code.INVALID_TOKEN_RESPONSE);
            }
            refreshKey = refreshKey == null ? "" : refreshKey;
        }

        @Override
        public String toString() {
            return "Token[<redacted>]";
        }
    }
}
