package com.platform.iot.mqtt;

import com.platform.iot.reliability.mapper.MqttFailureAggregateMapper;
import com.platform.iot.reliability.model.MqttFailureAggregate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MqttFailureEvidenceRecorderTest {

    @Test
    void aggregatesFailuresAndStoresOnlySanitizedEndpoint() {
        MqttFailureAggregateMapper mapper = mock(MqttFailureAggregateMapper.class);
        MqttFailureEvidenceRecorder recorder = new MqttFailureEvidenceRecorder(mapper);

        recorder.record("PLATFORM", MqttFailureCategory.BAD_CREDENTIALS,
                "ssl://user:secret@broker.example:8883");
        recorder.record("PLATFORM", MqttFailureCategory.BAD_CREDENTIALS,
                "ssl://user:secret@broker.example:8883");
        recorder.flush();

        ArgumentCaptor<MqttFailureAggregate> aggregate =
                ArgumentCaptor.forClass(MqttFailureAggregate.class);
        verify(mapper).upsert(aggregate.capture());
        assertThat(aggregate.getValue().getOccurrenceCount()).isEqualTo(2);
        assertThat(aggregate.getValue().getBrokerEndpoint())
                .isEqualTo("ssl://broker.example:8883")
                .doesNotContain("user", "secret");
    }
}
