package com.platform.iot.identity;

import com.platform.hvac.mapper.BizDeviceIdentityMapper;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.model.entity.BizDeviceIdentity;
import com.platform.hvac.model.entity.BizEquipment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MySqlDeviceIdentityProviderTest {

    @Mock private BizDeviceIdentityMapper identityMapper;
    @Mock private BizEquipmentMapper equipmentMapper;

    private MySqlDeviceIdentityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MySqlDeviceIdentityProvider(identityMapper, equipmentMapper);
    }

    @Test
    void rejectsLookupUntilFirstCompleteSnapshotIsLoaded() {
        assertThatThrownBy(() -> provider.find(
                new DeviceIdentityKey("MAC", "123456789012345")))
                .isInstanceOf(DeviceIdentitySnapshotUnavailableException.class);
    }

    @Test
    void resolvesEnabledIdentityToItsPreRegisteredEquipmentAndBuilding() {
        when(equipmentMapper.selectList(any())).thenReturn(List.of(equipment("BLD001")));
        when(identityMapper.selectList(any())).thenReturn(List.of(identity("BLD001", 1)));

        provider.refreshAll();

        assertThat(provider.find(new DeviceIdentityKey("mac", " 123456789012345 "))).get()
                .extracting(
                        DeviceIdentityBinding::equipmentId,
                        DeviceIdentityBinding::equipmentCode,
                        DeviceIdentityBinding::buildingId,
                        DeviceIdentityBinding::expectedProfileCode)
                .containsExactly("EQUIP001", "METER001", "BLD001", "ENERGY_METER_V1");
    }

    @Test
    void disabledIdentityIsNotAvailable() {
        when(equipmentMapper.selectList(any())).thenReturn(List.of(equipment("BLD001")));
        when(identityMapper.selectList(any())).thenReturn(List.of(identity("BLD001", 0)));

        provider.refreshAll();

        assertThat(provider.find(new DeviceIdentityKey("MAC", "123456789012345"))).isEmpty();
    }

    @Test
    void invalidOwnershipDoesNotReplaceLastCompleteSnapshot() {
        when(equipmentMapper.selectList(any()))
                .thenReturn(List.of(equipment("BLD001")))
                .thenReturn(List.of(equipment("BLD002")));
        when(identityMapper.selectList(any())).thenReturn(List.of(identity("BLD001", 1)));
        provider.refreshAll();

        provider.refreshAll();

        assertThat(provider.find(new DeviceIdentityKey("MAC", "123456789012345"))).isPresent();
    }

    private BizDeviceIdentity identity(String buildingId, int status) {
        BizDeviceIdentity identity = new BizDeviceIdentity();
        identity.setIdentityId("IDENTITY001");
        identity.setIdentityType("MAC");
        identity.setIdentityValue("123456789012345");
        identity.setEquipId("EQUIP001");
        identity.setBuildingId(buildingId);
        identity.setExpectedProfileCode("ENERGY_METER_V1");
        identity.setStatus(status);
        return identity;
    }

    private BizEquipment equipment(String buildingId) {
        BizEquipment equipment = new BizEquipment();
        equipment.setEquipId("EQUIP001");
        equipment.setEquipCode("METER001");
        equipment.setBuildingId(buildingId);
        equipment.setDelFlag(0);
        return equipment;
    }
}
