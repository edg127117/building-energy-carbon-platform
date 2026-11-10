package com.platform.iot.ingest.v2;

import com.platform.iot.identity.*;
import com.platform.iot.quality.*;
import com.platform.carbon.DynamicElectricityLiveService;
import com.platform.adapter.model.ElectricityInterval;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicElectricityV2InputTest {
    @Test void derivesFromDeclaredDeviceEndAndFreezesSourceOrderRatherThanReceiveTime() {
        var points=mock(DataPointConfigProvider.class);var live=mock(DynamicElectricityLiveService.class);
        var service=new DynamicElectricityV2Input(live,points,"MQTT_STANDARD_V1");
        var key=new DeviceIdentityKey("SERIAL_NO","A");var binding=new DeviceIdentityBinding("I",key,"E","E","B","METER",null,null,null,null);
        when(points.find(any(PointAliasKey.class))).thenReturn(Optional.of(new PointRuntimeConfig("SERVER_POINT","Q","Q","B",null,"E","E",null,null,null,"ANALOG","kWh","ONLINE",0,null,null)));
        var interval=new ElectricityInterval("Q",0,0,0,BigDecimal.ONE,BigDecimal.ONE,"kWh","kgCO2/kWh","SIMULATED","TEST","DEVICE_FIELDS");
        var message=new TelemetryV2Message("2.0","METER",1,key,"M","SOURCE","BOOT",5L,1790784900000L,System.currentTimeMillis(),null,null,null,null,"ADAPTER_DERIVED","DEVICE_REPORTED","DERIVED","EVIDENCE_ONLY","NONE",List.of(),interval);
        var request=service.prepare(message,binding);
        assertThat(request.startInclusive().toEpochMilli()).isEqualTo(1790784000000L);assertThat(request.endExclusive().toEpochMilli()).isEqualTo(1790784900000L);
        assertThat(request.revision()).isZero();assertThat(request.sourceOrder().seq()).isEqualTo(5);assertThat(request.sourceOrder().bootId()).isEqualTo("BOOT");
        var noTime=new TelemetryV2Message("2.0","METER",1,key,"M2",null,null,null,null,System.currentTimeMillis(),null,null,null,null,"ADAPTER_GENERATED","ADAPTER_RECEIVED","NONE","EVIDENCE_ONLY","NONE",List.of(),interval);
        assertThatThrownBy(()->service.prepare(noTime,binding)).hasMessageContaining("DEVICE_TIME_MISSING");
    }
    @Test void resolvesServerPointAndRefusesCrossBuildingAlias() {
        var points=mock(DataPointConfigProvider.class);var live=mock(DynamicElectricityLiveService.class);
        var service=new DynamicElectricityV2Input(live,points,"MQTT_STANDARD_V1");
        var key=new DeviceIdentityKey("SERIAL_NO","A");var binding=new DeviceIdentityBinding("I",key,"E","E","B","METER",null,null,null,null);
        var interval=new ElectricityInterval("Q",1790784000000L,1790784900000L,1,BigDecimal.ONE,BigDecimal.ONE,"kWh","kgCO2/kWh","SIMULATED","TEST");
        var message=new TelemetryV2Message("2.0","METER",1,key,"M",null,null,null,null,1790785000000L,null,null,null,null,"ADAPTER_GENERATED","ADAPTER_RECEIVED","NONE","EVIDENCE_ONLY","NONE",List.of(),interval);
        when(points.find(any(PointAliasKey.class))).thenReturn(Optional.of(new PointRuntimeConfig("SERVER_POINT","Q","Q","B",null,"E","E",null,null,null,"ANALOG","kWh","ONLINE",0,null,null)));
        assertThat(service.prepare(message,binding).pointId()).isEqualTo("SERVER_POINT");
        when(points.find(any(PointAliasKey.class))).thenReturn(Optional.of(new PointRuntimeConfig("FOREIGN_POINT","Q","Q","OTHER",null,"E","E",null,null,null,"ANALOG","kWh","ONLINE",0,null,null)));
        assertThatThrownBy(()->service.prepare(message,binding)).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        verifyNoInteractions(live);
    }
}
