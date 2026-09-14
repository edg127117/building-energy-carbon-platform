package com.platform.hvac.asset.service;

import com.platform.framework.exception.BusinessException;
import com.platform.framework.common.Result;
import com.platform.hvac.asset.api.AssetManagementContracts;
import com.platform.hvac.mapper.BizDeviceIdentityMapper;
import com.platform.hvac.mapper.BizPointAliasMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizDeviceIdentity;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.BizSpace;
import com.platform.hvac.model.entity.Building;
import com.platform.hvac.service.BizDataPointService;
import com.platform.hvac.service.BizEquipmentService;
import com.platform.hvac.service.BizSpaceService;
import com.platform.hvac.service.BizSystemGroupService;
import com.platform.hvac.service.BuildingService;
import com.platform.iot.onboarding.mapper.BizDeviceProductMapper;
import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import com.platform.iot.onboarding.mapper.BizProductPointTemplateMapper;
import com.platform.iot.onboarding.model.entity.BizProductPointTemplate;
import com.platform.iot.deviceparameter.DeviceParameterLegacyCompatibilityService;
import com.platform.iot.qualityusage.QualityUsageModels;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.LatestRawReading;
import com.platform.system.mapper.SysUserBuildingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetManagementServiceTest {
    @Mock private BuildingService buildingService;
    @Mock private BizSpaceService spaceService;
    @Mock private BizSystemGroupService systemGroupService;
    @Mock private BizEquipmentService equipmentService;
    @Mock private BizDataPointService dataPointService;
    @Mock private BizDeviceIdentityMapper identityMapper;
    @Mock private BizPointAliasMapper aliasMapper;
    @Mock private BizDeviceProductMapper productMapper;
    @Mock private BizProductPointTemplateMapper productPointMapper;
    @Mock private BizPendingDeviceMapper pendingMapper;
    @Mock private SysUserBuildingMapper userBuildingMapper;
    @Mock private DeviceParameterLegacyCompatibilityService parameterCompatibilityService;
    @Mock private HvacRawEventRepository rawEventRepository;
    @Mock private QualityUsagePolicyResolver qualityUsageResolver;

    @InjectMocks private AssetManagementService service;

    @Test
    void blocksBuildingDeletionWhenAnyBusinessReferenceExists() {
        Building building = new Building();
        building.setBuildingId("B1");
        when(buildingService.getById("B1")).thenReturn(building);
        when(spaceService.count(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteBuilding("B1", List.of("PLATFORM_ADMIN")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getErrorCode()).isEqualTo(AssetErrors.REFERENCE_CONFLICT);
                });
        verify(buildingService, never()).delete("B1");
    }

    @Test
    void keepsChildCreationAvailableWhenBuildingReferencesBlockDeletion() {
        Building building = new Building();
        building.setBuildingId("B1");
        when(buildingService.getById("B1")).thenReturn(building);
        when(spaceService.count(any())).thenReturn(1L);

        var result = service.buildingDetail("B1", List.of("PLATFORM_ADMIN"));

        assertThat(result.allowedActions()).containsExactly("CREATE", "UPDATE");
    }

    @Test
    void rejectsDeepSpaceAncestorCycleBeforeCallingLegacyUpdate() {
        BizSpace current = space("S1", "B1", null);
        BizSpace child = space("S2", "B1", "S1");
        when(spaceService.getById("S1")).thenReturn(current);
        when(spaceService.getById("S2")).thenReturn(child);

        AssetManagementContracts.SpaceUpdateRequest request =
                new AssetManagementContracts.SpaceUpdateRequest(
                        "S2", "机房", "R1", "ROOM", 1, null, "ACTIVE");

        assertThatThrownBy(() -> service.updateSpace(
                "S1", request, List.of("PLATFORM_ADMIN")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getErrorCode()).isEqualTo(AssetErrors.STATE_CONFLICT);
                });
        verify(spaceService, never()).update(any(BizSpace.class));
    }

    @Test
    void preservesUnknownIdentityStatusForSafeFrontendFallback() {
        BizEquipment equipment = new BizEquipment();
        equipment.setEquipId("E1");
        equipment.setEquipCode("AHU1");
        equipment.setEquipName("一号空调箱");
        equipment.setBuildingId("B1");
        when(equipmentService.list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<BizEquipment>>any()))
                .thenReturn(List.of(equipment));

        BizDeviceIdentity identity = new BizDeviceIdentity();
        identity.setIdentityId("I1");
        identity.setStatus(7);
        when(identityMapper.selectList(any())).thenReturn(List.of(identity));
        when(pendingMapper.selectList(any())).thenReturn(List.of());

        var result = service.listEquipment(1, 20, null, null, null,
                null, null, "UNKNOWN", null, List.of("PLATFORM_ADMIN"));

        assertThat(result.items()).singleElement()
                .extracting(AssetManagementContracts.EquipmentListItemView::status)
                .isEqualTo("UNKNOWN");
    }

    @Test
    void countsOnlyConfiguredRequiredPointSuffixes() {
        BizEquipment equipment = new BizEquipment();
        equipment.setEquipId("E1");
        equipment.setBuildingId("B1");
        equipment.setProductId("P1");
        when(equipmentService.getById("E1")).thenReturn(equipment);
        when(identityMapper.selectList(any())).thenReturn(List.of());
        when(parameterCompatibilityService.read(equipment)).thenReturn(
                new DeviceParameterLegacyCompatibilityService.LegacyProjection(
                        null, null, null, "LEGACY_UNGOVERNED"));

        BizProductPointTemplate required = new BizProductPointTemplate();
        required.setSuffixCode("temp");
        when(productPointMapper.selectList(any())).thenReturn(List.of(required));

        BizDataPoint unrelated = new BizDataPoint();
        unrelated.setSuffixCode("humidity");
        when(dataPointService.list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<BizDataPoint>>any()))
                .thenReturn(List.of(unrelated));

        var result = service.equipmentDetail("E1", List.of("PLATFORM_ADMIN"));

        assertThat(result.pointSummary().total()).isEqualTo(1);
        assertThat(result.pointSummary().required()).isEqualTo(1);
        assertThat(result.pointSummary().configuredRequired()).isZero();
    }

    @Test
    void rejectsReadingQueryBeforeAccessingEquipmentForNonAdmin() {
        assertThatThrownBy(() -> service.equipmentReadings("E1", List.of("BUILDING_OWNER")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo("ASSET_FORBIDDEN"));
        verify(equipmentService, never()).getById(any());
        verify(rawEventRepository, never()).findLatestByEquipmentPoints(any(), any(), any());
    }

    @Test
    void returnsNoDataAndDisabledWithoutCallingQualityPolicy() {
        BizEquipment equipment = equipment("E1", "B1");
        BizDataPoint enabled = point("P1", "IDU1_EPP", "EPP", "kWh", "ONLINE");
        BizDataPoint disabled = point("P2", "IDU1_EPN", "EPN", "kWh", "OFFLINE");
        when(equipmentService.getById("E1")).thenReturn(equipment);
        when(dataPointService.listByEquip("E1")).thenReturn(Result.success(List.of(enabled, disabled)));
        when(rawEventRepository.findLatestByEquipmentPoints("B1", "E1", List.of("P1")))
                .thenReturn(List.of());

        var result = service.equipmentReadings("E1", List.of("PLATFORM_ADMIN"));

        assertThat(result.points()).extracting(AssetManagementContracts.PointReadingView::status)
                .containsExactly("DISABLED", "NO_DATA");
        assertThat(result.points()).allSatisfy(reading -> assertThat(reading.value()).isNull());
        verify(qualityUsageResolver, never()).runtimeContext();
    }

    @Test
    void preservesZeroAndCumulativeRawValueWithExactBuildingEquipmentAndPointScope() {
        BizEquipment equipment = equipment("E1", "B1");
        BizDataPoint power = point("P1", "IDU1_P", "P", "kW", "ONLINE");
        BizDataPoint cumulative = point("P2", "IDU1_EPP", "EPP", "kWh", "ONLINE");
        when(equipmentService.getById("E1")).thenReturn(equipment);
        when(dataPointService.listByEquip("E1"))
                .thenReturn(Result.success(List.of(power, cumulative)));
        when(rawEventRepository.findLatestByEquipmentPoints(
                "B1", "E1", List.of("P2", "P1")))
                .thenReturn(List.of(
                        new LatestRawReading("P1", 0.0, 1_800_000_000_123L,
                                1_800_000_001_000L, 0),
                        new LatestRawReading("P2", 12345.678, 1_800_000_060_123L,
                                1_800_000_061_000L, 0)));
        when(qualityUsageResolver.runtimeContext()).thenReturn(null);
        when(qualityUsageResolver.resolve(
                org.mockito.ArgumentMatchers.nullable(QualityUsageModels.ResolutionContext.class),
                any(), org.mockito.ArgumentMatchers.eq("POINT_REALTIME_VIEW"),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new QualityUsageModels.Resolution(
                        QualityUsageModels.Decision.ALLOW, 0, "POINT_REALTIME_VIEW",
                        QualityUsageModels.PolicySource.SYSTEM_DEFAULT_Q0_ONLY,
                        null, 1, "QUALITY_ALLOWED"));

        var result = service.equipmentReadings("E1", List.of("PLATFORM_ADMIN"));

        assertThat(result.buildingId()).isEqualTo("B1");
        assertThat(result.points()).extracting(AssetManagementContracts.PointReadingView::value)
                .containsExactly(12345.678, 0.0);
        assertThat(result.points()).extracting(AssetManagementContracts.PointReadingView::status)
                .containsOnly("HAS_DATA");
        assertThat(result.points().getFirst().eventTime()).isEqualTo(1_800_000_060_123L);
    }

    @Test
    void hidesQualityBlockedRawValueButKeepsItsEvidenceTimesAndStatus() {
        BizEquipment equipment = equipment("E1", "B1");
        BizDataPoint point = point("P1", "IDU1_P", "P", "kW", "ONLINE");
        when(equipmentService.getById("E1")).thenReturn(equipment);
        when(dataPointService.listByEquip("E1")).thenReturn(Result.success(List.of(point)));
        when(rawEventRepository.findLatestByEquipmentPoints("B1", "E1", List.of("P1")))
                .thenReturn(List.of(new LatestRawReading(
                        "P1", 8.5, 1_800_000_000_123L, 1_800_000_001_000L, 2)));
        when(qualityUsageResolver.runtimeContext()).thenReturn(null);
        when(qualityUsageResolver.resolve(
                org.mockito.ArgumentMatchers.nullable(QualityUsageModels.ResolutionContext.class),
                org.mockito.ArgumentMatchers.eq("P1"),
                org.mockito.ArgumentMatchers.eq("POINT_REALTIME_VIEW"),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(new QualityUsageModels.Resolution(
                        QualityUsageModels.Decision.BLOCK, 2, "POINT_REALTIME_VIEW",
                        QualityUsageModels.PolicySource.SYSTEM_DEFAULT_Q0_ONLY,
                        null, 1, "QUALITY_NOT_ALLOWED_BY_DEFAULT"));

        var reading = service.equipmentReadings("E1", List.of("PLATFORM_ADMIN"))
                .points().getFirst();

        assertThat(reading.status()).isEqualTo("HAS_DATA");
        assertThat(reading.value()).isNull();
        assertThat(reading.usageStatus()).isEqualTo("QUALITY_BLOCKED");
        assertThat(reading.reason()).isEqualTo("QUALITY_NOT_ALLOWED_BY_DEFAULT");
        assertThat(reading.eventTime()).isEqualTo(1_800_000_000_123L);
    }

    @Test
    void propagatesTdengineAndPolicyFailuresInsteadOfReturningNoData() {
        BizEquipment equipment = equipment("E1", "B1");
        BizDataPoint point = point("P1", "IDU1_P", "P", "kW", "ONLINE");
        when(equipmentService.getById("E1")).thenReturn(equipment);
        when(dataPointService.listByEquip("E1")).thenReturn(Result.success(List.of(point)));
        when(rawEventRepository.findLatestByEquipmentPoints("B1", "E1", List.of("P1")))
                .thenThrow(new IllegalStateException("TDENGINE_UNAVAILABLE"))
                .thenReturn(List.of(new LatestRawReading(
                        "P1", 8.5, 1_800_000_000_123L, 1_800_000_001_000L, 0)));

        assertThatThrownBy(() -> service.equipmentReadings("E1", List.of("PLATFORM_ADMIN")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TDENGINE_UNAVAILABLE");

        when(qualityUsageResolver.runtimeContext())
                .thenThrow(new IllegalStateException("POLICY_SNAPSHOT_UNAVAILABLE"));
        assertThatThrownBy(() -> service.equipmentReadings("E1", List.of("PLATFORM_ADMIN")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("POLICY_SNAPSHOT_UNAVAILABLE");
    }

    private static BizSpace space(String id, String buildingId, String parentId) {
        BizSpace value = new BizSpace();
        value.setSpaceId(id);
        value.setBuildingId(buildingId);
        value.setParentSpaceId(parentId);
        return value;
    }

    private static BizEquipment equipment(String id, String buildingId) {
        BizEquipment value = new BizEquipment();
        value.setEquipId(id);
        value.setBuildingId(buildingId);
        return value;
    }

    private static BizDataPoint point(
            String id, String code, String suffix, String unit, String status) {
        BizDataPoint value = new BizDataPoint();
        value.setPointId(id);
        value.setEquipId("E1");
        value.setBuildingId("B1");
        value.setPointCode(code);
        value.setPointName(code);
        value.setSuffixCode(suffix);
        value.setUnit(unit);
        value.setStatus(status);
        return value;
    }
}
