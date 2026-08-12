package com.platform;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.IndicatorLatestCacheService;
import com.platform.config.DataQualityProperties;
import com.platform.config.FormulaProperties;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.hvac.service.BizDataPointService;
import com.platform.hvac.service.BuildingService;
import com.platform.iot.aggregation.HvacPointMinuteAggregator;
import com.platform.iot.aggregation.ManualRealMinuteAggregationService;
import com.platform.iot.dataquality.DataQualityFillTaskService;
import com.platform.iot.dataquality.DataQualityRecalculationJobService;
import com.platform.iot.dataquality.DataQualityRecalculationScheduler;
import com.platform.iot.dataquality.DataQualityRecalculationService;
import com.platform.iot.dataquality.DataQualityRecoveryService;
import com.platform.iot.dataquality.FillTaskEvidenceCodec;
import com.platform.iot.dataquality.FillTaskRepository;
import com.platform.iot.dataquality.LinearMinuteInterpolator;
import com.platform.iot.dataquality.ManualQualitySelectionService;
import com.platform.iot.dataquality.RecalculationJobRepository;
import com.platform.iot.dataquality.RecalculationVoidService;
import com.platform.iot.dataquality.TypicalValueConfigProvider;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.RecalculationChunkStats;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.TaskReconciliation;
import com.platform.iot.dataquality.model.TypicalValueStatus;
import com.platform.iot.dataquality.model.dto.DataQualityRecalculationDtos;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import com.platform.iot.formula.HvacFormulaEngine;
import com.platform.iot.formula.IndicatorConfigProvider;
import com.platform.iot.formula.IndicatorRealtimePublisher;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointAliasKey;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.iot.temporal.model.LateRawMinuteEvidence;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.PointMinuteKey;
import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.security.FormalRole;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SyncTaskExecutor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人工重算跨模块验收测试。
 *
 * <p>当前普通测试环境没有可复用的 TDengine 容器替身，本测试因此使用内存实现替换
 * MySQL/TDengine 仓储边界，但受理、调度领取、所有权作废、Q0 聚合、Q1/Q2 选择、
 * READY 发布和公式失效均运行生产服务。它验证跨服务顺序和恢复游标，不宣称覆盖
 * MySQL SQL、TDengine SQL、事务锁或真实消息中间件。</p>
 */
class DataQualityRecalculationAcceptanceTest {

    private static final long MINUTE = 60_000L;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long BASE = LocalDateTime.of(2026, 7, 20, 10, 0)
            .atZone(PROJECT_ZONE)
            .toInstant()
            .toEpochMilli();
    private static final String BUILDING = "BLD001";
    private static final String POINT = "P1";
    private static final String OLD_TASK = "OLD-Q2";
    private static final List<String> ADMIN =
            List.of(FormalRole.PLATFORM_ADMIN.name());

