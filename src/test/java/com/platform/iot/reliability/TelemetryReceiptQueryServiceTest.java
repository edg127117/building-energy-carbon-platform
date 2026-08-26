package com.platform.iot.reliability;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.reliability.mapper.MqttFailureAggregateMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptFailureMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptMapper;
import com.platform.iot.reliability.model.TelemetryReceipt;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelemetryReceiptQueryServiceTest {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-08-26T01:00:00Z");

    private TelemetryReceiptMapper receiptMapper;
    private TelemetryReceiptFailureMapper failureMapper;
    private BuildingScopeService buildingScopeService;
    private MqttFailureAggregateMapper mqttFailureMapper;
    private TelemetryReceiptQueryService service;

    @BeforeEach
    void setUp() {
        receiptMapper = mock(TelemetryReceiptMapper.class);
        failureMapper = mock(TelemetryReceiptFailureMapper.class);
        buildingScopeService = mock(BuildingScopeService.class);
        mqttFailureMapper = mock(MqttFailureAggregateMapper.class);
        service = new TelemetryReceiptQueryService(
                receiptMapper, failureMapper, buildingScopeService, mqttFailureMapper,
                Clock.fixed(NOW, PROJECT_ZONE));
        when(buildingScopeService.getAccessibleBuildingIds(any(), any()))
                .thenReturn(Set.of("B1"));
    }

    @Test
    void listDefaultsToLatestTwentyFourHoursAndReturnsCompatibilityEvidenceStates() {
        Page<TelemetryReceipt> page = new Page<>(1, 20);
        page.setRecords(List.of(receipt()));
        page.setTotal(1);
        when(receiptMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.list(1L, Set.of("BUILDING_OWNER"),
                null, null, null, null, null, 1, 20);

        assertThat(result.items()).singleElement().satisfies(view -> {
            assertThat(view.deviceToBrokerPuback()).isEqualTo("UNKNOWN");
            assertThat(view.adapterStandardPublishPuback()).isEqualTo("UNKNOWN");
            assertThat(view.platformInboundConsumerAck()).isEqualTo("NOT_TRACKED");
            assertThat(view.applicationAckPublishPuback()).isEqualTo("NOT_TRACKED");
            assertThat(view.applicationAckPublishedAtEpochMillis()).isNull();
        });
    }

    @Test
    void statisticsDeclaresHotWindowScope() {
        when(receiptMapper.selectCount(any())).thenReturn(0L);
        when(receiptMapper.selectObjs(any())).thenReturn(List.of(0L));
        when(failureMapper.selectCount(any())).thenReturn(0L);

        var statistics = service.statistics(
                1L, Set.of("BUILDING_OWNER"), null, null, null);

        assertThat(statistics.windowStartEpochMillis())
                .isEqualTo(NOW.minusSeconds(24 * 3600).toEpochMilli());
        assertThat(statistics.windowEndEpochMillis()).isEqualTo(NOW.toEpochMilli());
        assertThat(statistics.scope()).isEqualTo("HOT_RECEIPT_WINDOW");
    }

    @Test
    void rejectsSuccessfulReceiptWindowLongerThanTwentyFourHours() {
        long end = NOW.toEpochMilli();

        assertThatThrownBy(() -> service.list(
                1L, Set.of("BUILDING_OWNER"), null, null, null,
                end - 24 * 3600_000L - 1, end, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo("TELEMETRY_RECEIPT_WINDOW_TOO_LARGE"));
    }

    @Test
    void failureStatisticsUsesIndependentOneHundredEightyDayWindow() {
        when(failureMapper.selectCount(any())).thenReturn(0L);

        var statistics = service.failureStatistics(
                1L, Set.of("BUILDING_OWNER"), null, null, null);

        assertThat(statistics.windowStartEpochMillis())
                .isEqualTo(NOW.minusSeconds(180L * 24 * 3600).toEpochMilli());
        assertThat(statistics.windowEndEpochMillis()).isEqualTo(NOW.toEpochMilli());
        assertThat(statistics.scope()).isEqualTo("FAILURE_RETENTION_WINDOW");
    }

    @Test
    void rejectsFailureWindowLongerThanOneHundredEightyDays() {
        long end = NOW.toEpochMilli();

        assertThatThrownBy(() -> service.failureStatistics(
                1L, Set.of("BUILDING_OWNER"), null,
                end - 180L * 24 * 3600_000L - 1, end))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo("TELEMETRY_FAILURE_WINDOW_TOO_LARGE"));
    }

    private TelemetryReceipt receipt() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, PROJECT_ZONE);
        TelemetryReceipt receipt = new TelemetryReceipt();
        receipt.setCanonicalMessageId("C1");
        receipt.setBuildingId("B1");
        receipt.setEquipId("E1");
        receipt.setProfileCode("P1");
        receipt.setAdapterReceivedAt(now);
        receipt.setFirstPlatformReceivedAt(now);
        receipt.setLastPlatformReceivedAt(now);
        receipt.setPersistedAt(now);
        receipt.setIdSource("ADAPTER_GENERATED");
        receipt.setTimeSource("ADAPTER_RECEIVED");
        receipt.setDedupMode("NONE");
        receipt.setConfiguredAckMode("EVIDENCE_ONLY");
        receipt.setActualAckMode("EVIDENCE_ONLY");
        receipt.setReceiptStatus("PLATFORM_PERSISTED");
        receipt.setResultCode("PLATFORM_PERSISTED");
        receipt.setMetricCount(1);
        receipt.setAttemptCount(1);
        return receipt;
    }
}
