package com.platform.iot.formula;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.IndicatorLatestState;
import com.platform.iot.websocket.RealtimeMessageGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IndicatorRealtimePublisherTest {

    @Test
    void sendsHvacIndicatorEnvelopeToTheStateBuildingThroughInjectedGateway() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RealtimeMessageGateway gateway = mock(RealtimeMessageGateway.class);
        IndicatorRealtimePublisher publisher = new IndicatorRealtimePublisher(objectMapper, gateway);
        IndicatorLatestState state = state();

        publisher.publish(state);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(gateway).sendToBuilding(eq("BLD001"), message.capture());
        JsonNode json = objectMapper.readTree(message.getValue());
        assertThat(json.path("type").asText()).isEqualTo("HVAC_INDICATOR");
        assertThat(json.path("data").path("indicatorId").asText())
                .isEqualTo(state.indicatorId());
        assertThat(json.path("data").path("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void serializationFailureIsLoggedAndDoesNotEscape() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RealtimeMessageGateway gateway = mock(RealtimeMessageGateway.class);
        IndicatorRealtimePublisher publisher = new IndicatorRealtimePublisher(objectMapper, gateway);
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("cannot serialize") {});

        assertThatCode(() -> publisher.publish(state())).doesNotThrowAnyException();
        verifyNoInteractions(gateway);
    }

    @Test
    void deliveryFailureIsLoggedAndDoesNotEscape() {
        RealtimeMessageGateway gateway = mock(RealtimeMessageGateway.class);
        IndicatorRealtimePublisher publisher = new IndicatorRealtimePublisher(new ObjectMapper(), gateway);
        doThrow(new IllegalStateException("socket unavailable"))
                .when(gateway).sendToBuilding(eq("BLD001"), any());

        assertThatCode(() -> publisher.publish(state())).doesNotThrowAnyException();
        verify(gateway).sendToBuilding(eq("BLD001"), any());
    }

    @Test
    void skipsStatesWithoutAnExplicitBuildingRoute() {
        RealtimeMessageGateway gateway = mock(RealtimeMessageGateway.class);
        IndicatorRealtimePublisher publisher = new IndicatorRealtimePublisher(new ObjectMapper(), gateway);
        IndicatorLatestState withoutBuilding = new IndicatorLatestState(
                "IND001", "WCR_COP", " ", "EQUIP001", 1_800_000_000_000L,
                FormulaCalculation.Status.SUCCESS, 5.8, 0, "WCR_COP_V1", null,
                List.of(), List.of(), List.of());

        assertThatCode(() -> publisher.publish(withoutBuilding)).doesNotThrowAnyException();

        verifyNoInteractions(gateway);
    }

    private static IndicatorLatestState state() {
        return new IndicatorLatestState(
                "IND001", "WCR_COP", "BLD001", "EQUIP001", 1_800_000_000_000L,
                FormulaCalculation.Status.SUCCESS, 5.8, 0, "WCR_COP_V1", null,
                List.of(), List.of(), List.of());
    }
}
