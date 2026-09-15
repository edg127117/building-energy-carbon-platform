package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

/**
 * 厂家确认后的 wire 封装边界。Header 是否签名、GET 密文位置等未确认内容必须由联调实现显式提供，
 * 客户端不会根据协议展示样例猜测默认值。
 */
public interface DaikinWireCodec {

    EncodedRequest encode(DaikinEndpoint endpoint, Map<String, String> logicalParameters,
                          DaikinClientConfiguration.Credentials credentials, String accessToken, Instant now);

    Token decodeToken(DaikinEndpoint endpoint, JsonNode successfulEnvelope, Instant now);

    boolean isAuthenticationFailure(JsonNode envelope);

    record EncodedRequest(Map<String, String> headers, Map<String, String> queryParameters, byte[] body) {
        public EncodedRequest {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            queryParameters = queryParameters == null ? Map.of() : Map.copyOf(queryParameters);
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "EncodedRequest[headers=<redacted>, queryParameters=<redacted>, body=<redacted>]";
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