    @Test
    void completesVoidJobWithMixedQualityFormulaInvalidationAndQueryableChildren() {
        AcceptanceFixture fixture = new AcceptanceFixture();
        fixture.seedMixedVoidScenario();

        DataQualityRecalculationDtos.Response submitted =
                fixture.jobService.submitVoid(
                        7L, ADMIN, OLD_TASK, "修正错误典型值",
                        BASE + 10 * MINUTE);

        assertThat(submitted.status()).isEqualTo(RecalculationJobStatus.WAITING);
        assertThat(submitted.phase()).isEqualTo(RecalculationJobPhase.VOIDING);
        assertThat(fixture.jobs.findById(submitted.jobId()).orElseThrow()
                .getCursorMinute()).isEqualTo(local(BASE));

        fixture.scheduler.run();

        BizDataQualityRecalcJob completed =
                fixture.jobs.findById(submitted.jobId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(RecalculationJobStatus.SUCCEEDED);
        assertThat(completed.getPhase()).isEqualTo(RecalculationJobPhase.RECALCULATING);
        assertThat(completed.getCursorMinute()).isEqualTo(local(BASE + 6 * MINUTE));
        assertThat(completed)
                .extracting(
                        BizDataQualityRecalcJob::getQ0Count,
                        BizDataQualityRecalcJob::getQ1Count,
                        BizDataQualityRecalcJob::getQ2Count,
                        BizDataQualityRecalcJob::getMissingCount,
                        BizDataQualityRecalcJob::getVoidedCount,
                        BizDataQualityRecalcJob::getReplacedCount)
                .containsExactly(3, 1, 1, 1, 1, 1);

        // 只有仍归旧任务所有的分钟被删除；已经升级成 Q0 的分钟必须保留。
        assertThat(fixture.minutes.at(POINT, BASE).orElseThrow().dataQuality())
                .isZero();
        assertThat(fixture.minutes.at(POINT, BASE + MINUTE)
                .orElseThrow().dataQuality()).isZero();
        assertThat(fixture.minutes.at(POINT, BASE + 2 * MINUTE)
                .orElseThrow().dataQuality()).isEqualTo(1);
        assertThat(fixture.minutes.at(POINT, BASE + 3 * MINUTE)
                .orElseThrow().dataQuality()).isZero();
        assertThat(fixture.minutes.at(POINT, BASE + 4 * MINUTE)
                .orElseThrow().dataQuality()).isEqualTo(2);
        assertThat(fixture.minutes.at(POINT, BASE + 5 * MINUTE)).isEmpty();
        assertThat(fixture.oldTask().getApplyStatus()).isEqualTo(FillApplyStatus.VOIDED);
        assertThat(fixture.oldTask().getVoidedCount()).isEqualTo(1);
        assertThat(fixture.oldTask().getReplacedCount()).isEqualTo(1);

        assertThat(fixture.readyEvents)
                .hasSize(6)
                .allSatisfy(event -> assertThat(event.source().name())
                        .isEqualTo("MANUAL_RECALCULATION"));
        assertThat(fixture.readyEvents.getLast().minuteStart())
                .isEqualTo(BASE + 5 * MINUTE);
        assertThat(fixture.readyEvents.getLast().aggregates()).isEmpty();
        verify(fixture.indicatorRepository).deleteSuccesses(
                Set.of(new IndicatorMinuteKey(
                        "PUMP-EFF-1", BASE + 5 * MINUTE)));

        DataQualityRecalculationDtos.Detail detail =
                fixture.jobService.detail(ADMIN, submitted.jobId());
        assertThat(detail.job().status()).isEqualTo(RecalculationJobStatus.SUCCEEDED);
        assertThat(detail.childTasks())
                .hasSize(2)
                .allSatisfy(child -> {
                    assertThat(child).isNotNull();
                    assertThat(child.applyStatus()).isEqualTo(FillApplyStatus.APPLIED);
                });
        assertThat(fixture.fills.findByRecalculationJobId(submitted.jobId()))
                .extracting(
                        BizDataQualityFillTask::getDataQuality,
                        BizDataQualityFillTask::getRecalcJobId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, submitted.jobId()),
                        org.assertj.core.groups.Tuple.tuple(2, submitted.jobId()));
        assertThat(fixture.raw.includeLateArguments).containsExactly(true);
    }

    @ParameterizedTest
    @ValueSource(ints = {61, 120})
    void splitsLongRangeIntoExactlyTwoChunksWithoutRepeatingBoundaryMinute(
            int rangeMinutes) {
        AcceptanceFixture fixture = new AcceptanceFixture();
        DataQualityRecalculationDtos.Response submitted =
                fixture.submitEmptyRange(rangeMinutes);

        fixture.scheduler.run();
        assertThat(fixture.jobs.findById(submitted.jobId()).orElseThrow()
                .getStatus()).isEqualTo(RecalculationJobStatus.WAITING);
        fixture.scheduler.run();

        BizDataQualityRecalcJob completed =
                fixture.jobs.findById(submitted.jobId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(RecalculationJobStatus.SUCCEEDED);
        assertThat(completed.getCursorMinute())
                .isEqualTo(local(BASE + rangeMinutes * MINUTE));
        assertThat(completed.getMissingCount()).isEqualTo(rangeMinutes);
        assertThat(fixture.raw.windows).hasSize(2);
        assertThat(fixture.raw.includeLateArguments).containsExactly(true, true);

        List<Long> targetMinutes = fixture.readyEvents.stream()
                .map(HvacMinuteQualityReadyEvent::minuteStart)
                .toList();
        assertThat(targetMinutes).hasSize(rangeMinutes);
        assertThat(new LinkedHashSet<>(targetMinutes)).hasSize(rangeMinutes);
        assertThat(targetMinutes.stream()
                .filter(value -> value == BASE + 60 * MINUTE)
                .count()).isEqualTo(1);
        assertThat(targetMinutes).containsExactlyElementsOf(
                java.util.stream.LongStream.range(0, rangeMinutes)
                        .map(index -> BASE + index * MINUTE)
                        .boxed()
                        .toList());
    }

    private static LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }

    /**
     * 组装真实生产服务并把外部持久化替换为可观察的内存边界。
     */
    private static final class AcceptanceFixture {

        private final ObjectMapper objectMapper =
                new ObjectMapper().findAndRegisterModules();
        private final DataQualityProperties properties = properties();
        private final InMemoryJobRepository jobs = new InMemoryJobRepository();
        private final InMemoryFillTaskRepository fills =
                new InMemoryFillTaskRepository();
        private final InMemoryMinuteRepository minutes =
                new InMemoryMinuteRepository();
        private final InMemoryRawRepository raw = new InMemoryRawRepository();
        private final PointRuntimeConfig point = point();
        private final List<HvacMinuteQualityReadyEvent> readyEvents =
                new ArrayList<>();
        private final IndicatorMinuteRepository indicatorRepository =
                mock(IndicatorMinuteRepository.class);
        private final TypicalValueConfigProvider typicalValues =
                new InMemoryTypicalValueProvider();
        private final DataQualityRecalculationJobService jobService;
        private final DataQualityRecalculationScheduler scheduler;

