package com.platform.iot.ingest.standard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StandardTelemetryMqttMessageHandlerTest {

    private StandardTelemetryIngestionService service;
    private StandardTelemetryMqttMessageHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(StandardTelemetryIngestionService.class);
        handler = new StandardTelemetryMqttMessageHandler(new ObjectMapper(), service);
    }

    @Test
    void deserializesCanonicalJsonAndDelegatesWithLocalReceiveTime() {
        long receivedTime = 1_785_398_401_000L;
        when(service.ingest(any(), eq(receivedTime)))
                .thenReturn(StandardTelemetryResult.accepted(1));
        byte[] payload = """
                {
                  "standardVersion":"1.0",
                  "profileCode":"ENERGY_METER_V1",
                  "profileVersion":1,
                  "deviceIdentity":{"type":"MAC","value":"123456789012345"},
                  "eventTime":1785398400000,
                  "receivedTime":1785398399990,
                  "timeSource":"DEVICE_REPORTED",
                  "seq":10001,
                  "metrics":[
                    {"code":"CURRENT_ENERGY","value":12.34,"unit":"kWh","sourceField":"/current_energy"}
                  ]
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        StandardTelemetryResult result = handler.handle(payload, receivedTime);

        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.ACCEPTED);
        verify(service).ingest(any(StandardTelemetryMessage.class), eq(receivedTime));
    }

    @Test
    void malformedJsonIsPoisonMessageAndCanBeAcknowledged() {
        StandardTelemetryResult result = handler.handle("{".getBytes(), 1000L);

        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        assertThat(result.shouldAcknowledge()).isTrue();
        verifyNoInteractions(service);
    }
}
