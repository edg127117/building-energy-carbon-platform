package com.platform.iot.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HvacRealtimeProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HvacRealtimeProtocol protocol = new HvacRealtimeProtocol(objectMapper);

    @Test
    void decodesSubscribeWithoutEchoingToken() {
        var message = protocol.decodeClient("""
                {"type":"SUBSCRIBE","token":"jwt-value","buildingId":"BLD001"}
                """);

        assertThat(message).isEqualTo(
                new HvacRealtimeProtocol.Subscribe("jwt-value", "BLD001"));
        assertThat(protocol.subscribed("BLD001", 100L))
                .contains("\"type\":\"SUBSCRIBED\"")
                .contains("\"buildingId\":\"BLD001\"")
                .doesNotContain("jwt-value");
    }

    @Test
    void decodesPingAndUsesServerTimeInPong() throws Exception {
        assertThat(protocol.decodeClient("{\"type\":\"PING\"}"))
                .isEqualTo(HvacRealtimeProtocol.Ping.INSTANCE);

        JsonNode pong = objectMapper.readTree(protocol.pong(100L));
        assertThat(pong.path("type").asText()).isEqualTo("PONG");
        assertThat(pong.path("serverTime").asLong()).isEqualTo(100L);
        assertThat(pong.has("timestamp")).isFalse();
    }

    @Test
    void rejectsUnknownTypeBlankFieldsAndOversizedPayload() {
        assertBadProtocol("{\"type\":\"CONTROL\"}");
        assertBadProtocol("{\"type\":\"SUBSCRIBE\",\"token\":\" \",\"buildingId\":\"BLD001\"}");
        assertBadProtocol("{\"type\":\"SUBSCRIBE\",\"token\":\"jwt\",\"buildingId\":\" \"}");
        assertBadProtocol("not-json");
        assertBadProtocol("x".repeat(HvacRealtimeProtocol.MAX_CLIENT_MESSAGE_CHARS + 1));
    }

    @Test
    void producesDesensitizedErrorEnvelope() {
        String error = protocol.error("FORBIDDEN_BUILDING", "无权订阅该建筑");

        assertThat(error)
                .contains("FORBIDDEN_BUILDING")
                .doesNotContain("token")
                .doesNotContain("stack");
    }

    private void assertBadProtocol(String payload) {
        assertThatThrownBy(() -> protocol.decodeClient(payload))
                .isInstanceOf(HvacRealtimeAccessException.class)
                .satisfies(error -> {
                    HvacRealtimeAccessException accessError =
                            (HvacRealtimeAccessException) error;
                    assertThat(accessError.errorCode()).isEqualTo("BAD_PROTOCOL");
                    assertThat(accessError.closeCode())
                            .isEqualTo(HvacRealtimeProtocol.CLOSE_BAD_PROTOCOL);
                });
    }
}
