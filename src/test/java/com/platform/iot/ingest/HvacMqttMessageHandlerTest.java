package com.platform.iot.ingest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HvacMqttMessageHandlerTest {

    @Test
    void delegatesPayloadAndPreservesAcknowledgementDecision() {
        HvacIngestionService service = mock(HvacIngestionService.class);
        Map<String, Object> payload = Map.of("pointCode", "WCR1_TWin");
        when(service.ingest(payload, 1000L))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.ACCEPTED))
                .thenReturn(HvacIngestionResult.storageFailed("tdengine unavailable"));
        HvacMqttMessageHandler handler = new HvacMqttMessageHandler(service);

        assertThat(handler.handle(payload, 1000L).shouldAcknowledge()).isTrue();
        assertThat(handler.handle(payload, 1000L).shouldAcknowledge()).isFalse();
    }
}
