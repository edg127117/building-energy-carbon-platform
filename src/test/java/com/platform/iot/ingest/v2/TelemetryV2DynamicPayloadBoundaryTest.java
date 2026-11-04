package com.platform.iot.ingest.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 不能在V2普通遥测消息中静默忽略设备周期扩展并返回已持久化。 */
class TelemetryV2DynamicPayloadBoundaryTest {
    @Test void rejectsIntervalOnV2TopicBeforeIngestion() {
        var ingestion=mock(TelemetryV2IngestionService.class);
        var handler=new TelemetryV2MqttMessageHandler(new ObjectMapper(),ingestion);
        var result=handler.handle("{\"electricityInterval\":{\"revision\":1},\"metrics\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8),System.currentTimeMillis());
        assertThat(result.retryable()).isFalse();assertThat(result.resultCode()).isEqualTo("MALFORMED_V2_JSON");
        verify(ingestion).recordMalformedFailure(anyString());verify(ingestion,never()).ingest(any(),anyLong());
    }
}
