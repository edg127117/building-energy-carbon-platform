package com.platform.modbus.service;

import com.platform.modbus.config.ModbusEdgeProperties;
import com.platform.modbus.model.StandardTelemetryMessage;
import com.platform.modbus.model.TelemetryMessageFactory;
import com.platform.modbus.mqtt.TelemetryPublishException;
import com.platform.modbus.mqtt.TelemetryPublisher;
import com.platform.modbus.protocol.ModbusReadValues;
import com.platform.modbus.protocol.ModbusSession;
import com.platform.modbus.protocol.ModbusTransportException;
import com.platform.modbus.protocol.ModbusTransportFactory;
import com.platform.modbus.protocol.ReadPlanBuilder;
import com.platform.modbus.protocol.RegisterValueDecoder;
import com.platform.modbus.support.TestDeviceFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ModbusPollingServiceTest {

    @Test
    void publishesOneCompleteMessageAfterOneBatchedRead() {
        ModbusEdgeProperties properties = TestDeviceFactory.validProperties();
        RecordingTransport transport = new RecordingTransport(0);
        RecordingPublisher publisher = new RecordingPublisher(0);
        ModbusPollingService service = service(properties, transport, publisher);

        ModbusPollingService.PollResult result = service.poll(properties.getDevices().getFirst());

        assertThat(result).isEqualTo(ModbusPollingService.PollResult.SUCCESS);
        assertThat(transport.openCount).hasValue(1);
        assertThat(transport.reads).containsExactly("HOLDING_REGISTER:0:2:1");
        assertThat(publisher.messages).hasSize(1);
        assertThat(publisher.messages.getFirst().metrics())
                .extracting(metric -> metric.value().intValueExact())
                .containsExactly(10, 20);
    }

    @Test
    void retriesReadWithANewSessionAndDoesNotPublishPartialData() {
        ModbusEdgeProperties properties = TestDeviceFactory.validProperties();
        RecordingTransport transport = new RecordingTransport(1);
        RecordingPublisher publisher = new RecordingPublisher(0);
        ModbusPollingService service = service(properties, transport, publisher);

        assertThat(service.poll(properties.getDevices().getFirst()))
                .isEqualTo(ModbusPollingService.PollResult.SUCCESS);
        assertThat(transport.openCount).hasValue(2);
        assertThat(publisher.messages).hasSize(1);
    }

    @Test
    void exhaustsReadRetriesWithoutPublishing() {
        ModbusEdgeProperties properties = TestDeviceFactory.validProperties();
        RecordingTransport transport = new RecordingTransport(3);
        RecordingPublisher publisher = new RecordingPublisher(0);
        ModbusPollingService service = service(properties, transport, publisher);

        assertThat(service.poll(properties.getDevices().getFirst()))
                .isEqualTo(ModbusPollingService.PollResult.READ_FAILED);
        assertThat(publisher.messages).isEmpty();
    }

    @Test
    void retriesSameCanonicalMessageWhenMqttPublishFails() {
        ModbusEdgeProperties properties = TestDeviceFactory.validProperties();
        RecordingTransport transport = new RecordingTransport(0);
        RecordingPublisher publisher = new RecordingPublisher(2);
        ModbusPollingService service = service(properties, transport, publisher);

        assertThat(service.poll(properties.getDevices().getFirst()))
                .isEqualTo(ModbusPollingService.PollResult.SUCCESS);
        assertThat(publisher.attemptedIds)
                .hasSize(3)
                .allSatisfy(id -> assertThat(id)
                        .isEqualTo(publisher.attemptedIds.getFirst()));
        assertThat(publisher.messages).hasSize(1);
    }

    private ModbusPollingService service(
            ModbusEdgeProperties properties,
            ModbusTransportFactory transport,
            TelemetryPublisher publisher) {
        return new ModbusPollingService(
                properties,
                new ReadPlanBuilder(),
                new RegisterValueDecoder(),
                transport,
                new TelemetryMessageFactory(Clock.systemUTC()),
                publisher,
                ignored -> { },
                new SimpleMeterRegistry());
    }

    private static final class RecordingTransport implements ModbusTransportFactory {
        private final AtomicInteger remainingFailures;
        private final AtomicInteger openCount = new AtomicInteger();
        private final List<String> reads = new ArrayList<>();

        private RecordingTransport(int failures) {
            remainingFailures = new AtomicInteger(failures);
        }

        @Override
        public ModbusSession open(ModbusEdgeProperties.Connection connection) {
            openCount.incrementAndGet();
            if (remainingFailures.getAndDecrement() > 0) {
                throw new ModbusTransportException(
                        ModbusTransportException.FailureType.CONNECTION,
                        "test connection failure",
                        null);
            }
            return new ModbusSession() {
                @Override
                public ModbusReadValues read(
                        ModbusEdgeProperties.ReadFunction function,
                        int address,
                        int quantity,
                        int unitId) {
                    reads.add(function + ":" + address + ":" + quantity + ":" + unitId);
                    return new ModbusReadValues(null, new int[]{10, 20});
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class RecordingPublisher implements TelemetryPublisher {
        private final AtomicInteger remainingFailures;
        private final List<String> attemptedIds = new ArrayList<>();
        private final List<StandardTelemetryMessage> messages = new ArrayList<>();

        private RecordingPublisher(int failures) {
            remainingFailures = new AtomicInteger(failures);
        }

        @Override
        public void publish(StandardTelemetryMessage message) {
            attemptedIds.add(message.canonicalMessageId());
            if (remainingFailures.getAndDecrement() > 0) {
                throw new TelemetryPublishException("test publish failure", null);
            }
            messages.add(message);
        }
    }
}
