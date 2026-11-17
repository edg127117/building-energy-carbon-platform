package com.platform.iot.communication;

import com.platform.iot.identity.*;
import com.platform.iot.ingest.standard.*;
import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.CorrelationPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CommunicationEvidenceRecorderTest {
    @Test
    void evidenceStoreFailureIsLatchedEvenWhenMqttConnectionRemainsReady() {
        var service=mock(DeviceCommunicationService.class);
        var recorder=new CommunicationEvidenceRecorder(service,mock(JdbcTemplate.class));
        var key=new DeviceIdentityKey("SN","TEST");
        var binding=new DeviceIdentityBinding("I1",key,"E1","E1","B1","P",AckMode.EVIDENCE_ONLY,CorrelationPolicy.NONE,null,null);
        long now=System.currentTimeMillis();
        var message=new StandardTelemetryMessage("1.0","P",1,key,now,now,"DEVICE_REPORTED",1L,List.of());
        when(service.observe(any())).thenThrow(new IllegalStateException("isolated communication storage failure")).thenReturn(false).thenReturn(true);
        recorder.mqttReady(true);
        recorder.standard(binding,message,now);
        assertThat(recorder.mqttHealthy(now,45_000)).isFalse();
        verify(service).sourceHealth(eq("MQTT"),eq("FAILED"),anyLong());
        recorder.standard(binding,message,now);
        assertThat(recorder.mqttHealthy(now,45_000)).isFalse();
        recorder.standard(binding,message,now);
        assertThat(recorder.mqttHealthy(now,45_000)).isTrue();
    }

    @Test
    void retainedOrDuplicateDeliveryCannotCreateAnyCommunicationEvidence() {
        var service=mock(DeviceCommunicationService.class);
        var recorder=new CommunicationEvidenceRecorder(service,mock(JdbcTemplate.class));
        recorder.beginMqtt(false,System.currentTimeMillis());
        try {recorder.standard(null,null,0);} finally {recorder.endMqtt();}
        verifyNoInteractions(service);
    }
}
