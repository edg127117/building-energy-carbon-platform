package com.platform.iot.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.identity.*;
import com.platform.iot.ingest.*;
import com.platform.iot.ingest.standard.*;
import com.platform.iot.quality.*;
import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.CorrelationPolicy;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 H2 通讯/HTTP 配置与显式接入存储替身贯穿；不调用 Broker、厂家或 TDengine。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommunicationIngressContractTest {
    @Autowired DeviceCommunicationService communication;
    @Autowired CommunicationEvidenceRecorder recorder;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test @Transactional
    void zeroPowerAndStorageFailureDoNotPreventCommunicationOnlineAndLedgerCountsAreActual() throws Exception {
        var identities=mock(DeviceIdentityProvider.class);
        var points=mock(DataPointConfigProvider.class);
        var storage=mock(HvacIngestionService.class);
        var unknown=mock(UnknownDeviceHandler.class);
        var key=new DeviceIdentityKey("MAC","TEST-MAC-WCR-B1");
        var binding=new DeviceIdentityBinding("IDENTITY_WCR_B1",key,"EQUIP_WCR_B1","WCR1","BLD001","HVAC_DEVICE_V1",AckMode.EVIDENCE_ONLY,CorrelationPolicy.NONE,null,null);
        when(identities.find(key)).thenReturn(Optional.of(binding));
        when(points.find(any())).thenReturn(Optional.of(new PointRuntimeConfig("POINT001","P1","功率","BLD001","GROUP001","EQUIP_WCR_B1","WCR1","WCR","MAIN","PPE","ANALOG","kW","ONLINE",0,BigDecimal.ZERO,BigDecimal.TEN)));
        var event=mock(RawTelemetryEvent.class);
        when(storage.prepare(anyMap(),anyLong(),anyString())).thenReturn(event);
        when(storage.writePrepared(any(),eq(true))).thenReturn(HvacIngestionResult.storageFailed("isolated TDengine failure"));
        var ingestion=new StandardTelemetryIngestionService(identities,unknown,points,storage,"MQTT_STANDARD_V1");
        ReflectionTestUtils.setField(ingestion,"communication",recorder);
        long now=System.currentTimeMillis();
        var message=new StandardTelemetryMessage("1.0","HVAC_DEVICE_V1",1,key,now,now,"DEVICE_REPORTED",1L,List.of(new StandardMetric("POWER",BigDecimal.ZERO,"kW","POWER")));
        var result=ingestion.ingest(message,now);
        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.RETRYABLE_FAILURE);
        assertThat(communication.equipment("EQUIP_WCR_B1","BLD001").value()).isEqualTo("ONLINE");
        verify(storage).writePrepared(event,true);
        String token=login();
        mvc.perform(get("/v1/assets/ledger").param("buildingId","BLD001").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.online").value(1))
                .andExpect(jsonPath("$.data.summary.offline").value(0)).andExpect(jsonPath("$.data.summary.notMonitored").value(3));
        communication.sourceHealth("MQTT","FAILED",System.currentTimeMillis());
        mvc.perform(get("/v1/assets/ledger").param("buildingId","BLD001").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.online").value(0))
                .andExpect(jsonPath("$.data.summary.communicationUnknown").value(1)).andExpect(jsonPath("$.data.summary.offline").value(0));
    }

    @Test @Transactional
    void authenticatedEnvelopeOutOfRangeIsContactButRetainedAndHistoricalReplayAreNot() {
        var identities=mock(DeviceIdentityProvider.class);
        var points=mock(DataPointConfigProvider.class);
        var storage=mock(HvacIngestionService.class);
        var key=new DeviceIdentityKey("MAC","TEST-MAC-WCR-B1");
        var binding=new DeviceIdentityBinding("IDENTITY_WCR_B1",key,"EQUIP_WCR_B1","WCR1","BLD001","HVAC_DEVICE_V1",AckMode.EVIDENCE_ONLY,CorrelationPolicy.NONE,null,null);
        when(identities.find(key)).thenReturn(Optional.of(binding));
        when(points.find(any())).thenReturn(Optional.of(new PointRuntimeConfig("POINT001","P1","功率","BLD001","GROUP001","EQUIP_WCR_B1","WCR1","WCR","MAIN","PPE","ANALOG","kW","ONLINE",0,BigDecimal.ZERO,BigDecimal.TEN)));
        var ingestion=new StandardTelemetryIngestionService(identities,mock(UnknownDeviceHandler.class),points,storage,"MQTT_STANDARD_V1");
        ReflectionTestUtils.setField(ingestion,"communication",recorder);
        long now=System.currentTimeMillis();
        var historical=new StandardTelemetryMessage("1.0","HVAC_DEVICE_V1",1,key,now-3_600_000,now-3_600_000,"DEVICE_REPORTED",0L,List.of(new StandardMetric("POWER",new BigDecimal("999"),"kW","POWER")));
        ingestion.ingest(historical,now);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_target",Integer.class)).isZero();
        var fresh=new StandardTelemetryMessage("1.0","HVAC_DEVICE_V1",1,key,now,now,"DEVICE_REPORTED",1L,List.of(new StandardMetric("POWER",new BigDecimal("999"),"kW","POWER")));
        recorder.beginMqtt(false,now);
        try { ingestion.ingest(fresh,now); } finally { recorder.endMqtt(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_target",Integer.class)).isZero();
        assertThat(ingestion.ingest(fresh,now).outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        assertThat(communication.equipment("EQUIP_WCR_B1","BLD001").value()).isEqualTo("ONLINE");
        verifyNoInteractions(storage);
    }

    @Test @Transactional
    void configurationApiEnforcesAuthenticationRevisionAndGatewayAudit() throws Exception {
        mvc.perform(get("/v1/assets/communication/policies")).andExpect(status().isUnauthorized());
        String token=login();
        String request="{\"periodSeconds\":300,\"monitored\":true,\"evidence\":\"隔离测试周期配置\",\"expectedRevision\":1}";
        mvc.perform(put("/v1/assets/communication/policies/DEFAULT").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        mvc.perform(put("/v1/assets/communication/policies/DEFAULT").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());
        mvc.perform(put("/v1/assets/communication/policies/PRODUCT:null").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/v1/assets/communication/identities/IDENTITY_WCR_B1/gateway").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayKey\":\"GW1\",\"evidence\":\"隔离网关关系\",\"expectedRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.gateway_key").value("GATEWAY:GW1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_policy_audit",Integer.class)).isEqualTo(2);
        mvc.perform(get("/v1/assets/communication/identities/IDENTITY_WCR_B1/history").header("Authorization","Bearer "+token))
                .andExpect(status().isOk());
    }
    @Test @Transactional
    void legacyRangeFailureDoesNotTurnACommunicatingDeviceOffline() {
        var validator=mock(TelemetryQualityValidator.class);
        var raw=mock(com.platform.iot.temporal.HvacRawEventRepository.class);
        when(validator.validate(anyMap(),anyLong(),eq("MQTT_FREEZE_V1"))).thenReturn(
                TelemetryValidationResult.reject(TelemetryRejectionReason.ABOVE_MAXIMUM,"isolated out of range"));
        var ingestion=new HvacIngestionService(validator,raw,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                mock(org.springframework.context.ApplicationEventPublisher.class),30,"MQTT_FREEZE_V1");
        ReflectionTestUtils.setField(ingestion,"communication",recorder);
        String pointCode=jdbc.queryForObject("SELECT source_point_code FROM biz_point_alias WHERE point_id='POINT001' AND source_system='MQTT_FREEZE_V1'",String.class);
        long now=System.currentTimeMillis();
        var result=ingestion.ingest(Map.of("buildingId","BLD001","deviceId","WCR1","pointCode",pointCode,"val",999,"timestamp",now),now,"MQTT_FREEZE_V1");
        assertThat(result.outcome()).isEqualTo(IngestionOutcome.REJECTED);
        assertThat(communication.equipment("EQUIP_WCR_B1","BLD001").value()).isEqualTo("ONLINE");
        verifyNoInteractions(raw);
    }
    private String login() throws Exception {
        return json.readTree(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
    }
}
