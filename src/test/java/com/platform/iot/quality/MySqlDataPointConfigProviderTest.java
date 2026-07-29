package com.platform.iot.quality;

import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.mapper.BizPointAliasMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.BizPointAlias;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MySqlDataPointConfigProviderTest {

    @Mock private BizDataPointMapper pointMapper;
    @Mock private BizPointAliasMapper aliasMapper;
    @Mock private BizEquipmentMapper equipmentMapper;

    private MySqlDataPointConfigProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MySqlDataPointConfigProvider(pointMapper, aliasMapper, equipmentMapper);
    }

    @Test
    void refreshBuildsBuildingScopedAliasAndPointSnapshots() {
        when(equipmentMapper.selectList(any())).thenReturn(List.of(equipment()));
        when(pointMapper.selectList(any())).thenReturn(List.of(point()));
        when(aliasMapper.selectList(any())).thenReturn(List.of(alias()));

        provider.refreshAll();

        PointAliasKey key = new PointAliasKey("BLD001", "MQTT_FREEZE_V1", "WCR1_Flow");
        assertThat(provider.find(key)).get()
                .extracting(PointRuntimeConfig::pointCode).isEqualTo("WCR1_GW");
        assertThat(provider.find(key)).get()
                .extracting(
                        PointRuntimeConfig::dataType,
                        PointRuntimeConfig::unit)
                .containsExactly("ANALOG", "m³/h");
        assertThat(provider.find(new PointAliasKey(
                "BLD002", "MQTT_FREEZE_V1", "WCR1_Flow"))).isEmpty();
        assertThat(provider.findByPointId("POINT001")).isPresent();
        assertThat(provider.findAll()).extracting(PointRuntimeConfig::pointId)
                .containsExactly("POINT001");
    }

    @Test
    void failedFullRefreshKeepsLastKnownGoodSnapshot() {
        when(equipmentMapper.selectList(any())).thenReturn(List.of(equipment()));
        when(pointMapper.selectList(any()))
                .thenReturn(List.of(point()))
                .thenThrow(new IllegalStateException("mysql unavailable"));
        when(aliasMapper.selectList(any())).thenReturn(List.of(alias()));
        provider.refreshAll();

        provider.refreshAll();

        assertThat(provider.find(new PointAliasKey(
                "BLD001", "MQTT_FREEZE_V1", "WCR1_Flow"))).isPresent();
    }

    private BizDataPoint point() {
        BizDataPoint point = new BizDataPoint();
        point.setPointId("POINT001");
        point.setPointCode("WCR1_GW");
        point.setPointName("冷冻水流量");
        point.setBuildingId("BLD001");
        point.setSystemGroupId("GROUP001");
        point.setEquipId("EQUIP001");
        point.setFamilyCode("WCR");
        point.setComponentCode("MAIN");
        point.setSuffixCode("GW");
        point.setDataType("ANALOG");
        point.setUnit("m³/h");
        point.setIsForCalc(1);
        point.setStatus("ONLINE");
        return point;
    }

    private BizEquipment equipment() {
        BizEquipment equipment = new BizEquipment();
        equipment.setEquipId("EQUIP001");
        equipment.setEquipCode("WCR1");
        return equipment;
    }

    private BizPointAlias alias() {
        BizPointAlias alias = new BizPointAlias();
        alias.setAliasId("ALIAS001");
        alias.setBuildingId("BLD001");
        alias.setSourceSystem("MQTT_FREEZE_V1");
        alias.setSourcePointCode("WCR1_Flow");
        alias.setPointId("POINT001");
        alias.setStatus(1);
        return alias;
    }
}
