package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TypicalValueFillServiceTest {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long MINUTE = LocalDateTime.of(2026, 7, 29, 10, 0)
            .atZone(PROJECT_ZONE).toInstant().toEpochMilli();

    @Mock private TypicalValueConfigProvider configProvider;
    @Mock private FillTaskRepository fillTaskRepository;
    @Mock private HvacMinuteRepository minuteRepository;

    private TypicalValueFillService service;

    @BeforeEach
    void setUp() {
        FillTaskEvidenceCodec evidenceCodec = new FillTaskEvidenceCodec(
                new ObjectMapper().findAndRegisterModules());
        service = new TypicalValueFillService(
                configProvider, fillTaskRepository, evidenceCodec, minuteRepository);
    }

    @Test
    void approvedValueCreatesAuditableQualityTwoMinute() {
        BizPointTypicalValueConfig config = config("C1", 3, new BigDecimal("12.3400"));
        when(configProvider.findApproved("P1", MINUTE)).thenReturn(Optional.of(config));
        when(fillTaskRepository.getOrCreate(any())).thenAnswer(invocation -> {
            BizDataQualityFillTask task = invocation.getArgument(0);
            task.setTaskId("TASK-Q2");
            task.setApplyStatus(FillApplyStatus.WAITING);
            return task;
        });
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE, MinuteQualityWriteResult.Outcome.INSERTED,
                        null, null)));

        Optional<RawMinuteAggregate> result =
                service.fillMissing(point(), MINUTE, MINUTE + 45_000L);

        assertThat(result).isPresent();
        RawMinuteAggregate row = result.orElseThrow();
        assertThat(row.averageValue()).isEqualTo(12.34);
        assertThat(row.minimumValue()).isEqualTo(12.34);
        assertThat(row.maximumValue()).isEqualTo(12.34);
        assertThat(row.sampleCount()).isZero();
        assertThat(row.dataQuality()).isEqualTo(2);
        assertThat(row.firstReceivedTime()).isNull();
        assertThat(row.lastReceivedTime()).isNull();
        assertThat(row.qualityTaskId()).isEqualTo("TASK-Q2");

        ArgumentCaptor<BizDataQualityFillTask> taskCaptor =
                ArgumentCaptor.forClass(BizDataQualityFillTask.class);
        verify(fillTaskRepository).getOrCreate(taskCaptor.capture());
        BizDataQualityFillTask task = taskCaptor.getValue();
        assertThat(task.getIdempotencyKey()).startsWith("Q2:P1:C1:3:");
        assertThat(task.getDataQuality()).isEqualTo(2);
        assertThat(task.getTypicalConfigId()).isEqualTo("C1");
        assertThat(task.getTypicalConfigVersion()).isEqualTo(3);
        assertThat(task.getEvidenceJson())
                .contains("\"configId\":\"C1\"")
                .contains("\"algorithmVersion\":\"TYPICAL_V1\"")
                .contains("\"appliedSegments\":[]");
        verify(fillTaskRepository).markFirstApplied("TASK-Q2");
    }

    @Test
    void noApprovedValueSkipsTaskAndTdengineWrite() {
        when(configProvider.findApproved("P1", MINUTE)).thenReturn(Optional.empty());

        assertThat(service.fillMissing(point(), MINUTE, MINUTE + 45_000L)).isEmpty();

        verifyNoInteractions(fillTaskRepository, minuteRepository);
    }

    @Test
    void reusesOneTaskForSixtyMinutesButCreatesNewTaskForNewHourOrVersion() {
        AtomicInteger sequence = new AtomicInteger();
        BizPointTypicalValueConfig v1 = config("C1", 1, new BigDecimal("12.3"));
        BizPointTypicalValueConfig v2 = config("C1", 2, new BigDecimal("12.4"));
        when(configProvider.findApproved(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> invocation.<Long>getArgument(1) < MINUTE + 3_600_000L
                        ? Optional.of(v1) : Optional.of(v2));
        when(fillTaskRepository.getOrCreate(any())).thenAnswer(invocation -> {
            BizDataQualityFillTask task = invocation.getArgument(0);
            task.setTaskId("TASK-" + sequence.incrementAndGet());
            task.setApplyStatus(FillApplyStatus.WAITING);
            return task;
        });
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> {
                    RawMinuteAggregate row =
                            invocation.<List<RawMinuteAggregate>>getArgument(0).getFirst();
                    return List.of(new MinuteQualityWriteResult(
                            row.pointId(), row.minuteStart(),
                            MinuteQualityWriteResult.Outcome.INSERTED, null, null));
                });

        for (int index = 0; index < 60; index++) {
            assertThat(service.fillMissing(
                    point(), MINUTE + index * 60_000L, MINUTE + index * 60_000L + 45_000L))
                    .isPresent();
        }
        assertThat(service.fillMissing(
                point(), MINUTE + 3_600_000L, MINUTE + 3_645_000L)).isPresent();

        verify(fillTaskRepository, times(2)).getOrCreate(any());
        verify(fillTaskRepository, times(2)).markFirstApplied(any());
        verify(minuteRepository, times(61))
                .saveAllWithQualityPriority(anyList(), isNull());
    }

    @Test
    void approvedVersionChangeWithinSameHourUsesANewTask() {
        AtomicInteger sequence = new AtomicInteger();
        BizPointTypicalValueConfig v1 = config("C1", 1, new BigDecimal("12.3"));
        BizPointTypicalValueConfig v2 = config("C1", 2, new BigDecimal("12.4"));
        when(configProvider.findApproved("P1", MINUTE))
                .thenReturn(Optional.of(v1));
        when(configProvider.findApproved("P1", MINUTE + 60_000L))
                .thenReturn(Optional.of(v2));
        when(fillTaskRepository.getOrCreate(any())).thenAnswer(invocation -> {
            BizDataQualityFillTask task = invocation.getArgument(0);
            task.setTaskId("TASK-" + sequence.incrementAndGet());
            task.setApplyStatus(FillApplyStatus.WAITING);
            return task;
        });
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> {
                    RawMinuteAggregate row =
                            invocation.<List<RawMinuteAggregate>>getArgument(0).getFirst();
                    return List.of(new MinuteQualityWriteResult(
                            row.pointId(), row.minuteStart(),
                            MinuteQualityWriteResult.Outcome.INSERTED, null, null));
                });

        assertThat(service.fillMissing(point(), MINUTE, MINUTE + 45_000L)).isPresent();
        assertThat(service.fillMissing(
                point(), MINUTE + 60_000L, MINUTE + 105_000L)).isPresent();

        verify(fillTaskRepository, times(2)).getOrCreate(any());
        verify(fillTaskRepository, times(2)).markFirstApplied(any());
    }

    @Test
    void tdengineFailureIsRecordedAndGeneratedMinuteIsNotReturned() {
        BizPointTypicalValueConfig config = config("C1", 1, new BigDecimal("12.3"));
        when(configProvider.findApproved("P1", MINUTE)).thenReturn(Optional.of(config));
        when(fillTaskRepository.getOrCreate(any())).thenAnswer(invocation -> {
            BizDataQualityFillTask task = invocation.getArgument(0);
            task.setTaskId("TASK-FAIL");
            task.setApplyStatus(FillApplyStatus.WAITING);
            return task;
        });
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenThrow(new IllegalStateException("TDengine unavailable"));

        assertThat(service.fillMissing(point(), MINUTE, MINUTE + 45_000L)).isEmpty();

        verify(fillTaskRepository).recordFailure(
                "TASK-FAIL", MINUTE, "TDengine unavailable");
        verify(fillTaskRepository, never()).markFirstApplied(any());
    }

    @Test
    void invalidWriteResultCountIsRecordedAsTechnicalFailure() {
        BizPointTypicalValueConfig config = config(
                "C1", 1, new BigDecimal("12.3"));
        when(configProvider.findApproved("P1", MINUTE))
                .thenReturn(Optional.of(config));
        when(fillTaskRepository.getOrCreate(any())).thenAnswer(invocation -> {
            BizDataQualityFillTask task = invocation.getArgument(0);
            task.setTaskId("TASK-CONTRACT");
            task.setApplyStatus(FillApplyStatus.WAITING);
            return task;
        });
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of());

        assertThat(service.fillMissing(
                point(), MINUTE, MINUTE + 45_000L)).isEmpty();

        verify(fillTaskRepository).recordFailure(
                "TASK-CONTRACT", MINUTE, "质量2分钟写入结果数量与请求不一致");
    }

    @Test
    void rejectedTypicalWriteReturnsConcurrentHigherQualityMinute() {
        BizPointTypicalValueConfig config = config(
                "C1", 1, new BigDecimal("12.3"));
        when(configProvider.findApproved("P1", MINUTE))
                .thenReturn(Optional.of(config));
        when(fillTaskRepository.getOrCreate(any())).thenAnswer(invocation -> {
            BizDataQualityFillTask task = invocation.getArgument(0);
            task.setTaskId("TASK-Q2");
            task.setApplyStatus(FillApplyStatus.WAITING);
            return task;
        });
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.REJECTED_HIGHER_QUALITY,
                        0, null)));
        RawMinuteAggregate concurrentReal = new RawMinuteAggregate(
                "P1", "WCR1_TWin", "BLD001", "GROUP001", "E1", "WCR1",
                "WCR", "MAIN", "TWin", 1, MINUTE,
                13.0, 13.0, 13.0, 1, 0,
                MINUTE + 1_000L, MINUTE + 2_000L,
                MINUTE + 45_000L, null);
        when(minuteRepository.findPointMinute("P1", MINUTE))
                .thenReturn(Optional.of(concurrentReal));

        Optional<RawMinuteAggregate> resolved = service.fillMissing(
                point(), MINUTE, MINUTE + 45_000L);

        assertThat(resolved).contains(concurrentReal);
        verify(fillTaskRepository, never()).markFirstApplied(any());
    }

    private PointRuntimeConfig point() {
        return new PointRuntimeConfig(
                "P1", "WCR1_TWin", "冷冻水进水温度", "BLD001", "GROUP001",
                "E1", "WCR1", "WCR", "MAIN", "TWin", "ANALOG", "℃",
                "ONLINE", 1, BigDecimal.ZERO, new BigDecimal("50"));
    }

    private BizPointTypicalValueConfig config(
            String configId, int version, BigDecimal value) {
        BizPointTypicalValueConfig config = new BizPointTypicalValueConfig();
        config.setConfigId(configId);
        config.setPointId("P1");
        config.setBuildingId("BLD001");
        config.setTypicalValue(value);
        config.setUnit("℃");
        config.setVersion(version);
        config.setValidFrom(LocalDateTime.of(2026, 7, 29, 0, 0));
        config.setValidTo(LocalDateTime.of(2026, 7, 30, 0, 0));
        return config;
    }
}
