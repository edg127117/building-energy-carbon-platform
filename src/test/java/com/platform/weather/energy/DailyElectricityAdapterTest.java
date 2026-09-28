package com.platform.weather.energy;

import com.platform.energy.aggregation.NativeQuantityAggregationService;
import com.platform.energy.aggregation.EnergyAggregationModels.EvidenceStatus;
import com.platform.energy.aggregation.EnergyAggregationModels.MeterEventEvidence;
import com.platform.energy.aggregation.EnergyAggregationModels.MeterEventType;
import com.platform.energy.activity.EnergyActivityPointCatalog;
import com.platform.energy.activity.EnergyActivityPointCatalog.PointProfile;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.PolicySource;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.MeteringAssignmentView;
import com.platform.relation.api.RelationContracts.MeteringAssignmentsView;
import com.platform.relation.api.RelationContracts.QueryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.platform.iot.qualityusage.QualityUsageModels.ENERGY_ACTIVITY_AGGREGATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyElectricityAdapterTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 20);
    private static final long LEFT_MIDNIGHT = DAY.atStartOfDay(ZONE).toInstant().toEpochMilli();
    private static final long RIGHT_MIDNIGHT = DAY.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();

    private HvacRawEventRepository raw;
    private EnergyActivityPointCatalog catalog;
    private DataPointConfigProvider configs;
    private RelationGovernanceService relations;
    private QualityUsagePolicyResolver quality;
    private NativeQuantityAggregationService aggregation;
    private DailyElectricityAdapter adapter;

    @BeforeEach
    void setUp() {
        raw = mock(HvacRawEventRepository.class);
        catalog = mock(EnergyActivityPointCatalog.class);
        configs = mock(DataPointConfigProvider.class);
        relations = mock(RelationGovernanceService.class);
        quality = mock(QualityUsagePolicyResolver.class);
        aggregation = mock(NativeQuantityAggregationService.class);
        adapter = new DailyElectricityAdapter(raw, catalog, configs, relations, quality, aggregation);

        when(relations.effectiveMeteringAssignments(7L, List.of("ENERGY_MANAGER"), "B1", 1, 500))
                .thenReturn(assignments());
        when(configs.findByPointId("P1")).thenReturn(Optional.of(config()));
        when(catalog.find("B1", Set.of("P1"))).thenReturn(List.of(profile()));
        ResolutionContext context = mock(ResolutionContext.class);
        when(quality.historyContext(anySet(), eq(ENERGY_ACTIVITY_AGGREGATION), anyLong(), anyLong()))
                .thenReturn(context);
        when(quality.resolve(eq(context), eq("P1"), eq(ENERGY_ACTIVITY_AGGREGATION), anyLong(), anyInt()))
                .thenAnswer(call -> allowed(call.getArgument(4)));
        when(aggregation.governedEvidence(eq(7L), eq(List.of("ENERGY_MANAGER")), eq("B1"), eq("P1"),
                any(Instant.class), any(Instant.class)))
                .thenReturn(new NativeQuantityAggregationService.GovernedEvidence(List.of(), List.of()));
        when(aggregation.aggregateCumulative(any())).thenReturn(new NativeQuantityAggregationService.NativeQuantityResult(
                new BigDecimal("12.50"), "kWh", BigDecimal.ONE, 0, List.of("P1@1"), List.of(), List.of(), List.of(), true));
    }

    @Test
    void calculatesFromLatestQualifiedReadingBeforeMidnightAndMarksApproximate() {
        long before = LEFT_MIDNIGHT - 6 * 60_000L;
        when(raw.findPointHistory(eq("B1"), eq("E1"), eq("P1"), anyLong(), anyLong(), isNull(), eq(500)))
                .thenReturn(List.of(event(before, 100.0), event(RIGHT_MIDNIGHT, 112.5)));

        var result = adapter.calculate(7L, List.of("ENERGY_MANAGER"), "B1", "S1", "P1", DAY, DAY).getFirst();

        assertThat(result.energyKwh()).isEqualByComparingTo("12.50");
        assertThat(result.status()).isEqualTo("APPROXIMATE");
        assertThat(result.approximate()).isTrue();
        assertThat(result.startTime()).isEqualTo(before);
        assertThat(result.endTime()).isEqualTo(RIGHT_MIDNIGHT);
        assertThat(result.relationVersion()).isEqualTo("RV1");
        assertThat(result.direction()).isEqualTo("INBOUND");
        assertThat(result.unit()).isEqualTo("kWh");
        assertThat(result.startBoundaryOffsetMillis()).isEqualTo(-6 * 60_000L);
        assertThat(result.endBoundaryOffsetMillis()).isZero();
        assertThat(result.calculatedAt()).isPositive();
        assertThat(result.inputSummaryHash()).hasSize(64);
        assertThat(result.calculationPolicyVersion()).contains("NATIVE_CUMULATIVE_CORE");
        verify(aggregation).aggregateCumulative(argThat(request ->
                request.scope().fromInclusive().toEpochMilli() == before
                        && request.scope().toExclusive().toEpochMilli() == RIGHT_MIDNIGHT));
    }

    @Test
    void doesNotUseAReadingAfterMidnightAsTheLeftBoundary() {
        when(raw.findPointHistory(eq("B1"), eq("E1"), eq("P1"), anyLong(), anyLong(), isNull(), eq(500)))
                .thenReturn(List.of(event(LEFT_MIDNIGHT + 60_000, 100.0), event(RIGHT_MIDNIGHT, 112.5)));

        var result = adapter.calculate(7L, List.of("ENERGY_MANAGER"), "B1", "S1", "P1", DAY, DAY).getFirst();

        assertThat(result.energyKwh()).isNull();
        assertThat(result.status()).isEqualTo("MISSING");
        assertThat(result.reasons()).contains("QUALIFIED_BOUNDARY_MISSING");
        verify(aggregation, never()).aggregateCumulative(any());
    }

    @Test
    void rejectsPointOutsideRequestedSystemBeforeReadingRawEvents() {
        when(configs.findByPointId("P1")).thenReturn(Optional.of(new PointRuntimeConfig(
                "P1", "PC1", "Meter", "B1", "OTHER", "E1", "E1", "F", "C", "EPP",
                "ANALOG", "kWh", "ONLINE", 1, null, null)));

        var result = adapter.calculate(7L, List.of("ENERGY_MANAGER"), "B1", "S1", "P1", DAY, DAY).getFirst();

        assertThat(result.status()).isEqualTo("UNCONFIGURED");
        verifyNoInteractions(raw);
    }

    @Test
    void rejectsCrossBuildingPointWithoutReadingRawEvents() {
        when(catalog.find("OTHER_BUILDING", Set.of("P1"))).thenReturn(List.of());

        var result = adapter.calculate(7L, List.of("ENERGY_MANAGER"), "OTHER_BUILDING", "S1", "P1", DAY, DAY)
                .getFirst();

        assertThat(result.status()).isEqualTo("UNCONFIGURED");
        verifyNoInteractions(raw);
    }

    @Test
    void rejectsMissingReaderRoleBeforeLookingUpTheObject() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                adapter.calculate(7L, List.of("VIEWER"), "B1", "S1", "P1", DAY, DAY))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(raw, catalog, configs, relations, quality, aggregation);
    }

    @Test
    void reportsMissingWhenRawHistoryIsEmpty() {
        when(raw.findPointHistory(eq("B1"), eq("E1"), eq("P1"), anyLong(), anyLong(), isNull(), eq(500)))
                .thenReturn(List.of());

        var result = adapter.calculate(7L, List.of("ENERGY_MANAGER"), "B1", "S1", "P1", DAY, DAY).getFirst();

        assertThat(result.status()).isEqualTo("MISSING");
        assertThat(result.energyKwh()).isNull();
        assertThat(result.reasons()).contains("RAW_HISTORY_EMPTY");
        verifyNoInteractions(quality, aggregation);
    }

    @Test
    void reportsAnUnclassifiedCumulativeDecreaseInsteadOfProducingEnergy() {
        when(raw.findPointHistory(eq("B1"), eq("E1"), eq("P1"), anyLong(), anyLong(), isNull(), eq(500)))
                .thenReturn(List.of(event(LEFT_MIDNIGHT, 100.0), event(RIGHT_MIDNIGHT, 90.0)));
        when(aggregation.aggregateCumulative(any())).thenThrow(new BusinessException(
                409, "ENERGY_AGGREGATION_NEGATIVE_DELTA_UNCLASSIFIED", "unclassified negative delta"));

        var result = adapter.calculate(7L, List.of("ENERGY_MANAGER"), "B1", "S1", "P1", DAY, DAY).getFirst();

        assertThat(result.status()).isEqualTo("UNRESOLVED");
        assertThat(result.energyKwh()).isNull();
        assertThat(result.reasons()).contains("CUMULATIVE_READING_DECREASE_UNRESOLVED");
    }

    @Test
    void passesApprovedResetEvidenceToTheNativeCumulativeCore() {
        when(raw.findPointHistory(eq("B1"), eq("E1"), eq("P1"), anyLong(), anyLong(), isNull(), eq(500)))
                .thenReturn(List.of(event(LEFT_MIDNIGHT, 100.0), event(RIGHT_MIDNIGHT, 10.0)));
        var reset = new MeterEventEvidence("ME1", "MEV1", "B1", "P1", MeterEventType.RESET,
                Instant.ofEpochMilli(RIGHT_MIDNIGHT), EvidenceStatus.APPROVED, new BigDecimal("100"),
                new BigDecimal("10"), null, null, null, "RV1", "RV1", "evidence", 9L, 10L, false);
        when(aggregation.governedEvidence(eq(7L), eq(List.of("ENERGY_MANAGER")), eq("B1"), eq("P1"),
                any(Instant.class), any(Instant.class)))
                .thenReturn(new NativeQuantityAggregationService.GovernedEvidence(List.of(reset), List.of()));

        var result = adapter.calculate(7L, List.of("ENERGY_MANAGER"), "B1", "S1", "P1", DAY, DAY).getFirst();

        assertThat(result.energyKwh()).isEqualByComparingTo("12.50");
        verify(aggregation).aggregateCumulative(argThat(request -> request.meterEvents().contains(reset)));
    }

    @Test
    void validatesObjectWithoutReadingRawHistory() {
        var validation = adapter.validateObject(7L, List.of("ENERGY_MANAGER"), "B1", "S1", "P1");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.unit()).isEqualTo("kWh");
        assertThat(validation.direction()).isEqualTo("INBOUND");
        assertThat(validation.relationVersion()).isEqualTo("RV1");
        assertThat(validation.relationEffectiveAt()).isEqualTo(
                LocalDateTime.of(2026, 1, 1, 0, 0).atZone(ZONE).toInstant().toEpochMilli());
        verifyNoInteractions(raw, quality, aggregation);
    }

    @Test
    void rejectsDatesBeforeTheCurrentRelationVersionBecameEffective() {
        var metadata = new QueryMetadata("B1", "RV1", 1, DAY.plusDays(1).atStartOfDay(), 3,
                0, false, 0, 0, 0, 0);
        when(relations.effectiveMeteringAssignments(7L, List.of("ENERGY_MANAGER"), "B1", 1, 500))
                .thenReturn(new MeteringAssignmentsView(metadata, 1, 500, 1, assignments().items()));

        var result = adapter.calculate(7L, List.of("ENERGY_MANAGER"), "B1", "S1", "P1", DAY, DAY).getFirst();

        assertThat(result.status()).isEqualTo("UNCONFIGURED");
        assertThat(result.reasons()).contains("RELATION_VERSION_NOT_EFFECTIVE_FOR_DAY");
        verifyNoInteractions(raw, quality, aggregation);
    }

    @Test void counterIdentityChangeIsNotTreatedAsOrdinaryPositiveConsumption() {
        RawTelemetryEvent changed=new RawTelemetryEvent("P1","PC1","device","external","replacement","B1","S1","E1","E1","F","C","EPP",110d,RIGHT_MIDNIGHT,RIGHT_MIDNIGHT,0,1,false);
        when(raw.findPointHistory(eq("B1"),eq("E1"),eq("P1"),anyLong(),anyLong(),isNull(),eq(500)))
                .thenReturn(List.of(event(LEFT_MIDNIGHT,100),changed));
        var result=adapter.calculate(7L,List.of("ENERGY_MANAGER"),"B1","S1","P1",DAY,DAY).getFirst();
        assertThat(result.energyKwh()).isNull();
        assertThat(result.reasons()).contains("SOURCE_IDENTITY_CHANGED_REQUIRES_REVIEW");
        verifyNoInteractions(aggregation);
    }

    private static MeteringAssignmentsView assignments() {
        var metadata = new QueryMetadata("B1", "RV1", 1, LocalDateTime.of(2026, 1, 1, 0, 0), 3,
                0, false, 0, 0, 0, 0);
        var assignment = new MeteringAssignmentView("A1", "ASSIGNED", null, null, "evidence",
                "MB1", "BND1", "购电表", "ELECTRICITY", "CONFIRMED", "ACTIVE",
                "MN1", "P1", "PC1", "购电表", "INDEPENDENT", "INBOUND", "CONFIRMED",
                "N1", "SYSTEM", "S1", "S1", "系统");
        return new MeteringAssignmentsView(metadata, 1, 500, 1, List.of(assignment));
    }

    private static PointProfile profile() {
        return new PointProfile("P1", "PC1", "kWh", "EP1", "ELECTRICITY", "GRID_PURCHASED",
                "CUMULATIVE", "CONFIRMED", 1);
    }

    private static PointRuntimeConfig config() {
        return new PointRuntimeConfig("P1", "PC1", "Meter", "B1", "S1", "E1", "E1", "F", "C", "EPP",
                "ANALOG", "kWh", "ONLINE", 1, null, null);
    }

    private static RawTelemetryEvent event(long at, double value) {
        return new RawTelemetryEvent("P1", "PC1", "device", "external", "E1", "B1", "S1", "E1", "E1",
                "F", "C", "EPP", value, at, at, 0, 1, false);
    }

    private static Resolution allowed(int qualityCode) {
        return new Resolution(Decision.ALLOW, qualityCode, ENERGY_ACTIVITY_AGGREGATION,
                PolicySource.PUBLISHED_POLICY, 4, 8, "QUALITY_ALLOWED");
    }
}
