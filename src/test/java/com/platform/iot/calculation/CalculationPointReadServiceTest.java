package com.platform.iot.calculation;

import com.platform.energy.activity.EnergyActivityDataReader;
import com.platform.energy.activity.EnergyActivityDataReader.Cursor;
import com.platform.energy.activity.EnergyActivityDataReader.RawEvent;
import com.platform.energy.activity.EnergyActivityDataReader.RawEventPage;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.service.BizDataPointService;
import com.platform.iot.calculation.CalculationPointReadContracts.CalculationPointFact;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.PolicySource;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.platform.iot.calculation.CalculationPointReadErrors.DATA_SCOPE_MISMATCH;
import static com.platform.iot.calculation.CalculationPointReadErrors.DUPLICATE_FACT_IDENTITY;
import static com.platform.iot.calculation.CalculationPointReadErrors.POINT_METADATA_REQUIRED;
import static com.platform.iot.calculation.CalculationPointReadErrors.RAW_FACT_INVALID;
import static com.platform.iot.calculation.CalculationPointReadErrors.RESOURCE_LIMIT_EXCEEDED;
import static com.platform.iot.qualityusage.QualityUsageModels.INDICATOR_CALCULATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalculationPointReadServiceTest {
    private static final long USER_ID = 7L;
    private static final String BUILDING = "BLD001";
    private static final String OTHER_BUILDING = "BLD999";
    private static final String POINT = "POINT001";
    private static final long FROM = 100L;
    private static final long TO = 200L;
    private static final long WATERMARK = 300L;

    private BuildingScopeService buildingScope;
    private BizDataPointService dataPointService;
    private EnergyActivityDataReader dataReader;
    private QualityUsagePolicyResolver qualityResolver;
    private ResolutionContext context;
    private CalculationPointReadService service;

    @BeforeEach
    void setUp() {
        buildingScope = mock(BuildingScopeService.class);
        dataPointService = mock(BizDataPointService.class);
        dataReader = mock(EnergyActivityDataReader.class);
        qualityResolver = mock(QualityUsagePolicyResolver.class);
        context = mock(ResolutionContext.class);
        when(context.configRevision()).thenReturn(44L);
        service = new CalculationPointReadService(
                buildingScope, dataPointService, dataReader, qualityResolver);
    }

    @Test
    void retainsQualityBlockedFactsWithMetadataAndPolicyEvidence() {
        stubMetadata(POINT, "ANALOG", "m³/h");
        stubContext(Set.of(POINT), FROM, TO + 1);
        RawEvent blocked = event(POINT, BUILDING, 150L, 180L, 12.3, 2);
        when(dataReader.readRawEvents(BUILDING, Set.of(POINT), FROM, TO + 1, null, 500))
                .thenReturn(new RawEventPage(List.of(blocked), false, null));
        when(qualityResolver.resolve(eq(context), eq(POINT), eq(INDICATOR_CALCULATION),
                anyLong(), eq(2))).thenReturn(resolution(Decision.BLOCK, 2));

        var snapshot = service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, Set.of(POINT),
                instant(FROM), instant(TO), instant(WATERMARK));

        assertThat(snapshot.scenarioCode()).isEqualTo(INDICATOR_CALCULATION);
        assertThat(snapshot.qualityConfigRevision()).isEqualTo(44L);
        assertThat(snapshot.points()).singleElement().satisfies(point -> {
            assertThat(point.dataType()).isEqualTo("ANALOG");
            assertThat(point.unit()).isEqualTo("m³/h");
        });
        assertThat(snapshot.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.factIdentity()).isEqualTo("POINT001:150");
            assertThat(fact.rawValue()).isEqualTo(12.3);
            assertThat(fact.eventTime()).isEqualTo(instant(150L));
            assertThat(fact.receivedTime()).isEqualTo(instant(180L));
            assertThat(fact.qualityLevel()).isEqualTo("Q2");
            assertThat(fact.decision()).isEqualTo("BLOCK");
            assertThat(fact.policySource()).isEqualTo("PUBLISHED_POLICY");
            assertThat(fact.policyVersion()).isEqualTo(9);
            assertThat(fact.policyReason()).isEqualTo("QUALITY_NOT_ALLOWED");
            assertThat(fact.sourceNature()).isEqualTo("UNKNOWN");
            assertThat(fact.sourceNatureEvidence()).isEqualTo("UPSTREAM_NOT_PROVIDED");
        });
    }

    @Test
    void rejectsRawFactFromAnotherBuildingBeforeQualityResolution() {
        stubMetadata(POINT, "ANALOG", "℃");
        stubContext(Set.of(POINT), FROM, TO + 1);
        when(dataReader.readRawEvents(BUILDING, Set.of(POINT), FROM, TO + 1, null, 500))
                .thenReturn(new RawEventPage(
                        List.of(event(POINT, OTHER_BUILDING, 150L, 160L, 7.0, 0)), false, null));

        assertThatThrownBy(() -> service.read(
                USER_ID, Set.of("BUILDING_OWNER"), BUILDING, Set.of(POINT),
                instant(FROM), instant(TO), instant(WATERMARK)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(500);
                    assertThat(exception.getErrorCode()).isEqualTo(DATA_SCOPE_MISMATCH);
                });
        verify(buildingScope).checkAccess(USER_ID, Set.of("BUILDING_OWNER"), BUILDING);
        verify(qualityResolver, never()).resolve(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void includesExactEndBoundaryThroughHalfOpenUnderlyingQuery() {
        stubMetadata(POINT, "ACCUMULATE", "kWh");
        stubContext(Set.of(POINT), FROM, TO + 1);
        RawEvent start = event(POINT, BUILDING, FROM, 110L, 1.0, 0);
        RawEvent end = event(POINT, BUILDING, TO, 210L, 2.0, 0);
        when(dataReader.readRawEvents(BUILDING, Set.of(POINT), FROM, TO + 1, null, 500))
                .thenReturn(new RawEventPage(List.of(start, end), false, null));
        stubAllowedQuality();

        var snapshot = service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, Set.of(POINT),
                instant(FROM), instant(TO), instant(WATERMARK));

        assertThat(snapshot.facts()).extracting(CalculationPointFact::eventTime)
                .containsExactly(instant(FROM), instant(TO));
        verify(dataReader).readRawEvents(BUILDING, Set.of(POINT), FROM, TO + 1, null, 500);
        verify(qualityResolver).historyContext(Set.of(POINT), INDICATOR_CALCULATION, FROM, TO + 1);
    }

    @Test
    void readsAllSeekPagesBeforeReturningCompleteSnapshot() {
        stubMetadata(POINT, "ANALOG", "kW");
        stubContext(Set.of(POINT), FROM, TO + 1);
        Cursor firstCursor = new Cursor(150L, POINT);
        when(dataReader.readRawEvents(BUILDING, Set.of(POINT), FROM, TO + 1, null, 500))
                .thenReturn(new RawEventPage(
                        List.of(event(POINT, BUILDING, 150L, 151L, 1.0, 0)), true, firstCursor));
        when(dataReader.readRawEvents(BUILDING, Set.of(POINT), FROM, TO + 1, firstCursor, 500))
                .thenReturn(new RawEventPage(
                        List.of(event(POINT, BUILDING, 180L, 181L, 2.0, 0)), false, null));
        stubAllowedQuality();

        var snapshot = service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, Set.of(POINT),
                instant(FROM), instant(TO), instant(WATERMARK));

        assertThat(snapshot.scannedFactCount()).isEqualTo(2);
        assertThat(snapshot.facts()).extracting(CalculationPointFact::factIdentity)
                .containsExactly("POINT001:150", "POINT001:180");
        verify(dataReader).readRawEvents(BUILDING, Set.of(POINT), FROM, TO + 1, firstCursor, 500);
    }

    @Test
    void filtersFactsArrivingAfterWatermarkAndDeclaresTheWatermark() {
        stubMetadata(POINT, "ANALOG", "℃");
        stubContext(Set.of(POINT), FROM, TO + 1);
        RawEvent visible = event(POINT, BUILDING, 120L, 250L, 7.0, 0);
        RawEvent afterWatermark = event(POINT, BUILDING, 180L, 301L, 8.0, 0);
        when(dataReader.readRawEvents(BUILDING, Set.of(POINT), FROM, TO + 1, null, 500))
                .thenReturn(new RawEventPage(List.of(visible, afterWatermark), false, null));
        stubAllowedQuality();

        var snapshot = service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, Set.of(POINT),
                instant(FROM), instant(TO), instant(WATERMARK));

        assertThat(snapshot.activityWatermark()).isEqualTo(instant(WATERMARK));
        assertThat(snapshot.scannedFactCount()).isEqualTo(2);
        assertThat(snapshot.filteredAfterWatermarkCount()).isEqualTo(1);
        assertThat(snapshot.facts()).extracting(CalculationPointFact::factIdentity)
                .containsExactly("POINT001:120");
        verify(qualityResolver, times(1)).resolve(
                eq(context), eq(POINT), eq(INDICATOR_CALCULATION), anyLong(), eq(0));
    }

    @Test
    void rejectsMoreThan32RequestedPointsBeforeCallingDependencies() {
        Set<String> points = IntStream.range(0, 33)
                .mapToObj(index -> "POINT%03d".formatted(index))
                .collect(java.util.stream.Collectors.toSet());

        assertThatThrownBy(() -> service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, points,
                instant(FROM), instant(TO), instant(WATERMARK)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getErrorCode()).isEqualTo(RESOURCE_LIMIT_EXCEEDED);
                });
        verify(dataPointService, never()).listByBuilding(any());
        verify(dataReader, never()).readRawEvents(any(), anySet(), anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void acceptsExactly32RequestedPoints() {
        Set<String> points = IntStream.range(0, 32)
                .mapToObj(index -> "POINT%03d".formatted(index))
                .collect(java.util.stream.Collectors.toSet());
        List<BizDataPoint> metadata = points.stream()
                .map(pointId -> point(pointId, "ANALOG", "kW"))
                .toList();
        when(dataPointService.listByBuilding(BUILDING)).thenReturn(Result.success(metadata));
        stubContext(points, FROM, TO + 1);
        when(dataReader.readRawEvents(BUILDING, points, FROM, TO + 1, null, 500))
                .thenReturn(new RawEventPage(List.of(), false, null));

        var snapshot = service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, points,
                instant(FROM), instant(TO), instant(WATERMARK));

        assertThat(snapshot.points()).hasSize(32);
        assertThat(snapshot.facts()).isEmpty();
    }

    @Test
    void rejectsRangesLongerThan31DaysBeforeCallingDependencies() {
        long tooLong = CalculationPointReadService.MAX_RANGE_MILLIS + 1;

        assertThatThrownBy(() -> service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, Set.of(POINT),
                instant(0L), instant(tooLong), instant(tooLong)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getErrorCode()).isEqualTo(RESOURCE_LIMIT_EXCEEDED);
                });
        verify(dataPointService, never()).listByBuilding(any());
    }

    @Test
    void rejectsMoreThanTwentyThousandRawFactsAcrossSeekPages() {
        long end = 30_000L;
        stubMetadata(POINT, "ANALOG", "kW");
        stubContext(Set.of(POINT), FROM, end + 1);
        stubAllowedQuality();
        AtomicInteger pageIndex = new AtomicInteger();
        when(dataReader.readRawEvents(eq(BUILDING), eq(Set.of(POINT)), eq(FROM), eq(end + 1),
                any(), eq(500))).thenAnswer(invocation -> pageForLimit(pageIndex.getAndIncrement()));

        assertThatThrownBy(() -> service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, Set.of(POINT),
                instant(FROM), instant(end), instant(40_000L)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getErrorCode()).isEqualTo(RESOURCE_LIMIT_EXCEEDED);
        });
        assertThat(pageIndex.get()).isEqualTo(41);
    }

    @Test
    void acceptsExactlyTwentyThousandRawFactsAcrossSeekPages() {
        long end = 30_000L;
        stubMetadata(POINT, "ANALOG", "kW");
        stubContext(Set.of(POINT), FROM, end + 1);
        stubAllowedQuality();
        AtomicInteger pageIndex = new AtomicInteger();
        when(dataReader.readRawEvents(eq(BUILDING), eq(Set.of(POINT)), eq(FROM), eq(end + 1),
                any(), eq(500))).thenAnswer(invocation -> pageForMaximum(pageIndex.getAndIncrement()));

        var snapshot = service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, Set.of(POINT),
                instant(FROM), instant(end), instant(40_000L));

        assertThat(pageIndex.get()).isEqualTo(40);
        assertThat(snapshot.scannedFactCount()).isEqualTo(20_000);
        assertThat(snapshot.facts()).hasSize(20_000);
    }

    @Test
    void rejectsNonFiniteRawValuesBeforeQualityResolution() {
        stubMetadata(POINT, "ANALOG", "m³/h");
        stubContext(Set.of(POINT), FROM, TO + 1);
        when(dataReader.readRawEvents(BUILDING, Set.of(POINT), FROM, TO + 1, null, 500))
                .thenReturn(new RawEventPage(
                        List.of(event(POINT, BUILDING, 150L, 160L, Double.NaN, 0)), false, null));

        assertThatThrownBy(() -> service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, Set.of(POINT),
                instant(FROM), instant(TO), instant(WATERMARK)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getErrorCode()).isEqualTo(RAW_FACT_INVALID);
                });
        verify(qualityResolver, never()).resolve(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void rejectsRepeatedPublicFactIdentityInsteadOfSilentlyMergingIt() {
        stubMetadata(POINT, "ANALOG", "kW");
        stubContext(Set.of(POINT), FROM, TO + 1);
        RawEvent first = event(POINT, BUILDING, 150L, 160L, 1.0, 0);
        RawEvent duplicate = event(POINT, BUILDING, 150L, 161L, 2.0, 0);
        when(dataReader.readRawEvents(BUILDING, Set.of(POINT), FROM, TO + 1, null, 500))
                .thenReturn(new RawEventPage(List.of(first, duplicate), false, null));

        assertThatThrownBy(() -> service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, Set.of(POINT),
                instant(FROM), instant(TO), instant(WATERMARK)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getErrorCode()).isEqualTo(DUPLICATE_FACT_IDENTITY);
                });
        verify(qualityResolver, never()).resolve(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void rejectsPointMetadataWithoutSourceUnitBeforeReadingRawEvents() {
        BizDataPoint missingUnit = point(POINT, "ANALOG", " ");
        when(dataPointService.listByBuilding(BUILDING)).thenReturn(Result.success(List.of(missingUnit)));

        assertThatThrownBy(() -> service.read(
                USER_ID, Set.of("ENERGY_MANAGER"), BUILDING, Set.of(POINT),
                instant(FROM), instant(TO), instant(WATERMARK)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getErrorCode()).isEqualTo(POINT_METADATA_REQUIRED);
                });
        verify(dataReader, never()).readRawEvents(any(), anySet(), anyLong(), anyLong(), any(), anyInt());
    }

    private void stubMetadata(String pointId, String dataType, String unit) {
        when(dataPointService.listByBuilding(BUILDING))
                .thenReturn(Result.success(List.of(point(pointId, dataType, unit))));
    }

    private void stubContext(Set<String> pointIds, long from, long toExclusive) {
        when(qualityResolver.historyContext(pointIds, INDICATOR_CALCULATION, from, toExclusive))
                .thenReturn(context);
    }

    private void stubAllowedQuality() {
        when(qualityResolver.resolve(eq(context), eq(POINT), eq(INDICATOR_CALCULATION),
                anyLong(), anyInt())).thenAnswer(invocation -> resolution(
                Decision.ALLOW, invocation.getArgument(4, Integer.class)));
    }

    private static RawEventPage pageForLimit(int pageIndex) {
        int count = pageIndex == 40 ? 1 : 500;
        int firstOffset = pageIndex * 500;
        List<RawEvent> facts = IntStream.range(firstOffset, firstOffset + count)
                .mapToObj(offset -> event(
                        POINT, BUILDING, FROM + offset, 40_000L, offset + 1.0, 0))
                .toList();
        Cursor next = pageIndex < 40
                ? new Cursor(FROM + firstOffset + count - 1L, POINT) : null;
        return new RawEventPage(facts, next != null, next);
    }

    private static RawEventPage pageForMaximum(int pageIndex) {
        int firstOffset = pageIndex * 500;
        List<RawEvent> facts = IntStream.range(firstOffset, firstOffset + 500)
                .mapToObj(offset -> event(
                        POINT, BUILDING, FROM + offset, 40_000L, offset + 1.0, 0))
                .toList();
        Cursor next = pageIndex < 39
                ? new Cursor(FROM + firstOffset + 499L, POINT) : null;
        return new RawEventPage(facts, next != null, next);
    }

    private static BizDataPoint point(String pointId, String dataType, String unit) {
        BizDataPoint point = new BizDataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId + "_CODE");
        point.setBuildingId(BUILDING);
        point.setDataType(dataType);
        point.setUnit(unit);
        return point;
    }

    private static RawEvent event(
            String pointId,
            String buildingId,
            long eventTime,
            long receivedTime,
            double rawValue,
            int quality) {
        return new RawEvent(
                pointId,
                pointId + "_CODE",
                buildingId,
                "UNSPECIFIED_SOURCE",
                pointId + "_SOURCE",
                "DEVICE001",
                rawValue,
                eventTime,
                receivedTime,
                quality,
                false);
    }

    private static Resolution resolution(Decision decision, int quality) {
        return new Resolution(
                decision,
                quality,
                INDICATOR_CALCULATION,
                decision == Decision.BLOCK
                        ? PolicySource.PUBLISHED_POLICY : PolicySource.SYSTEM_DEFAULT_Q0_ONLY,
                decision == Decision.BLOCK ? 9 : null,
                44L,
                decision == Decision.BLOCK ? "QUALITY_NOT_ALLOWED" : "QUALITY_ALLOWED");
    }

    private static Instant instant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }
}
