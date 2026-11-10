package com.platform.carbon;

import com.platform.energy.activity.EnergyActivityDataContracts.RawActivityDataView;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class HourlyMonitoringBoundaryTest {
    final Instant hour=Instant.parse("2026-11-10T00:00:00Z");
    RawActivityDataView fact(long seconds,double value,String device) {
        return new RawActivityDataView("p","p","KWH","ELECTRICITY","PURCHASED_ELECTRICITY","CUMULATIVE","CONFIRMED",1,
                "SIMULATOR","p",device,value,hour.plusSeconds(seconds).toEpochMilli(),hour.plusSeconds(seconds).toEpochMilli(),"Q0",false,"BUILDING",1,1);
    }
    @Test void precedingAnchorsShareTheSameReadingAndNeverInterpolate() {
        var values=List.of(fact(-120,100,"d"),fact(3480,110,"d"),fact(7080,130,"d"));
        var first=HourlyMonitoringBoundary.select(values,hour,hour.plusSeconds(3600));
        var second=HourlyMonitoringBoundary.select(values,hour.plusSeconds(3600),hour.plusSeconds(7200));
        assertThat(first.from()).isEqualTo(hour.minusSeconds(120));
        assertThat(first.to()).isEqualTo(second.from()).isEqualTo(hour.plusSeconds(3480));
        assertThat(first.items().getLast().rawValue()-first.items().getFirst().rawValue()).isEqualTo(10);
        assertThat(second.items().getLast().rawValue()-second.items().getFirst().rawValue()).isEqualTo(20);
    }
    @Test void sixMinuteBoundaryIsInclusiveAndSevenMinutesDoesNotBecomeZero() {
        assertThat(HourlyMonitoringBoundary.select(List.of(fact(-360,10,"d"),fact(3240,10,"d")),hour,hour.plusSeconds(3600)).items()).hasSize(2);
        assertThatThrownBy(()->HourlyMonitoringBoundary.select(List.of(fact(-361,10,"d"),fact(3600,20,"d")),hour,hour.plusSeconds(3600)))
                .isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getErrorCode()).isEqualTo("HOURLY_MONITOR_ANCHOR_MISSING");
    }
    @Test void currentHourKeepsLastFifteenMinuteReadingWithoutInventingConsumption() {
        var window=HourlyMonitoringBoundary.select(List.of(fact(0,100,"d"),fact(900,103,"d")),hour,hour.plusSeconds(1740),true);
        assertThat(window.to()).isEqualTo(hour.plusSeconds(900));
        assertThat(window.items().getLast().rawValue()).isEqualTo(103);
        assertThatThrownBy(()->HourlyMonitoringBoundary.select(List.of(fact(0,100,"d")),hour,hour.plusSeconds(120),true)).isInstanceOf(BusinessException.class);
    }
    @Test void identitySwitchAndDuplicateTimestampsAreRejected() {
        assertThatThrownBy(()->HourlyMonitoringBoundary.select(List.of(fact(0,10,"a"),fact(3600,11,"b")),hour,hour.plusSeconds(3600)))
                .isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getErrorCode()).isEqualTo("HOURLY_MONITOR_IDENTITY_CHANGED");
        assertThatThrownBy(()->HourlyMonitoringBoundary.select(List.of(fact(0,10,"a"),fact(3600,11,"a"),fact(3600,12,"a")),hour,hour.plusSeconds(3600)))
                .isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getErrorCode()).isEqualTo("HOURLY_MONITOR_DUPLICATE_TIME");
    }
}