        private AcceptanceFixture() {
            DataPointConfigProvider pointProvider =
                    new DataPointConfigProvider() {
                        @Override
                        public Optional<PointRuntimeConfig> find(
                                PointAliasKey aliasKey) {
                            return Optional.empty();
                        }

                        @Override
                        public Optional<PointRuntimeConfig> findByPointId(
                                String pointId) {
                            return POINT.equals(pointId)
                                    ? Optional.of(point)
                                    : Optional.empty();
                        }

                        @Override
                        public Collection<PointRuntimeConfig> findAll() {
                            return List.of(point);
                        }
                    };
            ManualRealMinuteAggregationService aggregator =
                    new ManualRealMinuteAggregationService(
                            raw, pointProvider, new HvacPointMinuteAggregator());
            FillTaskEvidenceCodec evidenceCodec =
                    new FillTaskEvidenceCodec(objectMapper);
            ManualQualitySelectionService selection =
                    new ManualQualitySelectionService(
                            properties, minutes, fills, typicalValues,
                            evidenceCodec, new LinearMinuteInterpolator());

            IndicatorConfigProvider indicatorProvider =
                    mock(IndicatorConfigProvider.class);
            when(indicatorProvider.findAllActive()).thenReturn(List.of(indicator()));
            HvacFormulaEngine formulaEngine = new HvacFormulaEngine(
                    indicatorProvider,
                    minutes,
                    indicatorRepository,
                    mock(IndicatorLatestCacheService.class),
                    mock(IndicatorRealtimePublisher.class),
                    pointProvider,
                    new FormulaProperties());
            ApplicationEventPublisher publisher = event -> {
                if (event instanceof HvacMinuteQualityReadyEvent ready) {
                    readyEvents.add(ready);
                    formulaEngine.onMinuteQualityReady(ready);
                }
            };

            DataQualityRecalculationService recalculation =
                    new DataQualityRecalculationService(
                            jobs, aggregator, selection, minutes, pointProvider,
                            publisher, properties, objectMapper);
            RecalculationVoidService voidService =
                    new RecalculationVoidService(
                            minutes, fills, jobs, objectMapper);
            scheduler = new DataQualityRecalculationScheduler(
                    jobs, fills, voidService, recalculation, properties,
                    new SyncTaskExecutor());

            BuildingService buildingService = mock(BuildingService.class);
            BizDataPointService pointService = mock(BizDataPointService.class);
            when(pointService.listByIds(anyList()))
                    .thenReturn(List.of(dataPoint()));
            DataQualityFillTaskService fillTaskService =
                    new DataQualityFillTaskService(
                            fills,
                            evidenceCodec,
                            mock(BuildingScopeService.class),
                            mock(DataQualityRecoveryService.class));
            jobService = new DataQualityRecalculationJobService(
                    jobs, fills, buildingService, pointService,
                    fillTaskService, objectMapper);
        }

        private void seedMixedVoidScenario() {
            BizDataQualityFillTask old = oldTaskEntity();
            fills.tasksById.put(old.getTaskId(), old);
            fills.taskIdsByKey.put(old.getIdempotencyKey(), old.getTaskId());
            minutes.put(generated(BASE, 2, OLD_TASK, 12.0));
            minutes.put(real(BASE + MINUTE, 20.0));
            raw.events.add(event(BASE + 10_000L, 10.0, false));
            raw.events.add(event(BASE + 3 * MINUTE + 10_000L, 40.0, true));
            ((InMemoryTypicalValueProvider) typicalValues).configs.add(
                    typical(BASE + 4 * MINUTE, BASE + 5 * MINUTE));
        }

        private DataQualityRecalculationDtos.Response submitEmptyRange(
                int rangeMinutes) {
            return jobService.submitRange(
                    7L,
                    ADMIN,
                    new DataQualityRecalculationDtos.RecalculateRequest(
                            BUILDING,
                            List.of(POINT),
                            BASE,
                            BASE + rangeMinutes * MINUTE,
                            "验收 " + rangeMinutes + " 分钟分块"),
                    BASE + (rangeMinutes + 10L) * MINUTE);
        }

        private BizDataQualityFillTask oldTask() {
            return fills.findById(OLD_TASK).orElseThrow();
        }
    }

    private static DataQualityProperties properties() {
        DataQualityProperties result = new DataQualityProperties();
        result.getInterpolation().setMaxGapMinutes(5);
        result.setRecalculationStaleMs(60_000L);
        return result;
    }

    private static PointRuntimeConfig point() {
        return new PointRuntimeConfig(
                POINT, "PUMP-PPE", "水泵输入功率", BUILDING,
                "GROUP001", "PUMP001", "PUMP001",
                "PUMP", "Pc", "PPE", "ANALOG", "kW",
                "ONLINE", 1, BigDecimal.ZERO, new BigDecimal("1000"));
    }

