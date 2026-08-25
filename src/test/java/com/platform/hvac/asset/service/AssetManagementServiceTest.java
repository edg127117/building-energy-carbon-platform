package com.platform.hvac.asset.service;

import com.platform.framework.exception.BusinessException;
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

    private static BizSpace space(String id, String buildingId, String parentId) {
        BizSpace value = new BizSpace();
        value.setSpaceId(id);
        value.setBuildingId(buildingId);
        value.setParentSpaceId(parentId);
        return value;
    }
}
