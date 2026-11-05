package com.platform.modbus.model;

import com.platform.modbus.config.ModbusEdgeProperties.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ElectricityIntervalMessageFactoryTest {
    private Device device() {
        var d=new Device();d.setIdentityType("SERIAL_NO");d.setIdentityValue("SIMULATED");d.setProfileCode("METER");
        d.setPoints(List.of(point("Q","ELECTRICITY_KWH","kWh"),point("F","CO2_FACTOR","kgCO2/kWh"),point("start","INTERVAL_START","ms"),point("end","INTERVAL_END","ms"),point("revision","INTERVAL_REVISION","1")));
        return d;
    }
    private Point point(String code,String role,String unit) {var p=new Point();p.setCode(code);p.setUnit(unit);p.setValueType(role);p.setFunction(ReadFunction.HOLDING_REGISTER);p.setAddress(0);return p;}
    @Test void devicePeriodIsIndependentFromPollingClockAndHasNoRawDuplicate() {
        var factory=new TelemetryMessageFactory(Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC));
        var result=factory.create(device(),Map.of("Q",new BigDecimal("1.25"),"F",new BigDecimal("0.5"),"start",new BigDecimal("1790784000000"),"end",new BigDecimal("1790784900000"),"revision",BigDecimal.ONE));
        assertThat(result.electricityInterval().startMs()).isEqualTo(1790784000000L);assertThat(result.metrics()).isEmpty();
        assertThat(result.collectedAt()).isNull();assertThat(result.timeSource()).isEqualTo("ADAPTER_RECEIVED");
        assertThat(result.electricityInterval().metricCode()).isEqualTo("Q");
    }
    @Test void missingOrFractionalIntervalCannotUsePollingTimeAsFallback() {
        var factory=new TelemetryMessageFactory(Clock.systemUTC());
        assertThatThrownBy(()->factory.create(device(),Map.of("Q",BigDecimal.ONE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.create(device(),Map.of("Q",BigDecimal.ONE,"F",BigDecimal.ONE,"start",new BigDecimal("1790784000000.5"),"end",new BigDecimal("1790784900000"),"revision",BigDecimal.ONE))).isInstanceOf(ArithmeticException.class);
    }
    @Test void oneDeviceTimeNeedsNoDeviceRevisionAndDoesNotPromotePollingSequence() {
        var d=device();d.setPoints(List.of(point("Q","ELECTRICITY_KWH","kWh"),point("F","CO2_FACTOR","kgCO2/kWh"),point("end","INTERVAL_END","ms")));
        var factory=new TelemetryMessageFactory(Clock.systemUTC());
        var result=factory.create(d,Map.of("Q",BigDecimal.ONE,"F",new BigDecimal("0.5"),"end",new BigDecimal("1790784900000")));
        assertThat(result.electricityInterval().revision()).isZero();assertThat(result.electricityInterval().startMs()).isEqualTo(1790784000000L);
        assertThat(result.electricityInterval().sequenceSource()).isEqualTo("ADAPTER_POLL");assertThat(result.sourceSeq()).isEqualTo(1);
    }
}
