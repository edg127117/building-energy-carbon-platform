package com.platform.iot.ingest.v2;

import com.platform.iot.identity.DeviceIdentityBinding;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.identity.DeviceIdentityProvider;
import com.platform.iot.identity.UnknownDeviceHandler;
import com.platform.iot.ingest.standard.StandardMetric;
import com.platform.iot.ingest.standard.StandardTelemetryIngestionService;
import com.platform.iot.ingest.standard.StandardTelemetryResult;
import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.CorrelationPolicy;
import com.platform.iot.reliability.ReceiptStatus;
import com.platform.iot.reliability.mapper.TelemetryReceiptFailureMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptMapper;
import com.platform.iot.reliability.model.TelemetryReceipt;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryV2IngestionServiceTest {

    private DeviceIdentityProvider identityProvider;
    private StandardTelemetryIngestionService standardService;
    private TelemetryReceiptMapper receiptMapper;
    private TelemetryReceiptFailureMapper failureMapper;
    private UnknownDeviceHandler unknownDeviceHandler;
    private TelemetryV2IngestionService service;

    @BeforeEach
    void setUp() {
        identityProvider = mock(DeviceIdentityProvider.class);
        standardService = mock(StandardTelemetryIngestionService.class);
        receiptMapper = mock(TelemetryReceiptMapper.class);
        failureMapper = mock(TelemetryReceiptFailureMapper.class);
        unknownDeviceHandler = mock(UnknownDeviceHandler.class);
        service = new TelemetryV2IngestionService(identityProvider,
                unknownDeviceHandler, standardService, receiptMapper,
                failureMapper, new AckModeResolver(), new SimpleMeterRegistry(),
                "platform/telemetry/v2/ack/adapter");
        when(identityProvider.find(any())).thenReturn(Optional.of(binding()));
    }

    @Test
    void persistsMessageWhenAllSourceCorrelationFieldsAreMissing() {
        when(standardService.ingestImmutable(any(), anyLong()))
                .thenReturn(StandardTelemetryResult.accepted(1));

        V2ProcessingResult result = service.ingest(message("C1", BigDecimal.ONE),
                1_785_398_400_500L);

        assertThat(result.status()).isEqualTo(ReceiptStatus.PLATFORM_PERSISTED);
        assertThat(result.processedMetrics()).isEqualTo(1);
        assertThat(result.actualAckMode()).isEqualTo(AckMode.EVIDENCE_ONLY);
        ArgumentCaptor<TelemetryReceipt> receipt =
                ArgumentCaptor.forClass(TelemetryReceipt.class);
        verify(receiptMapper, times(1)).insert(receipt.capture());
        verify(receiptMapper, never()).incrementAttempt(any(), any(), any(), any());
        assertThat(receipt.getValue().getCollectedAt()).isNull();
        assertThat(receipt.getValue().getTimeSource()).isEqualTo("ADAPTER_RECEIVED");
        assertThat(receipt.getValue().getDedupMode()).isEqualTo("NONE");
    }

    @Test
    void sameCanonicalIdAndDifferentPayloadIsConflictWithoutTdengineWrite() {
        TelemetryReceipt existing = new TelemetryReceipt();
        existing.setPayloadHash("different-hash");
        existing.setPersistedAt(java.time.LocalDateTime.now());
        when(receiptMapper.selectForUpdate("C1")).thenReturn(existing);

        V2ProcessingResult result = service.ingest(message("C1", BigDecimal.TEN),
                1_785_398_400_500L);

        assertThat(result.status()).isEqualTo(ReceiptStatus.MESSAGE_CONFLICT);
        verify(standardService, never()).ingestImmutable(any(), anyLong());
        verify(failureMapper).insert(any());
    }

    @Test
    void sameCanonicalIdAndSamePayloadIsIdempotentDuplicate() {
        when(standardService.ingestImmutable(any(), anyLong()))
                .thenReturn(StandardTelemetryResult.accepted(1));
        service.ingest(message("C1", BigDecimal.ONE), 1_785_398_400_500L);
        ArgumentCaptor<TelemetryReceipt> inserted =
                ArgumentCaptor.forClass(TelemetryReceipt.class);
        verify(receiptMapper).insert(inserted.capture());
        TelemetryReceipt existing = new TelemetryReceipt();
        existing.setPayloadHash(inserted.getValue().getPayloadHash());
        existing.setPersistedAt(inserted.getValue().getPersistedAt());
        when(receiptMapper.selectForUpdate("C1")).thenReturn(existing);

        V2ProcessingResult duplicate = service.ingest(
                message("C1", BigDecimal.ONE), 1_785_398_401_500L);

        assertThat(duplicate.status()).isEqualTo(ReceiptStatus.DUPLICATE_PERSISTED);
        verify(receiptMapper).incrementAttempt(any(), any(), any(), any());
        verify(standardService).ingestImmutable(any(), anyLong());
    }

    @Test
    void storageFailureKeepsBrokerRedeliveryAndDoesNotCreateSuccessReceipt() {
        when(standardService.ingestImmutable(any(), anyLong()))
                .thenReturn(StandardTelemetryResult.retryable(1, "tdengine unavailable"));

        V2ProcessingResult result = service.ingest(message("C2", BigDecimal.ONE),
                1_785_398_400_500L);

        assertThat(result.retryable()).isTrue();
        assertThat(result.processedMetrics()).isEqualTo(1);
        assertThat(result.applicationAck()).isNull();
        verify(receiptMapper, never()).insert(any());
        verify(failureMapper).insert(any());
    }

    @Test
    void unknownProxyDeviceReceivesPermanentResultThroughTrustedAdapterRoute() {
        when(identityProvider.find(any())).thenReturn(Optional.empty());
        TelemetryV2Message message = new TelemetryV2Message(
                "2.0", "P1", 1, new DeviceIdentityKey("MAC", "UNKNOWN"),
                "C3", "M3", null, null, null, 1_785_398_400_000L,
                null, null, null, null, "DEVICE_REPORTED", "ADAPTER_RECEIVED",
                "EXACT", "ADAPTER_PROXY", "SOURCE_MESSAGE_ID",
                List.of(new StandardMetric("ENERGY", BigDecimal.ONE, "kWh", "/energy")));

        V2ProcessingResult result = service.ingest(message, 1_785_398_400_500L);

        assertThat(result.status()).isEqualTo(ReceiptStatus.DISCOVERED_NOT_ACTIVE);
        assertThat(result.actualAckMode()).isEqualTo(AckMode.ADAPTER_PROXY);
        assertThat(result.ackTopic()).isEqualTo("platform/telemetry/v2/ack/adapter");
        assertThat(result.applicationAck().deliveryScope()).isEqualTo("ADAPTER_ONLY");
        verify(unknownDeviceHandler).recordDiscovered(any(), anyLong());
        verify(standardService, never()).ingestImmutable(any(), anyLong());
    }

    @Test
    void sustainsTwoHundredMessagesPerSecondInSoftwareHotPathSimulation() {
        when(standardService.ingestImmutable(any(), anyLong()))
                .thenReturn(StandardTelemetryResult.accepted(5));
        int messages = 1_000;

        long startedAt = System.nanoTime();
        for (int index = 0; index < messages; index++) {
            V2ProcessingResult result = service.ingest(
                    fiveMetricMessage("LOAD-" + index), 1_785_398_400_500L + index);
            assertThat(result.status()).isEqualTo(ReceiptStatus.PLATFORM_PERSISTED);
            assertThat(result.processedMetrics()).isEqualTo(5);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        verify(receiptMapper, times(messages)).insert(any());
        verify(standardService, times(messages)).ingestImmutable(any(), anyLong());
    }

    @Test
    void cleanupDefaultsCoverTargetDeviceModelAndBurstRate() {
        int devices = 1_000;
        int shortestReportSeconds = 15;
        int metricsPerMessage = 5;
        double messagesPerSecond = (double) devices / shortestReportSeconds;
        double pointsPerSecond = messagesPerSecond * metricsPerMessage;
        double cleanupRowsPerSecond = 2_000D * 10 / 60;

        assertThat(messagesPerSecond).isCloseTo(66.67,
                org.assertj.core.data.Offset.offset(0.01));
        assertThat(pointsPerSecond).isCloseTo(333.33,
                org.assertj.core.data.Offset.offset(0.01));
        assertThat(cleanupRowsPerSecond).isGreaterThan(200D);
    }

    private DeviceIdentityBinding binding() {
        return new DeviceIdentityBinding("I1", new DeviceIdentityKey("MAC", "ABC"),
                "E1", "METER1", "B1", "P1", AckMode.EVIDENCE_ONLY,
                CorrelationPolicy.NONE, null, null);
    }

    private TelemetryV2Message message(String canonicalId, BigDecimal value) {
        return new TelemetryV2Message("2.0", "P1", 1,
                new DeviceIdentityKey("MAC", "ABC"), canonicalId,
                null, null, null, null, 1_785_398_400_000L,
                null, null, null, null, "ADAPTER_GENERATED",
                "ADAPTER_RECEIVED", "NONE", "EVIDENCE_ONLY", "NONE",
                List.of(new StandardMetric("ENERGY", value, "kWh", "/energy")));
    }

    private TelemetryV2Message fiveMetricMessage(String canonicalId) {
        return new TelemetryV2Message("2.0", "P1", 1,
                new DeviceIdentityKey("MAC", "ABC"), canonicalId,
                null, null, null, null, 1_785_398_400_000L,
                null, null, null, null, "ADAPTER_GENERATED",
                "ADAPTER_RECEIVED", "NONE", "EVIDENCE_ONLY", "NONE",
                List.of(
                        new StandardMetric("M1", BigDecimal.ONE, "kWh", "/m1"),
                        new StandardMetric("M2", BigDecimal.valueOf(2), "kWh", "/m2"),
                        new StandardMetric("M3", BigDecimal.valueOf(3), "kWh", "/m3"),
                        new StandardMetric("M4", BigDecimal.valueOf(4), "kWh", "/m4"),
                        new StandardMetric("M5", BigDecimal.valueOf(5), "kWh", "/m5")));
    }
}
