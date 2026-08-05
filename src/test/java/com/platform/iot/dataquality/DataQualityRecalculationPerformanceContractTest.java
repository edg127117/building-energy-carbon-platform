package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.IndicatorLatestCacheService;
import com.platform.config.DataQualityProperties;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.hvac.model.entity.Building;
import com.platform.hvac.service.BizDataPointService;
import com.platform.hvac.service.BizEquipmentService;
import com.platform.hvac.service.BuildingService;
import com.platform.hvac.service.HvacIndicatorQueryService;
import com.platform.hvac.service.HvacQueryService;
import com.platform.hvac.service.HvacSnapshotFreshnessPolicy;
import com.platform.iot.aggregation.HvacPointMinuteAggregator;
import com.platform.iot.aggregation.ManualRealMinuteAggregationService;
import com.platform.iot.dataquality.mapper.BizDataQualityRecalcJobMapper;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import com.platform.iot.formula.HvacFormulaEngine;
import com.platform.iot.formula.IndicatorConfigProvider;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化人工重算的批量读取边界，防止后续实现退化为逐测点、逐分钟查询。
 *
 * <p>测试使用真实的历史 Q0 聚合、Q1/Q2 选择和分块编排组合，只替换数据库及
 * 事件边界；因此断言覆盖的是完整服务调用链，而不是对 mock 自身的孤立验证。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataQualityRecalculationPerformanceContractTest {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final long BASE = LocalDateTime.of(2026, 7, 29, 10, 0)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli();
    private static final long NOW = BASE + 24 * 60 * MINUTE_MILLIS;
    private static final Set<String> POINT_IDS = Set.of("P1");

    @Mock private HvacRawEventRepository rawRepository;
    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private RecalculationJobRepository jobRepository;
    @Mock private DataPointConfigProvider pointConfigProvider;
    @Mock private FillTaskRepository fillTaskRepository;
    @Mock private TypicalValueConfigProvider typicalValueConfigProvider;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BizDataQualityRecalcJobMapper recalculationJobMapper;

    @ParameterizedTest
    @CsvSource({
            "61, 2",
            "120, 2"
    })
    void eachChunkPerformsExactlyOneRawAndOneFormalMinuteBatchRead(
            int targetMinutes,
            int chunkCount) {
        DataQualityProperties properties = new DataQualityProperties();
        properties.getInterpolation().setMaxGapMinutes(5);
        PointRuntimeConfig point = point();
        BizDataQualityRecalcJob job = runningJob(targetMinutes);
        ManualRealMinuteAggregationService realAggregator =
                new ManualRealMinuteAggregationService(
                        rawRepository,
                        pointConfigProvider,
                        new HvacPointMinuteAggregator());
        ManualQualitySelectionService realSelection =
                new ManualQualitySelectionService(
                        properties,
                        minuteRepository,
                        fillTaskRepository,
                        typicalValueConfigProvider,
                        new FillTaskEvidenceCodec(
                                new ObjectMapper().findAndRegisterModules()),
                        new LinearMinuteInterpolator());
        DataQualityRecalculationService service =
                new DataQualityRecalculationService(
                        jobRepository,
                        realAggregator,
                        realSelection,
                        minuteRepository,
                        pointConfigProvider,
                        eventPublisher,
                        properties,
                        new ObjectMapper());

        when(jobRepository.findById("JOB-PERF"))
                .thenReturn(Optional.of(job));
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(rawRepository.findWindow(anyLong(), anyLong(), eq(true)))
                .thenReturn(List.of());
        when(minuteRepository.findRange(
                eq(POINT_IDS), anyLong(), anyLong()))
                .thenReturn(List.of());
        when(typicalValueConfigProvider.findApproved(
                eq("P1"), anyLong()))
                .thenReturn(Optional.empty());
        doAnswer(invocation -> {
            job.setCursorMinute(invocation.getArgument(2));
            return null;
        }).when(jobRepository).advanceChunk(
                eq("JOB-PERF"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(),
                any(Boolean.class),
                any(LocalDateTime.class));

        for (int chunk = 0; chunk < chunkCount; chunk++) {
            service.processClaimedJob("JOB-PERF", NOW);
        }

        ArgumentCaptor<Long> rawFrom = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> rawTo = ArgumentCaptor.forClass(Long.class);
        verify(rawRepository, times(chunkCount))
                .findWindow(rawFrom.capture(), rawTo.capture(), eq(true));
        ArgumentCaptor<Long> minuteFrom = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> minuteTo = ArgumentCaptor.forClass(Long.class);
        verify(minuteRepository, times(chunkCount))
                .findRange(
                        eq(POINT_IDS),
                        minuteFrom.capture(),
                        minuteTo.capture());

        List<Long> expectedFrom = new ArrayList<>();
        List<Long> expectedTo = new ArrayList<>();
        long jobTo = BASE + targetMinutes * MINUTE_MILLIS;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            long cursor = BASE + chunk * 60 * MINUTE_MILLIS;
            long targetTo = Math.min(
                    cursor + 60 * MINUTE_MILLIS, jobTo);
            expectedFrom.add(Math.max(
                    BASE, cursor - 6 * MINUTE_MILLIS));
            expectedTo.add(Math.min(
                    jobTo, targetTo + 6 * MINUTE_MILLIS));
        }
        assertThat(rawFrom.getAllValues()).containsExactlyElementsOf(expectedFrom);
        assertThat(rawTo.getAllValues()).containsExactlyElementsOf(expectedTo);
        assertThat(minuteFrom.getAllValues()).containsExactlyElementsOf(expectedFrom);
        assertThat(minuteTo.getAllValues()).containsExactlyElementsOf(expectedTo);
        assertThat(job.getCursorMinute()).isEqualTo(local(jobTo));
        verify(eventPublisher, times(targetMinutes)).publishEvent(any(Object.class));
    }

    @Test
    void ordinaryHvacAndFormulaHistoryDoNotScanRecalculationJobs() {
        BuildingService buildingService = mock(BuildingService.class);
        BuildingScopeService scopeService = mock(BuildingScopeService.class);
        BizDataPointService dataPointService = mock(BizDataPointService.class);
        BizEquipmentService equipmentService = mock(BizEquipmentService.class);
        HvacMinuteRepository queryMinuteRepository =
                mock(HvacMinuteRepository.class);
        Building building = new Building();
        building.setBuildingId("BLD001");
        when(buildingService.getById("BLD001")).thenReturn(building);

        BizDataPoint point = new BizDataPoint();
        point.setPointId("P1");
        point.setPointCode("P1");
        point.setPointName("冷冻水供水温度");
        point.setBuildingId("BLD001");
        point.setStatus("ONLINE");
        point.setUnit("℃");
        when(dataPointService.listByIds(List.of("P1")))
                .thenReturn(List.of(point));
        when(queryMinuteRepository.findHistory(
                List.of("P1"), BASE, BASE + MINUTE_MILLIS, 1))
                .thenReturn(List.of());
        HvacQueryService hvacQueryService = new HvacQueryService(
                buildingService,
                scopeService,
                dataPointService,
                equipmentService,
                queryMinuteRepository,
                mock(HvacSnapshotFreshnessPolicy.class));

        hvacQueryService.history(
                "BLD001",
                "P1",
                BASE,
                BASE + MINUTE_MILLIS,
                1L,
                Set.of("PLATFORM_ADMIN"));

        IndicatorConfigProvider indicatorConfigProvider =
                mock(IndicatorConfigProvider.class);
        IndicatorLatestCacheService cache =
                mock(IndicatorLatestCacheService.class);
        IndicatorMinuteRepository indicatorRepository =
                mock(IndicatorMinuteRepository.class);
        HvacFormulaEngine formulaEngine = mock(HvacFormulaEngine.class);
        BizIndicator indicator = new BizIndicator();
        indicator.setIndicatorId("I1");
        indicator.setIndicatorCode("WCR_COP");
        indicator.setBuildingId("BLD001");
        indicator.setEquipId("WCR1");
        indicator.setSystemGroupId("GROUP001");
        indicator.setStatus(1);
        when(indicatorConfigProvider.findActive("I1"))
                .thenReturn(Optional.of(indicator));
        when(indicatorRepository.findHistory(
                "I1", BASE, BASE + MINUTE_MILLIS))
                .thenReturn(List.of());
        HvacIndicatorQueryService indicatorQueryService =
                new HvacIndicatorQueryService(
                        buildingService,
                        scopeService,
                        indicatorConfigProvider,
                        cache,
                        indicatorRepository,
                        queryMinuteRepository,
                        formulaEngine);

        indicatorQueryService.history(
                "I1",
                BASE,
                BASE + MINUTE_MILLIS,
                1L,
                Set.of("PLATFORM_ADMIN"));

        verify(recalculationJobMapper, never()).selectPageFiltered(
                any(), any(), any(), any(), any(), any());
    }

    private BizDataQualityRecalcJob runningJob(int targetMinutes) {
        BizDataQualityRecalcJob job = new BizDataQualityRecalcJob();
        job.setJobId("JOB-PERF");
        job.setJobType(RecalculationJobType.RANGE_RECALCULATE);
        job.setBuildingId("BLD001");
        job.setPointIdsJson("[\"P1\"]");
        job.setFromMinute(local(BASE));
        job.setToMinute(local(BASE + targetMinutes * MINUTE_MILLIS));
        job.setCursorMinute(local(BASE));
        job.setStatus(RecalculationJobStatus.RUNNING);
        job.setPhase(RecalculationJobPhase.RECALCULATING);
        return job;
    }

    private PointRuntimeConfig point() {
        return new PointRuntimeConfig(
                "P1",
                "P1",
                "冷冻水供水温度",
                "BLD001",
                "GROUP001",
                "WCR1",
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

    private LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis),
                ZoneId.of("Asia/Shanghai"));
    }
}