    private static BizDataPoint dataPoint() {
        BizDataPoint result = new BizDataPoint();
        result.setPointId(POINT);
        result.setBuildingId(BUILDING);
        return result;
    }

    private static BizIndicator indicator() {
        BizIndicator result = new BizIndicator();
        result.setIndicatorId("PUMP-EFF-1");
        result.setIndicatorCode("PUMP_EFF");
        result.setBuildingId(BUILDING);
        result.setSystemGroupId("GROUP001");
        result.setEquipId("PUMP001");
        result.setStatus(1);
        return result;
    }

    private static BizDataQualityFillTask oldTaskEntity() {
        BizDataQualityFillTask result = new BizDataQualityFillTask();
        result.setTaskId(OLD_TASK);
        result.setIdempotencyKey("Q2:OLD");
        result.setBuildingId(BUILDING);
        result.setPointId(POINT);
        result.setStartMinute(local(BASE));
        result.setEndMinute(local(BASE + 6 * MINUTE));
        result.setMinuteCount(6);
        result.setDataQuality(2);
        result.setSourceType(FillSourceType.TYPICAL_VALUE);
        result.setAlgorithmVersion("TYPICAL_V1");
        result.setEvidenceJson("{}");
        result.setApplyStatus(FillApplyStatus.APPLIED);
        result.setAppliedCount(1);
        result.setFailedCount(4);
        result.setReplacedCount(1);
        result.setVoidedCount(0);
        result.setRetryCount(0);
        result.setGeneratedAt(local(BASE + 7 * MINUTE));
        result.setCreateTime(local(BASE + 7 * MINUTE));
        result.setUpdateTime(local(BASE + 7 * MINUTE));
        return result;
    }

    private static BizPointTypicalValueConfig typical(long from, long to) {
        BizPointTypicalValueConfig result = new BizPointTypicalValueConfig();
        result.setConfigId("TYPICAL-1");
        result.setPointId(POINT);
        result.setBuildingId(BUILDING);
        result.setTypicalValue(new BigDecimal("55"));
        result.setUnit("kW");
        result.setSourceDescription("验收配置");
        result.setReason("补齐长期缺失");
        result.setValidFrom(local(from));
        result.setValidTo(local(to));
        result.setStatus(TypicalValueStatus.APPROVED);
        result.setVersion(1);
        result.setCreatedBy(7L);
        result.setReviewerId(7L);
        result.setReviewedAt(local(from - MINUTE));
        result.setCreateTime(local(from - MINUTE));
        result.setUpdateTime(local(from - MINUTE));
        return result;
    }

    private static RawTelemetryEvent event(
            long eventTime, double value, boolean late) {
        return new RawTelemetryEvent(
                POINT, "PUMP-PPE", "MQTT", "PPE", "PUMP001",
                BUILDING, "GROUP001", "PUMP001", "PUMP001",
                "PUMP", "Pc", "PPE", value, eventTime,
                eventTime + 100L, 0, 1, late);
    }

    private static RawMinuteAggregate real(long minute, double value) {
        return aggregate(minute, value, 0, null);
    }

    private static RawMinuteAggregate generated(
            long minute, int quality, String taskId, double value) {
        return aggregate(minute, value, quality, taskId);
    }

    private static RawMinuteAggregate aggregate(
            long minute, double value, int quality, String taskId) {
        return new RawMinuteAggregate(
                POINT, "PUMP-PPE", BUILDING, "GROUP001",
                "PUMP001", "PUMP001", "PUMP", "Pc", "PPE", 1,
                minute, value, value, value, quality == 0 ? 1 : 0,
                quality, minute, minute, minute + MINUTE, taskId);
    }

    private record MinuteKey(String pointId, long minute) {
    }

