package com.platform.iot.ingest.standard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.carbon.DynamicElectricityLiveService;
import com.platform.carbon.DynamicElectricityContracts.DeviceIntervalRequest;
import com.platform.framework.exception.BusinessException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 标准主题真实反序列化与校验，计算依赖用替身验证持久失败不能确认。 */
class StandardDynamicElectricityIngestionServiceTest {
    private DynamicElectricityLiveService live;
    private StandardTelemetryIngestionService numeric;
    private StandardTelemetryMqttMessageHandler handler;
    private jakarta.validation.ValidatorFactory factory;
    private ObjectMapper json;
    private final Instant from=Instant.parse("2026-10-01T00:00:00Z");

    @BeforeEach void setup() {
        live=mock(DynamicElectricityLiveService.class);numeric=mock(StandardTelemetryIngestionService.class);
        json=new ObjectMapper().findAndRegisterModules();factory=Validation.buildDefaultValidatorFactory();
        handler=new StandardTelemetryMqttMessageHandler(json,numeric,new StandardDynamicElectricityIngestionService(live,factory.getValidator()));
    }
    @AfterEach void close(){factory.close();}
    private Map<String,Object> payload() {
        var interval=new LinkedHashMap<String,Object>();
        interval.put("pointId","P");interval.put("startInclusive",from.toString());interval.put("endExclusive",from.plusSeconds(900).toString());
        interval.put("revision",1);interval.put("electricityKwh","12.345");interval.put("factorKgCo2PerKwh","0.54321");
        interval.put("electricityUnit","kWh");interval.put("factorUnit","kgCO2/kWh");interval.put("sourceNature","SIMULATED");interval.put("sourceReference","MQTT fixture");
        var envelope=new LinkedHashMap<String,Object>();envelope.put("standardVersion","1.0");envelope.put("profileCode","METER");
        envelope.put("profileVersion",1);envelope.put("deviceIdentity",Map.of("type","SERIAL_NO","value","M1"));
        envelope.put("eventTime",from.plusSeconds(900).toEpochMilli());envelope.put("receivedTime",from.plusSeconds(900).toEpochMilli());
        envelope.put("timeSource","DEVICE_REPORTED");envelope.put("seq",1);envelope.put("metrics",List.of());envelope.put("electricityInterval",interval);
        return envelope;
    }
    private StandardTelemetryResult handle(Map<String,Object> body)throws Exception{return handler.handle(json.writeValueAsBytes(body),System.currentTimeMillis());}
    @SuppressWarnings("unchecked") private Map<String,Object> interval(Map<String,Object> body){return (Map<String,Object>)body.get("electricityInterval");}

    @Test void routesExistingStandardTopicWithExactDecimalsAndNoHttpUser()throws Exception {
        var result=handle(payload());assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.ACCEPTED);
        assertThat(result.processedMetrics()).isEqualTo(2);assertThat(result.shouldAcknowledge()).isTrue();
        var request=org.mockito.ArgumentCaptor.forClass(DeviceIntervalRequest.class);verify(live).ingestFromMqtt(request.capture());
        assertThat(request.getValue().electricityKwh()).isEqualByComparingTo("12.345");assertThat(request.getValue().factorKgCo2PerKwh()).isEqualByComparingTo("0.54321");
        verifyNoInteractions(numeric);
    }
    @Test void acceptsSeparatedFactorAndQuantity()throws Exception {
        var body=payload();interval(body).remove("factorKgCo2PerKwh");assertThat(handle(body).processedMetrics()).isEqualTo(1);
        body=payload();interval(body).remove("electricityKwh");assertThat(handle(body).processedMetrics()).isEqualTo(1);
        verify(live,times(2)).ingestFromMqtt(any());
    }
    @Test void derivesIntervalFromDeviceEventTimeWithoutExtraHardwareFields()throws Exception {
        var body=payload();interval(body).remove("startInclusive");interval(body).remove("endExclusive");interval(body).remove("revision");
        body.put("bootId","DEVICE_BOOT");body.put("sourceMessageId","DEVICE_MESSAGE");
        assertThat(handle(body).outcome()).isEqualTo(StandardTelemetryOutcome.ACCEPTED);
        var capture=org.mockito.ArgumentCaptor.forClass(DeviceIntervalRequest.class);verify(live).ingestFromMqtt(capture.capture());
        var request=capture.getValue();assertThat(request.startInclusive()).isEqualTo(from);assertThat(request.endExclusive()).isEqualTo(from.plusSeconds(900));
        assertThat(request.revision()).isZero();assertThat(request.sourceOrder().bootId()).isEqualTo("DEVICE_BOOT");assertThat(request.sourceOrder().seq()).isEqualTo(1);
        clearInvocations(live);body.put("timeSource","SERVER_RECEIVED");
        var missing=handle(body);assertThat(missing.outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        assertThat(missing.detail()).contains("DEVICE_TIME_MISSING");verifyNoInteractions(live);
        body.put("timeSource","DEVICE_REPORTED");body.put("eventTime",from.plusSeconds(901).toEpochMilli());
        assertThat(handle(body).outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);verifyNoInteractions(live);
    }
    @Test void rejectsWrongUnitsNegativeValuesMixedMetricsAndBrokenEnvelope()throws Exception {
        var body=payload();interval(body).put("factorUnit","kgCO2e/kWh");assertThat(handle(body).outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        body=payload();interval(body).put("electricityKwh","-1");assertThat(handle(body).outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        body=payload();body.put("metrics",List.of(Map.of("code","Q","value",1,"unit","kWh","sourceField","/Q")));assertThat(handle(body).outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        body=payload();body.put("standardVersion","2.0");assertThat(handle(body).outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        verifyNoInteractions(live,numeric);
    }
    @Test void keepsDatabaseFailuresRetryableButPermanentlyRejectsBadScope()throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("storage")).when(live).ingestFromMqtt(any());
        assertThat(handle(payload()).shouldAcknowledge()).isFalse();
        doThrow(new BusinessException(503,"NOT_READY","dependency")).when(live).ingestFromMqtt(any());assertThat(handle(payload()).shouldAcknowledge()).isFalse();
        doThrow(new BusinessException(409,"DYNAMIC_DEVICE_SCOPE","scope")).when(live).ingestFromMqtt(any());
        var result=handle(payload());assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);assertThat(result.shouldAcknowledge()).isTrue();
    }
    @Test void preservesOldNumericSerializationAndRouting()throws Exception {
        var body=payload();body.remove("electricityInterval");body.put("metrics",List.of(Map.of("code","Q","value",1,"unit","kWh","sourceField","/Q")));
        when(numeric.ingest(any(),anyLong())).thenReturn(StandardTelemetryResult.accepted(1));assertThat(handle(body).processedMetrics()).isEqualTo(1);
        verifyNoInteractions(live);
        var old=json.readValue(json.writeValueAsBytes(body),StandardTelemetryMessage.class);
        assertThat(json.readTree(json.writeValueAsBytes(old)).has("electricityInterval")).isFalse();
    }
}
