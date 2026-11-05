package com.platform.iot.ingest.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V2周期扩展必须保留给接入服务，不能被JSON边界静默丢弃。 */
class TelemetryV2DynamicPayloadBoundaryTest {
    @Test void routesIntervalToV2IngestionWithoutDroppingIt() {
        var ingestion=mock(TelemetryV2IngestionService.class);
        var handler=new TelemetryV2MqttMessageHandler(new ObjectMapper(),ingestion);
        var expected=new V2ProcessingResult("M",null,"PENDING",0,true,com.platform.iot.reliability.AckMode.EVIDENCE_ONLY,null,null,null);
        when(ingestion.ingest(any(),anyLong())).thenReturn(expected);
        var result=handler.handle("{\"electricityInterval\":{\"revision\":1},\"metrics\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8),System.currentTimeMillis());
        assertThat(result).isSameAs(expected);
        var request=org.mockito.ArgumentCaptor.forClass(TelemetryV2Message.class);verify(ingestion).ingest(request.capture(),anyLong());
        assertThat(request.getValue().electricityInterval().revision()).isEqualTo(1);verify(ingestion,never()).recordMalformedFailure(anyString());
    }
}
