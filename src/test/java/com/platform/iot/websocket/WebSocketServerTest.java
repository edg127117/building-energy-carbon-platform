package com.platform.iot.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WebSocketServerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private HvacRealtimeSessionRegistry registry;
    @Mock
    private Session session;

    private WebSocketServer server;

    @BeforeEach
    void setUp() {
        lenient().when(session.getId()).thenReturn("session-1");
        server = new WebSocketServer(new HvacRealtimeProtocol(objectMapper), registry);
    }

    @Test
    void onOpenOnlyRegistersPendingState() {
        server.onOpen(session);

        verify(registry).open(session);
        verifyNoMoreInteractions(registry);
    }

    @Test
    void validSubscribeAuthorizesAndReturnsSubscribedEnvelope() throws Exception {
        when(registry.subscribe(session, "jwt", "BLD001"))
                .thenReturn(subscription());

        server.onMessage("{\"type\":\"SUBSCRIBE\",\"token\":\"jwt\",\"buildingId\":\"BLD001\"}", session);

        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(registry).sendControl(eq(session), response.capture());
        JsonNode envelope = objectMapper.readTree(response.getValue());
        assertThat(envelope.path("type").asText()).isEqualTo("SUBSCRIBED");
        assertThat(envelope.path("buildingId").asText()).isEqualTo("BLD001");
        assertThat(response.getValue()).doesNotContain("jwt");
    }

    @Test
    void validPingRefreshesAuthorizationAndReturnsPongWithServerTime() throws Exception {
        when(registry.ping(session)).thenReturn(subscription());

        server.onMessage("{\"type\":\"PING\"}", session);

        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(registry).sendControl(eq(session), response.capture());
        JsonNode envelope = objectMapper.readTree(response.getValue());
        assertThat(envelope.path("type").asText()).isEqualTo("PONG");
        assertThat(envelope.path("serverTime").canConvertToLong()).isTrue();
    }

    @Test
    void malformedFrameSendsSanitizedErrorBeforeBadProtocolClose() throws Exception {
        server.onMessage("not-json", session);

        assertErrorThenClose(HvacRealtimeProtocol.CLOSE_BAD_PROTOCOL, "BAD_PROTOCOL");
    }

    @Test
    void invalidJwtAndForbiddenBuildingUseConfirmedCloseCodes() throws Exception {
        doThrow(new HvacRealtimeAccessException("UNAUTHORIZED", 4401, "登录状态无效"))
                .when(registry).subscribe(session, "bad", "BLD001");

        server.onMessage("{\"type\":\"SUBSCRIBE\",\"token\":\"bad\",\"buildingId\":\"BLD001\"}", session);

        assertErrorThenClose(4401, "UNAUTHORIZED");

        doThrow(new HvacRealtimeAccessException("FORBIDDEN_BUILDING", 4403, "无权订阅该建筑"))
                .when(registry).subscribe(session, "jwt", "BLD002");
        server.onMessage("{\"type\":\"SUBSCRIBE\",\"token\":\"jwt\",\"buildingId\":\"BLD002\"}", session);

        verify(registry).close(eq(session), eq(4403), eq("无权订阅该建筑"));
    }

    @Test
    void unexpectedDependencyFailureClosesWith1011() throws Exception {
        doThrow(new IllegalStateException("dependency unavailable"))
                .when(registry).subscribe(session, "jwt", "BLD001");

        server.onMessage("{\"type\":\"SUBSCRIBE\",\"token\":\"jwt\",\"buildingId\":\"BLD001\"}", session);

        assertErrorThenClose(1011, "REALTIME_INTERNAL_ERROR");
    }

    @Test
    void closeAndErrorBothDelegateToIdempotentRegistryCleanup() {
        server.onClose(session);
        server.onError(session, new IllegalStateException("unexpected"));

        verify(registry, org.mockito.Mockito.times(2)).remove(session);
    }

    private void assertErrorThenClose(int closeCode, String errorCode) throws Exception {
        InOrder ordered = inOrder(registry);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        ordered.verify(registry).sendControl(eq(session), error.capture());
        ordered.verify(registry).close(eq(session), eq(closeCode), anyString());
        JsonNode envelope = objectMapper.readTree(error.getValue());
        assertThat(envelope.path("type").asText()).isEqualTo("ERROR");
        assertThat(envelope.path("code").asText()).isEqualTo(errorCode);
    }

    private HvacRealtimeSubscription subscription() {
        return new HvacRealtimeSubscription(
                1L, Set.of("BUILDING_OWNER"), "BLD001", "jwt",
                System.currentTimeMillis() + 60_000L);
    }
}
