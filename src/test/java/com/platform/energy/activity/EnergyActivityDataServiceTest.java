package com.platform.energy.activity;

import com.platform.energy.activity.EnergyActivityDataReader.Cursor;
import com.platform.energy.activity.EnergyActivityDataReader.RawEvent;
import com.platform.energy.activity.EnergyActivityDataReader.RawEventPage;
import com.platform.energy.activity.EnergyActivityDataContracts.RawActivityDataView;
import com.platform.energy.activity.EnergyActivityPointCatalog.PointProfile;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.PolicySource;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.platform.energy.activity.EnergyActivityDataErrors.DATA_SCOPE_MISMATCH;
import static com.platform.energy.activity.EnergyActivityDataErrors.POINT_PROFILE_UNCONFIRMED;
import static com.platform.iot.qualityusage.QualityUsageModels.ENERGY_ACTIVITY_AGGREGATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnergyActivityDataServiceTest {
    private BuildingScopeService buildingScope;
    private EnergyActivityPointCatalog pointCatalog;
    private EnergyActivityDataReader dataReader;
    private QualityUsagePolicyResolver qualityResolver;
    private ResolutionContext context;
    private EnergyActivityDataService service;

    @BeforeEach
    void setUp() {
        buildingScope = mock(BuildingScopeService.class);
        pointCatalog = mock(EnergyActivityPointCatalog.class);
        dataReader = mock(EnergyActivityDataReader.class);
        qualityResolver = mock(QualityUsagePolicyResolver.class);
        context = mock(ResolutionContext.class);
        service = new EnergyActivityDataService(
                buildingScope, pointCatalog, dataReader, qualityResolver);
    }

    @Test
    void returnsOnlyQualityAllowedRowsWithProfileAndPolicyEvidence() {
        Set<String> pointIds = Set.of("POINT001", "POINT002");
        when(pointCatalog.find("BLD001", pointIds)).thenReturn(List.of(
                profile("POINT001", "kWh", "ELECTRICITY", "GRID_PURCHASED", "CONFIRMED"),
                profile("POINT002", "Nm3", "NATURAL_GAS", "PIPELINE", "CONFIRMED")));
        when(qualityResolver.historyContext(
                pointIds, ENERGY_ACTIVITY_AGGREGATION, 60_000L, 180_000L))
                .thenReturn(context);
        RawEvent q0 = event("POINT001", "BLD001", 60_000L, 0);
        RawEvent q1 = event("POINT002", "BLD001", 120_000L, 1);
        Cursor next = new Cursor(120_000L, "POINT002");
        when(dataReader.readRawEvents(
                "BLD001", pointIds, 60_000L, 180_000L, null, 200))
                .thenReturn(new RawEventPage(List.of(q0, q1), true, next));
        when(qualityResolver.resolve(context, "POINT001",
                ENERGY_ACTIVITY_AGGREGATION, 60_000L, 0))
                .thenReturn(resolution(Decision.ALLOW, 0));
        when(qualityResolver.resolve(context, "POINT002",
                ENERGY_ACTIVITY_AGGREGATION, 120_000L, 1))
                .thenReturn(resolution(Decision.BLOCK, 1));

        var page = service.rawEvents(
                7L, Set.of("BUILDING_OWNER"), "BLD001",
                List.of("POINT001", "POINT002"), 60_000L, 180_000L,
                null, null, 200);

        assertThat(page.factKind()).isEqualTo("RAW_EVENT");
        assertThat(page.identityRule()).isEqualTo("POINT_ID_EVENT_TIME");
        assertThat(page.duplicateHandling()).isEqualTo("COALESCED_BY_IDENTITY");
        assertThat(page.correctionEvidence()).isEqualTo("NOT_RETAINED");
        assertThat(page.scannedCount()).isEqualTo(2);
        assertThat(page.blockedCount()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.pointId()).isEqualTo("POINT001");
            assertThat(item.unit()).isEqualTo("kWh");
            assertThat(item.qualityLevel()).isEqualTo("Q0");
            assertThat(item.policySource()).isEqualTo("SYSTEM_DEFAULT_Q0_ONLY");
            assertThat(item.policyConfigRevision()).isEqualTo(7);
        });
        assertThat(page.nextCursor().pointId()).isEqualTo("POINT002");
        verify(buildingScope).checkAccess(7L, Set.of("BUILDING_OWNER"), "BLD001");
    }

    @Test
    void rejectsUnconfirmedEnergyProfileBeforeReadingTdengine() {
        Set<String> pointIds = Set.of("POINT001");
        when(pointCatalog.find("BLD001", pointIds)).thenReturn(List.of(
                profile("POINT001", "kWh", "ELECTRICITY", "GRID_PURCHASED", "PENDING")));

        assertThatThrownBy(() -> service.rawEvents(
                7L, Set.of("ENERGY_MANAGER"), "BLD001",
                List.of("POINT001"), 60_000L, 180_000L,
                null, null, 200))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getErrorCode()).isEqualTo(POINT_PROFILE_UNCONFIRMED);
                });
        verify(dataReader, never()).readRawEvents(
                any(), anySet(), anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void failsClosedWhenTdengineReturnsAnotherBuilding() {
        Set<String> pointIds = Set.of("POINT001");
        when(pointCatalog.find("BLD001", pointIds)).thenReturn(List.of(
                profile("POINT001", "kWh", "ELECTRICITY", "GRID_PURCHASED", "CONFIRMED")));
        when(qualityResolver.historyContext(anySet(), eq(ENERGY_ACTIVITY_AGGREGATION),
                anyLong(), anyLong())).thenReturn(context);
        when(dataReader.readRawEvents(
                "BLD001", pointIds, 60_000L, 180_000L, null, 200))
                .thenReturn(new RawEventPage(
                        List.of(event("POINT001", "BLD999", 60_000L, 0)), false, null));

        assertThatThrownBy(() -> service.rawEvents(
                7L, Set.of("PLATFORM_ADMIN"), "BLD001",
                List.of("POINT001"), 60_000L, 180_000L,
                null, null, 200))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(500);
                    assertThat(exception.getErrorCode()).isEqualTo(DATA_SCOPE_MISMATCH);
                });
        verify(qualityResolver, never()).resolve(
                any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void aggregationSnapshotIncludesStartAnchorAndExactEndBoundaryAtFixedWatermark() {
        Set<String> pointIds = Set.of("POINT001");
        PointProfile profile = new PointProfile("POINT001", "POINT001_CODE", "kWh",
                "PROFILE_POINT001", "ELECTRICITY", "GRID_PURCHASED", "CUMULATIVE",
                "CONFIRMED", 3);
        when(pointCatalog.find("BLD001", pointIds)).thenReturn(List.of(profile));
        RawEvent anchor = event("POINT001", "BLD001", 50L, 0);
        RawEvent start = event("POINT001", "BLD001", 100L, 0);
        RawEvent end = event("POINT001", "BLD001", 200L, 0);
        RawEvent arrivedAfterWatermark = new RawEvent("POINT001", "POINT001_CODE", "BLD001",
                "SIMULATION_V1", "POINT001_SOURCE", "SIM_DEVICE", 15.0,
                150L, 301L, 0, true);
        when(dataReader.readLatestAtOrBefore("BLD001", "POINT001", 100L)).thenReturn(anchor);
        when(dataReader.readRawEvents("BLD001", pointIds, 100L, 201L, null, 500))
                .thenReturn(new RawEventPage(List.of(start, arrivedAfterWatermark, end), false, null));
        when(qualityResolver.historyContext(pointIds, ENERGY_ACTIVITY_AGGREGATION, 50L, 201L))
                .thenReturn(context);
        when(qualityResolver.resolve(eq(context), eq("POINT001"),
                eq(ENERGY_ACTIVITY_AGGREGATION), anyLong(), eq(0)))
                .thenReturn(resolution(Decision.ALLOW, 0));

        var snapshot = service.aggregationSnapshot(7L, Set.of("ENERGY_MANAGER"),
                "BLD001", "POINT001", 100L, 200L, 300L);

        assertThat(snapshot.activityWatermark()).isEqualTo(300L);
        assertThat(snapshot.valueSemantics()).isEqualTo("CUMULATIVE");
        assertThat(snapshot.items()).extracting(RawActivityDataView::eventTime)
                .containsExactly(50L, 100L, 200L);
    }

    private static PointProfile profile(
            String pointId, String unit, String type, String subtype, String status) {
        return new PointProfile(
                pointId, pointId + "_CODE", unit, "PROFILE_" + pointId,
                type, subtype, "PERIOD_QUANTITY", status, 3);
    }

    private static RawEvent event(
            String pointId, String buildingId, long eventTime, int quality) {
        return new RawEvent(
                pointId, pointId + "_CODE", buildingId,
                "SIMULATION_V1", pointId + "_SOURCE", "SIM_DEVICE",
                12.3, eventTime, eventTime + 100, quality, false);
    }

    private static Resolution resolution(Decision decision, int quality) {
        return new Resolution(
                decision, quality, ENERGY_ACTIVITY_AGGREGATION,
                PolicySource.SYSTEM_DEFAULT_Q0_ONLY, null, 7,
                decision == Decision.ALLOW
                        ? "NO_ACTIVE_POLICY_DEFAULT" : "QUALITY_NOT_ALLOWED_BY_DEFAULT");
    }
}
