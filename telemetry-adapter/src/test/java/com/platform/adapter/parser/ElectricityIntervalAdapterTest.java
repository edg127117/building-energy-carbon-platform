package com.platform.adapter.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.profile.*;
import com.platform.adapter.mqtt.TelemetryOutputSerializer;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ElectricityIntervalAdapterTest {
    private final JsonTelemetryParser parser=new JsonTelemetryParser(new ObjectMapper());
    private final ProtocolProfile profile=new ProtocolProfile("P","METER",1,"own/raw","SERIAL_NO","/id",null,null,"/time","/seq",null,"/boot",null,null,"ADAPTER_PROXY","BOOT_ID_AND_SEQ",true);
    private List<ProtocolFieldMapping> mappings() {
        return List.of(mapping("Q","ELECTRICITY_KWH","kWh",false),mapping("F","CO2_FACTOR","kgCO2/kWh",false),
                mapping("start","INTERVAL_START","ms",true),mapping("end","INTERVAL_END","ms",true),mapping("revision","INTERVAL_REVISION","1",true),mapping("T","DECIMAL","C",true));
    }
    private ProtocolFieldMapping mapping(String code,String type,String unit,boolean required) {
        return new ProtocolFieldMapping(code,"P","/"+code,code,type,unit,unit,BigDecimal.ONE,BigDecimal.ZERO,required,true,0);
    }
    private com.platform.adapter.model.StandardTelemetryMessage parse(int revision,String values) {
        String payload="{\"id\":\"A\",\"time\":1790784900000,\"boot\":\"B\",\"seq\":1,\"start\":1790784000000,\"end\":1790784900000,\"revision\":"+revision+",\"T\":20,"+values+"}";
        return parser.adapt("own/raw",payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),1790785000000L,profile,mappings());
    }
    @Test void preservesPeriodPrecisionAndKeepsOtherMetricsWithoutDuplicatingPeriod() {
        var m=parse(1,"\"Q\":12.123456789012,\"F\":0.543210987654321098");
        assertThat(m.electricityInterval().electricityKwh()).isEqualByComparingTo("12.123456789012");
        assertThat(m.electricityInterval().factorKgCo2PerKwh()).isEqualByComparingTo("0.543210987654321098");
        assertThat(m.metrics()).extracting(com.platform.adapter.model.StandardMetric::code).containsExactly("T");
    }
    @Test void repeatsKeepKeyButRevisionsAndSplitPartsGetDifferentKeys() {
        var full=parse(1,"\"Q\":1,\"F\":0.5");
        assertThat(parse(1,"\"Q\":1,\"F\":0.5").canonicalMessageId()).isEqualTo(full.canonicalMessageId());
        assertThat(parse(2,"\"Q\":1,\"F\":0.5").canonicalMessageId()).isNotEqualTo(full.canonicalMessageId());
        var q=parse(2,"\"Q\":1");var f=parse(2,"\"F\":0.5");
        assertThat(q.canonicalMessageId()).isNotEqualTo(f.canonicalMessageId());
        assertThat(q.electricityInterval().factorKgCo2PerKwh()).isNull();
    }
    @Test void refusesMissingTimesEmptyPairInvalidUnitsAndV1Downgrade() {
        assertThatThrownBy(()->parse(1,"\"Q\":null,\"F\":null")).isInstanceOf(TelemetryAdaptationException.class);
        var bad=new ArrayList<>(mappings());bad.set(1,mapping("F","CO2_FACTOR","kgCO2e/kWh",false));
        assertThatThrownBy(()->IntervalMappings.validate(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.adapt("own/raw","{\"id\":\"A\",\"Q\":1,\"F\":0.5,\"T\":20}".getBytes(),1790785000000L,profile,mappings())).isInstanceOf(TelemetryAdaptationException.class);
        var settings=new com.platform.adapter.profile.AdapterProfileProperties();settings.setOutputVersion("V1");
        assertThatThrownBy(()->new TelemetryOutputSerializer(new ObjectMapper(),settings).serialize(parse(1,"\"Q\":1,\"F\":0.5"))).hasMessageContaining("V2");
    }
}
