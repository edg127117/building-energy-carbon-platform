package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.DataQualityProperties;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.RecalculationChunkStats;
import com.platform.iot.dataquality.model.TypicalValueStatus;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualQualitySelectionServiceTest {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long BASE = LocalDateTime.of(2026, 7, 29, 10, 0)
            .atZone(PROJECT_ZONE)
            .toInstant()
            .toEpochMilli();
    private static final long FINALIZED_AT = BASE + 10 * MINUTE_MILLIS;

    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private FillTaskRepository fillTaskRepository;
    @Mock private TypicalValueConfigProvider typicalValueConfigProvider;

    private FillTaskEvidenceCodec evidenceCodec;
    private ManualQualitySelectionService service;

    @BeforeEach
    void setUp() {
        DataQualityProperties properties = new DataQualityProperties();
        properties.getInterpolation().setMaxGapMinutes(5);
        evidenceCodec = new FillTaskEvidenceCodec(
                new ObjectMapper().findAndRegisterModules());
        service = new ManualQualitySelectionService(
                properties,
                minuteRepository,
                fillTaskRepository,
                typicalValueConfigProvider,
                evidenceCodec,
                new LinearMinuteInterpolator());
    }

    @Test
    void selectsQ1ThenApprovedQ2WithoutDowngradingExistingRows() {
        PointRuntimeConfig p1 = point("P1");
        PointRuntimeConfig p2 = point("P2");
        BizDataQualityRecalcJob job = job(
                RecalculationJobType.VOID_AND_RECALCULATE,
                BASE,
                BASE + 5 * MINUTE_MILLIS,
                "OLD-TASK");
        RawMinuteAggregate p1Left = real(p1, BASE, 10.0);
        RawMinuteAggregate p1Right = real(
                p1, BASE + 2 * MINUTE_MILLIS, 30.0);
        RawMinuteAggregate existingP2Q0 = real(p2, BASE, 50.0);
        RawMinuteAggregate existingP2Q1 = generated(
                p2, BASE + MINUTE_MILLIS, 51.0, 1, "EXISTING-Q1");
        when(minuteRepository.findRange(
                Set.of("P1", "P2"), BASE, BASE + 5 * MINUTE_MILLIS))
                .thenReturn(List.of(
                        p1Left, p1Right, existingP2Q0, existingP2Q1));
        BizPointTypicalValueConfig approved = typicalConfig(
                "C1", "P1", 3, new BigDecimal("42.5"),
                BASE + 3 * MINUTE_MILLIS,
                BASE + 4 * MINUTE_MILLIS);
        stubNoOtherTypicalValues(approved);
        stubTasks();
        when(minuteRepository.saveAllWithQualityPriority(anyList(), any()))
                .thenAnswer(invocation -> inserted(invocation.getArgument(0)));

        ManualQualitySelectionService.ChunkSelection result =
                service.selectAndPersist(
                        job,
                        points(p1, p2),
                        BASE,
                        BASE + 5 * MINUTE_MILLIS,
                        BASE,
                        BASE + 5 * MINUTE_MILLIS,
                        FINALIZED_AT);

        assertThat(result.q1Rows())
                .extracting(RawMinuteAggregate::pointId,
                        RawMinuteAggregate::minuteStart,
                        RawMinuteAggregate::averageValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "P1", BASE + MINUTE_MILLIS, 20.0));
        assertThat(result.q2Rows())
                .extracting(RawMinuteAggregate::pointId,
                        RawMinuteAggregate::minuteStart,
                        RawMinuteAggregate::averageValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "P1", BASE + 3 * MINUTE_MILLIS, 42.5));
        assertThat(result.childTasks()).hasSize(2);
        assertThat(result.finalStats())
                .isEqualTo(new RecalculationChunkStats(3, 2, 1, 4));
        assertThat(result.finalStats().q0Count()
                + result.finalStats().q1Count()
                + result.finalStats().q2Count()
                + result.finalStats().missingCount())
                .isEqualTo(2 * 5);

        ArgumentCaptor<BizDataQualityFillTask> taskCaptor =
                ArgumentCaptor.forClass(BizDataQualityFillTask.class);
        verify(fillTaskRepository, times(2)).getOrCreate(taskCaptor.capture());
        BizDataQualityFillTask q1Task = taskCaptor.getAllValues().stream()
                .filter(task -> task.getDataQuality() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(q1Task.getIdempotencyKey())
                .isEqualTo("RECALC_Q1:JOB-1:P1:" + BASE + ":"
                        + (BASE + 2 * MINUTE_MILLIS) + ":LINEAR_V1")
                .isNotEqualTo(FillTaskIdempotency.q1(
                        "P1", BASE, BASE + 2 * MINUTE_MILLIS, "LINEAR_V1"));
        assertThat(q1Task.getStartMinute()).isEqualTo(local(BASE + MINUTE_MILLIS));
        assertThat(q1Task.getEndMinute()).isEqualTo(local(BASE + 2 * MINUTE_MILLIS));
        assertThat(q1Task.getRecalcJobId()).isEqualTo("JOB-1");
        assertThat(q1Task.getSupersedesTaskId()).isEqualTo("OLD-TASK");
        FillTaskEvidence.Interpolation interpolation =
                (FillTaskEvidence.Interpolation) evidenceCodec.decode(
                        FillSourceType.INTERPOLATION, q1Task.getEvidenceJson());
        assertThat(interpolation.leftValue()).isEqualTo(10.0);
        assertThat(interpolation.rightValue()).isEqualTo(30.0);

        BizDataQualityFillTask q2Task = taskCaptor.getAllValues().stream()
                .filter(task -> task.getDataQuality() == 2)
                .findFirst()
                .orElseThrow();
        assertThat(q2Task.getIdempotencyKey())
                .startsWith("RECALC_Q2:JOB-1:P1:C1:3:")
                .isNotEqualTo(FillTaskIdempotency.q2(
                        "P1", "C1", 3, BASE));
        assertThat(q2Task.getStartMinute())
                .isEqualTo(local(BASE + 3 * MINUTE_MILLIS));
        assertThat(q2Task.getEndMinute())
                .isEqualTo(local(BASE + 4 * MINUTE_MILLIS));
        assertThat(q2Task.getRecalcJobId()).isEqualTo("JOB-1");
        assertThat(q2Task.getSupersedesTaskId()).isEqualTo("OLD-TASK");
        FillTaskEvidence.Typical typical =
                (FillTaskEvidence.Typical) evidenceCodec.decode(
                        FillSourceType.TYPICAL_VALUE, q2Task.getEvidenceJson());
        assertThat(typical.configId()).isEqualTo("C1");
        assertThat(typical.version()).isEqualTo(3);
        assertThat(typical.value()).isEqualByComparingTo("42.5");

        ArgumentCaptor<List<RawMinuteAggregate>> rowsCaptor =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> supersedesCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(minuteRepository, times(2)).saveAllWithQualityPriority(
                rowsCaptor.capture(), supersedesCaptor.capture());
        assertThat(rowsCaptor.getAllValues())
                .allSatisfy(rows -> assertThat(rows)
                        .isSortedAccordingTo(java.util.Comparator
                                .comparingLong(RawMinuteAggregate::minuteStart)
                                .thenComparing(RawMinuteAggregate::pointId)));
        assertThat(supersedesCaptor.getAllValues()).containsOnly("OLD-TASK");
        assertThat(rowsCaptor.getAllValues().stream()
                .flatMap(List::stream)
                .map(row -> row.pointId() + ":" + row.minuteStart()))
                .doesNotContain(
                        "P2:" + BASE,
                        "P2:" + (BASE + MINUTE_MILLIS));

        InOrder order = inOrder(
                minuteRepository, fillTaskRepository, typicalValueConfigProvider);
        order.verify(minuteRepository).findRange(
                Set.of("P1", "P2"), BASE, BASE + 5 * MINUTE_MILLIS);
        order.verify(fillTaskRepository).getOrCreate(any());
        order.verify(minuteRepository).saveAllWithQualityPriority(
                anyList(), any());
        order.verify(typicalValueConfigProvider, atLeastOnce())
                .findApproved(any(), anyLong());
    }

    @Test
    void chunkBoundaryReusesFullGapTaskAndWritesOnlyTargetMinutes() {
        PointRuntimeConfig point = point("P1");
        BizDataQualityRecalcJob job = job(
                RecalculationJobType.RANGE_RECALCULATE,
                BASE,
                BASE + 4 * MINUTE_MILLIS,
                null);
        when(minuteRepository.findRange(
                Set.of("P1"), BASE, BASE + 4 * MINUTE_MILLIS))
                .thenReturn(List.of(
                        real(point, BASE, 0.0),
                        real(point, BASE + 3 * MINUTE_MILLIS, 30.0)));
        stubTasks();
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> inserted(invocation.getArgument(0)));

        ManualQualitySelectionService.ChunkSelection first =
                service.selectAndPersist(
                        job,
                        points(point),
                        BASE + MINUTE_MILLIS,
                        BASE + 2 * MINUTE_MILLIS,
                        BASE,
                        BASE + 4 * MINUTE_MILLIS,
                        FINALIZED_AT);
        ManualQualitySelectionService.ChunkSelection second =
                service.selectAndPersist(
                        job,
                        points(point),
                        BASE + 2 * MINUTE_MILLIS,
                        BASE + 3 * MINUTE_MILLIS,
                        BASE,
                        BASE + 4 * MINUTE_MILLIS,
                        FINALIZED_AT);

        assertThat(first.q1Rows())
                .extracting(RawMinuteAggregate::minuteStart)
                .containsExactly(BASE + MINUTE_MILLIS);
        assertThat(second.q1Rows())
                .extracting(RawMinuteAggregate::minuteStart)
                .containsExactly(BASE + 2 * MINUTE_MILLIS);
        ArgumentCaptor<BizDataQualityFillTask> taskCaptor =
                ArgumentCaptor.forClass(BizDataQualityFillTask.class);
        verify(fillTaskRepository, times(2)).getOrCreate(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues())
                .extracting(BizDataQualityFillTask::getIdempotencyKey)
                .containsOnly("RECALC_Q1:JOB-1:P1:" + BASE + ":"
                        + (BASE + 3 * MINUTE_MILLIS) + ":LINEAR_V1");
        assertThat(taskCaptor.getAllValues())
                .extracting(BizDataQualityFillTask::getStartMinute)
                .containsOnly(local(BASE + MINUTE_MILLIS));
        assertThat(taskCaptor.getAllValues())
                .extracting(BizDataQualityFillTask::getEndMinute)
                .containsOnly(local(BASE + 3 * MINUTE_MILLIS));
        assertThat(taskCaptor.getAllValues())
                .extracting(BizDataQualityFillTask::getMinuteCount)
                .containsOnly(2);
    }

    @Test
    void sameApprovedConfigAndHourUsesOneTaskForAllMissingMinutes() {
        PointRuntimeConfig point = point("P1");
        BizDataQualityRecalcJob job = job(
                RecalculationJobType.RANGE_RECALCULATE,
                BASE,
                BASE + 5 * MINUTE_MILLIS,
                null);
        RawMinuteAggregate existingQ1 = generated(
                point, BASE + 2 * MINUTE_MILLIS, 22.0, 1, "EXISTING-Q1");
        when(minuteRepository.findRange(
                Set.of("P1"), BASE, BASE + 5 * MINUTE_MILLIS))
                .thenReturn(List.of(existingQ1));
        BizPointTypicalValueConfig approved = typicalConfig(
                "C1", "P1", 2, new BigDecimal("15.0"),
                BASE,
                BASE + 5 * MINUTE_MILLIS);
        when(typicalValueConfigProvider.findApproved(any(), anyLong()))
                .thenReturn(Optional.of(approved));
        stubTasks();
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> inserted(invocation.getArgument(0)));

        ManualQualitySelectionService.ChunkSelection result =
                service.selectAndPersist(
                        job,
                        points(point),
                        BASE,
                        BASE + 5 * MINUTE_MILLIS,
                        BASE,
                        BASE + 5 * MINUTE_MILLIS,
                        FINALIZED_AT);

        assertThat(result.q1Rows()).isEmpty();
        assertThat(result.q2Rows())
                .extracting(RawMinuteAggregate::minuteStart)
                .containsExactly(
                        BASE,
                        BASE + MINUTE_MILLIS,
                        BASE + 3 * MINUTE_MILLIS,
                        BASE + 4 * MINUTE_MILLIS);
        assertThat(result.childTasks()).hasSize(1);
        assertThat(result.finalStats())
                .isEqualTo(new RecalculationChunkStats(0, 1, 4, 0));
        verify(fillTaskRepository, times(1)).getOrCreate(any());
        ArgumentCaptor<List<RawMinuteAggregate>> rowsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(minuteRepository).saveAllWithQualityPriority(
                rowsCaptor.capture(), isNull());
        assertThat(rowsCaptor.getValue())
                .extracting(RawMinuteAggregate::minuteStart)
                .doesNotContain(BASE + 2 * MINUTE_MILLIS);
    }

    @Test
    void q2TaskKeepsFullConfigHourAndJobIntersectionAcrossChunks() {
        PointRuntimeConfig point = point("P1");
        BizDataQualityRecalcJob job = job(
                RecalculationJobType.RANGE_RECALCULATE,
                BASE,
                BASE + 10 * MINUTE_MILLIS,
                null);
        when(minuteRepository.findRange(
                Set.of("P1"), BASE, BASE + 10 * MINUTE_MILLIS))
                .thenReturn(List.of());
        BizPointTypicalValueConfig approved = typicalConfig(
                "C1", "P1", 2, new BigDecimal("15.0"),
                BASE,
                BASE + 10 * MINUTE_MILLIS);
        when(typicalValueConfigProvider.findApproved(any(), anyLong()))
                .thenReturn(Optional.of(approved));
        stubTasks();
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> inserted(invocation.getArgument(0)));

        ManualQualitySelectionService.ChunkSelection result =
                service.selectAndPersist(
                        job,
                        points(point),
                        BASE + 4 * MINUTE_MILLIS,
                        BASE + 6 * MINUTE_MILLIS,
                        BASE,
                        BASE + 10 * MINUTE_MILLIS,
                        FINALIZED_AT);

        assertThat(result.q2Rows())
                .extracting(RawMinuteAggregate::minuteStart)
                .containsExactly(
                        BASE + 4 * MINUTE_MILLIS,
                        BASE + 5 * MINUTE_MILLIS);
        ArgumentCaptor<BizDataQualityFillTask> taskCaptor =
                ArgumentCaptor.forClass(BizDataQualityFillTask.class);
        verify(fillTaskRepository).getOrCreate(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStartMinute())
                .isEqualTo(local(BASE));
        assertThat(taskCaptor.getValue().getEndMinute())
                .isEqualTo(local(BASE + 10 * MINUTE_MILLIS));
        assertThat(taskCaptor.getValue().getMinuteCount()).isEqualTo(10);
        assertThat(result.finalStats())
                .isEqualTo(new RecalculationChunkStats(0, 0, 2, 0));
    }

    @Test
    void failedChildBatchRecordsEveryMinuteAndPropagatesFailure() {
        PointRuntimeConfig point = point("P1");
        BizDataQualityRecalcJob job = job(
                RecalculationJobType.RANGE_RECALCULATE,
                BASE,
                BASE + 4 * MINUTE_MILLIS,
                null);
        when(minuteRepository.findRange(
                Set.of("P1"), BASE, BASE + 4 * MINUTE_MILLIS))
                .thenReturn(List.of(
                        real(point, BASE, 0.0),
                        real(point, BASE + 3 * MINUTE_MILLIS, 30.0)));
        stubTasks();
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenThrow(new IllegalStateException("TDengine unavailable"));

        assertThatThrownBy(() -> service.selectAndPersist(
                job,
                points(point),
                BASE,
                BASE + 4 * MINUTE_MILLIS,
                BASE,
                BASE + 4 * MINUTE_MILLIS,
                FINALIZED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TDengine unavailable");

        verify(fillTaskRepository).recordFailure(
                "TASK-Q1", BASE + MINUTE_MILLIS, "TDengine unavailable");
        verify(fillTaskRepository).recordFailure(
                "TASK-Q1", BASE + 2 * MINUTE_MILLIS, "TDengine unavailable");
        verify(fillTaskRepository, never()).markFirstApplied(any());
    }

    @Test
    void recordsOnlyActualPreviousTaskReplacements() {
        PointRuntimeConfig point = point("P1");
        BizDataQualityRecalcJob job = job(
                RecalculationJobType.RANGE_RECALCULATE,
                BASE,
                BASE + 3 * MINUTE_MILLIS,
                null);
        when(minuteRepository.findRange(
                Set.of("P1"), BASE, BASE + 3 * MINUTE_MILLIS))
                .thenReturn(List.of(
                        real(point, BASE, 0.0),
                        generated(point, BASE + MINUTE_MILLIS,
                                11.0, 2, "OLD-Q2"),
                        real(point, BASE + 2 * MINUTE_MILLIS, 20.0)));
        stubTasks();
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> {
                    RawMinuteAggregate row =
                            invocation.<List<RawMinuteAggregate>>getArgument(0)
                                    .getFirst();
                    return List.of(new MinuteQualityWriteResult(
                            row.pointId(),
                            row.minuteStart(),
                            MinuteQualityWriteResult.Outcome.UPGRADED,
                            2,
                            "OLD-Q2"));
                });

        service.selectAndPersist(
                job,
                points(point),
                BASE,
                BASE + 3 * MINUTE_MILLIS,
                BASE,
                BASE + 3 * MINUTE_MILLIS,
                FINALIZED_AT);

        verify(fillTaskRepository).recordReplacements(Map.of("OLD-Q2", 1));
    }

    @Test
    void rejectedCandidateUsesPreviousQualityForFinalClassification() {
        PointRuntimeConfig point = point("P1");
        BizDataQualityRecalcJob job = job(
                RecalculationJobType.RANGE_RECALCULATE,
                BASE,
                BASE + 3 * MINUTE_MILLIS,
                null);
        when(minuteRepository.findRange(
                Set.of("P1"), BASE, BASE + 3 * MINUTE_MILLIS))
                .thenReturn(List.of(
                        real(point, BASE, 0.0),
                        real(point, BASE + 2 * MINUTE_MILLIS, 20.0)));
        stubTasks();
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> {
                    RawMinuteAggregate row =
                            invocation.<List<RawMinuteAggregate>>getArgument(0)
                                    .getFirst();
                    return List.of(new MinuteQualityWriteResult(
                            row.pointId(),
                            row.minuteStart(),
                            MinuteQualityWriteResult.Outcome.REJECTED_HIGHER_QUALITY,
                            0,
                            null));
                });

        ManualQualitySelectionService.ChunkSelection result =
                service.selectAndPersist(
                        job,
                        points(point),
                        BASE,
                        BASE + 3 * MINUTE_MILLIS,
                        BASE,
                        BASE + 3 * MINUTE_MILLIS,
                        FINALIZED_AT);

        assertThat(result.finalStats())
                .isEqualTo(new RecalculationChunkStats(3, 0, 0, 0));
        verify(typicalValueConfigProvider, never())
                .findApproved(any(), anyLong());
    }

    private void stubNoOtherTypicalValues(
            BizPointTypicalValueConfig approved) {
        when(typicalValueConfigProvider.findApproved(any(), anyLong()))
                .thenAnswer(invocation -> {
                    String pointId = invocation.getArgument(0);
                    long minute = invocation.getArgument(1);
                    if ("P1".equals(pointId)
                            && minute == BASE + 3 * MINUTE_MILLIS) {
                        return Optional.of(approved);
                    }
                    return Optional.empty();
                });
    }

    private void stubTasks() {
        AtomicInteger sequence = new AtomicInteger();
        when(fillTaskRepository.getOrCreate(any())).thenAnswer(invocation -> {
            BizDataQualityFillTask candidate = invocation.getArgument(0);
            if (candidate.getTaskId() == null) {
                candidate.setTaskId(candidate.getDataQuality() == 1
                        ? "TASK-Q1"
                        : "TASK-Q2-" + sequence.incrementAndGet());
            }
            candidate.setApplyStatus(FillApplyStatus.WAITING);
            return candidate;
        });
    }

    private List<MinuteQualityWriteResult> inserted(
            List<RawMinuteAggregate> rows) {
        List<MinuteQualityWriteResult> results = new ArrayList<>();
        for (RawMinuteAggregate row : rows) {
            results.add(new MinuteQualityWriteResult(
                    row.pointId(),
                    row.minuteStart(),
                    MinuteQualityWriteResult.Outcome.INSERTED,
                    null,
                    null));
        }
        return List.copyOf(results);
    }

    private BizDataQualityRecalcJob job(
            RecalculationJobType type,
            long from,
            long to,
            String supersedesTaskId) {
        BizDataQualityRecalcJob job = new BizDataQualityRecalcJob();
        job.setJobId("JOB-1");
        job.setJobType(type);
        job.setBuildingId("BLD001");
        job.setFromMinute(local(from));
        job.setToMinute(local(to));
        job.setSupersedesTaskId(supersedesTaskId);
        return job;
    }

    private Map<String, PointRuntimeConfig> points(
            PointRuntimeConfig... values) {
        Map<String, PointRuntimeConfig> points = new LinkedHashMap<>();
        for (PointRuntimeConfig point : values) {
            points.put(point.pointId(), point);
        }
        return points;
    }

    private PointRuntimeConfig point(String pointId) {
        return new PointRuntimeConfig(
                pointId,
                pointId,
                pointId,
                "BLD001",
                "GROUP001",
                "E1",
                "WCR1",
                "WCR",
                "MAIN",
                "TWin",
                "ANALOG",
                "℃",
                "ONLINE",
                1,
                BigDecimal.ZERO,
                new BigDecimal("100"));
    }

    private BizPointTypicalValueConfig typicalConfig(
            String configId,
            String pointId,
            int version,
            BigDecimal value,
            long validFrom,
            long validTo) {
        BizPointTypicalValueConfig config = new BizPointTypicalValueConfig();
        config.setConfigId(configId);
        config.setPointId(pointId);
        config.setBuildingId("BLD001");
        config.setTypicalValue(value);
        config.setUnit("℃");
        config.setVersion(version);
        config.setValidFrom(local(validFrom));
        config.setValidTo(local(validTo));
        config.setStatus(TypicalValueStatus.APPROVED);
        return config;
    }

    private RawMinuteAggregate real(
            PointRuntimeConfig point,
            long minute,
            double value) {
        return aggregate(point, minute, value, 1, 0, null);
    }

    private RawMinuteAggregate generated(
            PointRuntimeConfig point,
            long minute,
            double value,
            int quality,
            String taskId) {
        return aggregate(point, minute, value, 0, quality, taskId);
    }

    private RawMinuteAggregate aggregate(
            PointRuntimeConfig point,
            long minute,
            double value,
            int sampleCount,
            int quality,
            String taskId) {
        return new RawMinuteAggregate(
                point.pointId(),
                point.pointCode(),
                point.buildingId(),
                point.systemGroupId(),
                point.equipId(),
                point.equipCode(),
                point.familyCode(),
                point.componentCode(),
                point.suffixCode(),
                point.isForCalc(),
                minute,
                value,
                value,
                value,
                sampleCount,
                quality,
                quality == 0 ? minute + 1_000L : null,
                quality == 0 ? minute + 2_000L : null,
                FINALIZED_AT,
                taskId);
    }

    private LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }

    private static org.mockito.verification.VerificationMode atLeastOnce() {
        return org.mockito.Mockito.atLeastOnce();
    }
}
