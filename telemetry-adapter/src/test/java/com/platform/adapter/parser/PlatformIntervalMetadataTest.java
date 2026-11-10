package com.platform.adapter.parser;

import com.platform.adapter.model.ElectricityInterval;
import com.platform.adapter.profile.ProtocolFieldMapping;
import com.platform.adapter.model.StandardMetric;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 只用设备边界时间推导周期，不调用Broker或设备。 */
class PlatformIntervalMetadataTest {
    private static final long END=1790784900000L;
    @Test void oneDeviceBoundaryDerivesOtherAndLeavesVersionToPlatform() {
        var mappings=List.of(mapping("Q","ELECTRICITY_KWH","kWh",false),mapping("F","CO2_FACTOR","kgCO2/kWh",false),mapping("T","INTERVAL_END","ms",true));
        var interval=IntervalMappings.extract(mappings,List.of(metric("Q","1"),metric("F","0.5"),metric("T",Long.toString(END))),"test");
        assertThat(interval.startMs()).isEqualTo(END-900000);assertThat(interval.endMs()).isEqualTo(END);assertThat(interval.revision()).isZero();
        assertThat(new ElectricityInterval("Q",END-900000,0,0,BigDecimal.ONE,BigDecimal.ONE,"kWh","kgCO2/kWh","SIMULATED","test").boundaries(null).endMs()).isEqualTo(END);
    }
    @Test void missingOrAmbiguousTimeNeverFallsBackToReceiveClock() {
        var absent=new ElectricityInterval("Q",0,0,0,BigDecimal.ONE,BigDecimal.ONE,"kWh","kgCO2/kWh","SIMULATED","test");
        assertThatThrownBy(()->absent.boundaries(null)).hasMessageContaining("DEVICE_TIME_MISSING");
        assertThatThrownBy(()->absent.boundaries(END+1)).hasMessageContaining("INVALID_ELECTRICITY_INTERVAL");
        assertThat(absent.boundaries(END).startMs()).isEqualTo(END-900000);
        assertThatThrownBy(()->new ElectricityInterval("Q",END-1800000,END,0,BigDecimal.ONE,BigDecimal.ONE,"kWh","kgCO2/kWh","SIMULATED","test").validate()).isInstanceOf(IllegalArgumentException.class);
    }
    private ProtocolFieldMapping mapping(String code,String type,String unit,boolean required) {
        return new ProtocolFieldMapping(code,"P","/"+code,code,type,unit,unit,BigDecimal.ONE,BigDecimal.ZERO,required,true,0);
    }
    private StandardMetric metric(String code,String value){return new StandardMetric(code,new BigDecimal(value),"unused","/"+code);}
}