    /**
     * 以 TDengine 当前行语义实现质量优先写和所有权条件删除。
     */
    private static final class InMemoryMinuteRepository
            implements HvacMinuteRepository {

        private final Map<MinuteKey, RawMinuteAggregate> rows =
                new LinkedHashMap<>();

        private void put(RawMinuteAggregate row) {
            rows.put(new MinuteKey(row.pointId(), row.minuteStart()), row);
        }

        private Optional<RawMinuteAggregate> at(String pointId, long minute) {
            return Optional.ofNullable(rows.get(new MinuteKey(pointId, minute)));
        }

        @Override
        public List<MinuteQualityWriteResult> saveAllWithQualityPriority(
                List<RawMinuteAggregate> aggregates,
                String supersedesTaskId) {
            List<MinuteQualityWriteResult> results = new ArrayList<>();
            for (RawMinuteAggregate incoming : aggregates) {
                MinuteKey key = new MinuteKey(
                        incoming.pointId(), incoming.minuteStart());
                RawMinuteAggregate current = rows.get(key);
                MinuteQualityWriteResult.Outcome outcome;
                if (current == null) {
                    outcome = MinuteQualityWriteResult.Outcome.INSERTED;
                    rows.put(key, incoming);
                } else if (incoming.dataQuality() == 0
                        && current.dataQuality() == 0) {
                    outcome = MinuteQualityWriteResult.Outcome.UPDATED_REAL;
                    rows.put(key, incoming);
                } else if (incoming.dataQuality() < current.dataQuality()) {
                    outcome = MinuteQualityWriteResult.Outcome.UPGRADED;
                    rows.put(key, incoming);
                } else if (incoming.dataQuality() == current.dataQuality()
                        && java.util.Objects.equals(
                        incoming.qualityTaskId(), current.qualityTaskId())) {
                    outcome = MinuteQualityWriteResult.Outcome.IDEMPOTENT;
                } else if (incoming.dataQuality() == current.dataQuality()
                        && supersedesTaskId != null
                        && supersedesTaskId.equals(current.qualityTaskId())) {
                    outcome = MinuteQualityWriteResult.Outcome.UPGRADED;
                    rows.put(key, incoming);
                } else if (incoming.dataQuality() > current.dataQuality()) {
                    outcome = MinuteQualityWriteResult.Outcome.REJECTED_HIGHER_QUALITY;
                } else {
                    outcome = MinuteQualityWriteResult.Outcome.REJECTED_SAME_QUALITY;
                }
                results.add(new MinuteQualityWriteResult(
                        incoming.pointId(),
                        incoming.minuteStart(),
                        outcome,
                        current == null ? null : current.dataQuality(),
                        current == null ? null : current.qualityTaskId()));
            }
            return List.copyOf(results);
        }

        @Override
        public Optional<RawMinuteAggregate> findPointMinute(
                String pointId, long minuteStart) {
            return at(pointId, minuteStart);
        }

        @Override
        public List<RawMinuteAggregate> findRange(
                Set<String> pointIds, long fromInclusive, long toExclusive) {
            return rows.values().stream()
                    .filter(row -> pointIds.contains(row.pointId()))
                    .filter(row -> row.minuteStart() >= fromInclusive)
                    .filter(row -> row.minuteStart() < toExclusive)
                    .sorted(Comparator
                            .comparingLong(RawMinuteAggregate::minuteStart)
                            .thenComparing(RawMinuteAggregate::pointId))
                    .toList();
        }

        @Override
        public List<RawMinuteAggregate> findByQualityTaskId(String taskId) {
            return rows.values().stream()
                    .filter(row -> taskId.equals(row.qualityTaskId()))
                    .toList();
        }

        @Override
        public List<RawMinuteAggregate> findByQualityTaskId(
                String taskId,
                String pointId,
                long fromInclusive,
                long toExclusive,
                int limit) {
            return findRange(Set.of(pointId), fromInclusive, toExclusive).stream()
                    .filter(row -> taskId.equals(row.qualityTaskId()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<RawMinuteAggregate> findLateRealMinutes(
                long fromInclusive,
                long toExclusive,
                Long afterMinuteStart,
                String afterPointId,
                int normalFinalizationDelaySeconds,
                int limit) {
            return List.of();
        }

        @Override
        public boolean deleteIfOwnedByTask(
                String pointId, long minuteStart, String taskId) {
            MinuteKey key = new MinuteKey(pointId, minuteStart);
            RawMinuteAggregate current = rows.get(key);
            if (current == null || !taskId.equals(current.qualityTaskId())) {
                return false;
            }
            rows.remove(key);
            return true;
        }

        @Override
        public Set<String> findExistingPointIds(long minuteStart) {
            return rows.values().stream()
                    .filter(row -> row.minuteStart() == minuteStart)
                    .map(RawMinuteAggregate::pointId)
                    .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public List<RawMinuteAggregate> findByMinute(
                long minuteStart, Set<String> buildingIds) {
            return rows.values().stream()
                    .filter(row -> row.minuteStart() == minuteStart)
                    .filter(row -> buildingIds.contains(row.buildingId()))
                    .sorted(Comparator.comparing(RawMinuteAggregate::pointId))
                    .toList();
        }

        @Override
        public List<HvacMinuteQueryRow> findLatestByPointIds(
                List<String> pointIds) {
            return List.of();
        }

        @Override
        public List<HvacMinuteQueryRow> findHistory(
                List<String> pointIds,
                long fromInclusive,
                long toExclusive,
                int resolutionMinutes) {
            return List.of();
        }
    }

    /**
     * 记录每个历史查询窗口及 includeLate 参数，验收人工聚合必须包含迟到真实证据。
     */
    private static final class InMemoryRawRepository
            implements HvacRawEventRepository {

        private final List<RawTelemetryEvent> events = new ArrayList<>();
        private final List<long[]> windows = new ArrayList<>();
        private final List<Boolean> includeLateArguments = new ArrayList<>();

        @Override
        public RawEventWriteResult upsert(RawTelemetryEvent event) {
            events.add(event);
            return RawEventWriteResult.INSERTED;
        }

        @Override
        public List<RawTelemetryEvent> findWindow(
                long startInclusive,
                long endExclusive,
                boolean includeLate) {
            windows.add(new long[]{startInclusive, endExclusive});
            includeLateArguments.add(includeLate);
            return events.stream()
                    .filter(event -> event.eventTime() >= startInclusive)
                    .filter(event -> event.eventTime() < endExclusive)
                    .filter(event -> includeLate || !event.late())
                    .toList();
        }

        @Override
        public List<LateRawMinuteEvidence> findLateMinuteEvidence(
                long startInclusive,
                long endExclusive,
                Long afterMinuteStart,
                String afterPointId,
                int limit) {
            return List.of();
        }

        @Override
        public Set<PointMinuteKey> findLateEvidenceKeys(
                Collection<PointMinuteKey> candidates) {
            return Set.of();
        }

        @Override
        public void deleteBefore(long eventTimeExclusive) {
            events.removeIf(event -> event.eventTime() < eventTimeExclusive);
        }
    }

    private static final class InMemoryTypicalValueProvider
            implements TypicalValueConfigProvider {

        private final List<BizPointTypicalValueConfig> configs =
                new ArrayList<>();

        @Override
        public Optional<BizPointTypicalValueConfig> findApproved(
                String pointId, long minuteStart) {
            return configs.stream()
                    .filter(config -> pointId.equals(config.getPointId()))
                    .filter(config -> config.getStatus() == TypicalValueStatus.APPROVED)
                    .filter(config -> !local(minuteStart)
                            .isBefore(config.getValidFrom()))
                    .filter(config -> config.getValidTo() == null
                            || local(minuteStart).isBefore(config.getValidTo()))
                    .findFirst();
        }

        @Override
        public List<BizPointTypicalValueConfig> snapshot() {
            return List.copyOf(configs);
        }

        @Override
        public void refresh() {
            // 验收配置在测试开始前固定，不需要模拟 MySQL 热刷新。
        }
    }

    /**
     * 模拟 MySQL 条件领取、作废阶段收口和游标原子推进。
     */
    private static final class InMemoryJobRepository
            implements RecalculationJobRepository {

        private final Map<String, BizDataQualityRecalcJob> byId =
                new LinkedHashMap<>();
        private final Map<String, String> idByKey = new LinkedHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public BizDataQualityRecalcJob insert(BizDataQualityRecalcJob candidate) {
            candidate.setJobId("JOB-" + sequence.incrementAndGet());
            candidate.setQ0Count(0);
            candidate.setQ1Count(0);
            candidate.setQ2Count(0);
            candidate.setMissingCount(0);
            candidate.setVoidedCount(0);
            candidate.setReplacedCount(0);
            byId.put(candidate.getJobId(), candidate);
            idByKey.put(candidate.getIdempotencyKey(), candidate.getJobId());
            return candidate;
        }

        @Override
        public Optional<BizDataQualityRecalcJob> findById(String jobId) {
            return Optional.ofNullable(byId.get(jobId));
        }

        @Override
        public Optional<BizDataQualityRecalcJob> findByIdempotencyKey(
                String key) {
            return Optional.ofNullable(idByKey.get(key)).map(byId::get);
        }

        @Override
        public Optional<BizDataQualityRecalcJob> findByIdempotencyKeyForUpdate(
                String key) {
            return findByIdempotencyKey(key);
        }

        @Override
        public List<BizDataQualityRecalcJob> findOverlappingForUpdate(
                String buildingId,
                LocalDateTime from,
                LocalDateTime to) {
            return byId.values().stream()
                    .filter(job -> buildingId.equals(job.getBuildingId()))
                    .filter(job -> job.getStatus() == RecalculationJobStatus.WAITING
                            || job.getStatus() == RecalculationJobStatus.RUNNING)
                    .filter(job -> job.getToMinute().isAfter(from)
                            && job.getFromMinute().isBefore(to))
                    .toList();
        }

        @Override
        public IPage<BizDataQualityRecalcJob> findPage(
                int pageNum,
                int pageSize,
                String buildingId,
                RecalculationJobType type,
                RecalculationJobStatus status,
                LocalDateTime from,
                LocalDateTime to) {
            List<BizDataQualityRecalcJob> records = byId.values().stream()
                    .filter(job -> buildingId == null
                            || buildingId.equals(job.getBuildingId()))
                    .filter(job -> type == null || type == job.getJobType())
                    .filter(job -> status == null || status == job.getStatus())
                    .toList();
            return new Page<BizDataQualityRecalcJob>(pageNum, pageSize)
                    .setRecords(records)
                    .setTotal(records.size());
        }

        @Override
        public List<BizDataQualityRecalcJob> findClaimable(
                LocalDateTime staleBefore, int limit) {
            return byId.values().stream()
                    .filter(job -> job.getStatus() == RecalculationJobStatus.WAITING
                            || (job.getStatus() == RecalculationJobStatus.RUNNING
                            && job.getUpdateTime().isBefore(staleBefore)))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean claim(
                String jobId,
                LocalDateTime staleBefore,
                LocalDateTime now) {
            BizDataQualityRecalcJob job = byId.get(jobId);
            if (job == null
                    || (job.getStatus() != RecalculationJobStatus.WAITING
                    && !(job.getStatus() == RecalculationJobStatus.RUNNING
                    && job.getUpdateTime().isBefore(staleBefore)))) {
                return false;
            }
            job.setStatus(RecalculationJobStatus.RUNNING);
            if (job.getStartedAt() == null) {
                job.setStartedAt(now);
            }
            job.setUpdateTime(now);
            return true;
        }

        @Override
        public boolean releaseClaim(
                String jobId,
                LocalDateTime expectedCursor,
                LocalDateTime claimedAt) {
            BizDataQualityRecalcJob job = byId.get(jobId);
            if (job == null
                    || job.getStatus() != RecalculationJobStatus.RUNNING
                    || !expectedCursor.equals(job.getCursorMinute())
                    || !claimedAt.equals(job.getUpdateTime())) {
                return false;
            }
            job.setStatus(RecalculationJobStatus.WAITING);
            job.setUpdateTime(claimedAt.plusNanos(1_000_000L));
            return true;
        }

        @Override
        public void resumeFailed(String jobId) {
            BizDataQualityRecalcJob job = byId.get(jobId);
            job.setStatus(RecalculationJobStatus.WAITING);
            job.setLastError(null);
            job.setFinishedAt(null);
        }

        @Override
        public void freezeVoidTargets(
                String jobId, String targetMinutesJson) {
            BizDataQualityRecalcJob job = byId.get(jobId);
            if (job.getVoidTargetMinutesJson() == null) {
                job.setVoidTargetMinutesJson(targetMinutesJson);
            }
        }

        @Override
        public void completeVoid(
                String jobId, int voidedCount, int replacedCount) {
            BizDataQualityRecalcJob job = byId.get(jobId);
            job.setVoidedCount(voidedCount);
            job.setReplacedCount(replacedCount);
            job.setPhase(RecalculationJobPhase.RECALCULATING);
        }

        @Override
        public void advanceChunk(
                String jobId,
                LocalDateTime expectedCursor,
                LocalDateTime nextCursor,
                RecalculationChunkStats stats,
                boolean finished,
                LocalDateTime at) {
            BizDataQualityRecalcJob job = byId.get(jobId);
            if (job.getStatus() != RecalculationJobStatus.RUNNING
                    || !expectedCursor.equals(job.getCursorMinute())) {
                throw new IllegalStateException("游标条件更新失败");
            }
            job.setQ0Count(job.getQ0Count() + stats.q0Count());
            job.setQ1Count(job.getQ1Count() + stats.q1Count());
            job.setQ2Count(job.getQ2Count() + stats.q2Count());
            job.setMissingCount(
                    job.getMissingCount() + stats.missingCount());
            job.setCursorMinute(nextCursor);
            job.setStatus(finished
                    ? RecalculationJobStatus.SUCCEEDED
                    : RecalculationJobStatus.WAITING);
            job.setFinishedAt(finished ? at : null);
            job.setUpdateTime(at);
        }

        @Override
        public void markFailed(
                String jobId,
                LocalDateTime expectedCursor,
                String error) {
            BizDataQualityRecalcJob job = byId.get(jobId);
            if (job != null && expectedCursor.equals(job.getCursorMinute())) {
                job.setStatus(RecalculationJobStatus.FAILED);
                job.setLastError(error);
            }
        }
    }

    /**
     * 保存旧任务与人工 Q1/Q2 子任务，使详情查询经过真实 DTO 编解码链路。
     */
    private static final class InMemoryFillTaskRepository
            implements FillTaskRepository {

        private final Map<String, BizDataQualityFillTask> tasksById =
                new LinkedHashMap<>();
        private final Map<String, String> taskIdsByKey = new LinkedHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public BizDataQualityFillTask getOrCreate(
                BizDataQualityFillTask candidate) {
            String existingId = taskIdsByKey.get(candidate.getIdempotencyKey());
            if (existingId != null) {
                return tasksById.get(existingId);
            }
            candidate.setTaskId("CHILD-" + sequence.incrementAndGet());
            tasksById.put(candidate.getTaskId(), candidate);
            taskIdsByKey.put(candidate.getIdempotencyKey(), candidate.getTaskId());
            return candidate;
        }

        @Override
        public Optional<BizDataQualityFillTask> findById(String taskId) {
            return Optional.ofNullable(tasksById.get(taskId));
        }

        @Override
        public Optional<BizDataQualityFillTask> findAuditById(String taskId) {
            return findById(taskId);
        }

        @Override
        public Optional<BizDataQualityFillTask> findByIdForUpdate(
                String taskId) {
            return findById(taskId);
        }

        @Override
        public IPage<BizDataQualityFillTask> findPage(
                int pageNum,
                int pageSize,
                boolean allBuildings,
                Collection<String> buildingIds,
                String buildingId,
                String pointId,
                FillSourceType sourceType,
                Integer dataQuality,
                FillApplyStatus applyStatus,
                LocalDateTime fromInclusive,
                LocalDateTime toExclusive) {
            return new Page<BizDataQualityFillTask>(pageNum, pageSize)
                    .setRecords(List.copyOf(tasksById.values()))
                    .setTotal(tasksById.size());
        }

        @Override
        public List<BizDataQualityFillTask> findByRecalculationJobId(
                String jobId) {
            return tasksById.values().stream()
                    .filter(task -> jobId.equals(task.getRecalcJobId()))
                    .sorted(Comparator.comparing(BizDataQualityFillTask::getTaskId))
                    .toList();
        }

        @Override
        public void markFirstApplied(String taskId) {
            BizDataQualityFillTask task = tasksById.get(taskId);
            task.setApplyStatus(FillApplyStatus.APPLIED);
            task.setAppliedCount(Math.max(1, zero(task.getAppliedCount())));
        }

        @Override
        public void incrementReplacedCount(String taskId, int increment) {
            BizDataQualityFillTask task = tasksById.get(taskId);
            task.setReplacedCount(zero(task.getReplacedCount()) + increment);
        }

        @Override
        public void recordFailure(
                String taskId, long minuteStart, String error) {
            BizDataQualityFillTask task = tasksById.get(taskId);
            task.setApplyStatus(FillApplyStatus.FAILED);
            task.setFailedCount(zero(task.getFailedCount()) + 1);
            task.setLastError(error);
        }

        @Override
        public List<BizDataQualityFillTask> findRetryable(
                LocalDateTime updatedBefore, int limit) {
            return List.of();
        }

        @Override
        public List<String> findInvalidSourceRetryableTaskIds(
                LocalDateTime updatedBefore, int limit) {
            return List.of();
        }

        @Override
        public List<BizDataQualityFillTask> findWaitingInterpolationTasks(
                LocalDateTime updatedBefore, int limit) {
            return List.of();
        }

        @Override
        public List<BizDataQualityFillTask> findTypicalTasksToClose(
                LocalDateTime hourEndedBefore, int limit) {
            return List.of();
        }

        @Override
        public List<BizDataQualityFillTask> findInterpolationTasksToClose(
                LocalDateTime updatedBefore, int limit) {
            return List.of();
        }

        @Override
        public void incrementRetry(String taskId) {
            BizDataQualityFillTask task = tasksById.get(taskId);
            task.setRetryCount(zero(task.getRetryCount()) + 1);
        }

        @Override
        public void recordRetryError(String taskId, String error) {
            tasksById.get(taskId).setLastError(error);
        }

        @Override
        public void markRetryRecovered(
                String taskId,
                FillApplyStatus applyStatus,
                int ownReplacementIncrement) {
            BizDataQualityFillTask task = tasksById.get(taskId);
            task.setApplyStatus(applyStatus);
            task.setReplacedCount(
                    zero(task.getReplacedCount()) + ownReplacementIncrement);
        }

        @Override
        public void recordReplacements(
                Map<String, Integer> countsByOldTaskId) {
            countsByOldTaskId.forEach((taskId, count) -> {
                BizDataQualityFillTask task = tasksById.get(taskId);
                if (task != null) {
                    task.setReplacedCount(
                            zero(task.getReplacedCount()) + count);
                }
            });
        }

        @Override
        public void reconcile(TaskReconciliation result) {
            // 本验收不运行后台普通补全恢复。
        }

        @Override
        public void markVoided(
                String taskId,
                long operatorId,
                String reason,
                LocalDateTime at) {
            BizDataQualityFillTask task = tasksById.get(taskId);
            task.setApplyStatus(FillApplyStatus.VOIDED);
            task.setVoidBy(operatorId);
            task.setVoidReason(reason);
            task.setVoidAt(at);
        }

        @Override
        public void markVoidedExact(
                String taskId,
                long operatorId,
                String reason,
                LocalDateTime at,
                int minuteCount,
                int failedCount,
                int replacedCount,
                int voidedCount) {
            BizDataQualityFillTask task = tasksById.get(taskId);
            task.setApplyStatus(FillApplyStatus.VOIDED);
            task.setMinuteCount(minuteCount);
            task.setAppliedCount(0);
            task.setFailedCount(failedCount);
            task.setReplacedCount(replacedCount);
            task.setVoidedCount(voidedCount);
            task.setVoidBy(operatorId);
            task.setVoidReason(reason);
            task.setVoidAt(at);
            task.setClosedAt(at);
            task.setUpdateTime(at);
        }

        private static int zero(Integer value) {
            return value == null ? 0 : value;
        }
    }

}
