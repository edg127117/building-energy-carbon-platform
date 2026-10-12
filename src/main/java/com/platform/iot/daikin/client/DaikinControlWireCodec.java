package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 按文档构造控制 PUT 及单内机读取；须经 wireConfirmed 开关启用，不能视为真实联调证据。 */
public final class DaikinControlWireCodec implements DaikinWireCodec {
    private final DaikinV2DeviceWireCodec reads;
    private final ObjectMapper mapper;
    public DaikinControlWireCodec(ObjectMapper mapper) { this.mapper = mapper.copy(); this.reads = new DaikinV2DeviceWireCodec(mapper); }

    @Override public EncodedRequest encode(DaikinEndpoint endpoint, Map<String, String> parameters,
            DaikinClientConfiguration.Credentials credentials, String token, Instant now) {
        if (endpoint != DaikinEndpoint.INUNIT && !endpoint.method().equals("PUT"))
            return reads.encode(endpoint, parameters, credentials, token, now);
        String key = switch (endpoint) {
            case CONTROL_ON_OFF -> "onOff";
            case CONTROL_TEMPERATURE -> "temperature";
            case CONTROL_MODE -> "mode";
            case CONTROL_AIR_FLOW -> "airFlow";
            case CONTROL_FAN_SPEED -> "fanSpeed";
            case INUNIT -> null;
            default -> throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
        };
        if (parameters == null || key == null && !parameters.isEmpty()
                || key != null && (parameters.size() != 1 || !parameters.containsKey(key)))
            throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("appId", credentials.account()); headers.put("token", token);
        headers.put("Content-Type", "application/json");
        headers.put("sign", DaikinProtocolCrypto.sign(parameters, credentials.salt()));
        try {
            String ciphertext = DaikinProtocolCrypto.encrypt(mapper.writeValueAsString(parameters), credentials.encryptionKey());
            return key == null ? new EncodedRequest(headers, Map.of(), new byte[0], ciphertext)
                    : new EncodedRequest(headers, Map.of(), ciphertext.getBytes(StandardCharsets.US_ASCII));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new DaikinClientException(DaikinClientException.Code.INVALID_REQUEST, e);
        }
    }
    @Override public Token decodeToken(DaikinEndpoint endpoint, JsonNode envelope, Instant now) { return reads.decodeToken(endpoint, envelope, now); }
    @Override public boolean isAuthenticationFailure(JsonNode envelope) { return reads.isAuthenticationFailure(envelope); }
}
