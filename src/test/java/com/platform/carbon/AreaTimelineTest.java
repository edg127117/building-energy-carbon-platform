package com.platform.carbon;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static com.platform.carbon.CarbonModels.*;
import static org.assertj.core.api.Assertions.*;

class AreaTimelineTest {
    static final LocalDate START=LocalDate.of(2024,1,1), JULY=LocalDate.of(2024,7,1), END=LocalDate.of(2025,1,1);
    static DenominatorVersion version(String id,String area,LocalDate from,LocalDate to) {
        return new DenominatorVersion("D",id,1,"B",DenominatorType.BUILDING_AREA,new BigDecimal(area),"M2","source","evidence",
                UsageNature.FORMAL,LifecycleStatus.ACTIVE,from,to,1,1,from.atStartOfDay(),2L,from.atStartOfDay(),"approved",3L,from.atStartOfDay());
    }
    @Test void weighsLeapYearAndMidMonthByCivilDaysWithoutRoundingAreaFirst() {
        var result=AreaTimeline.weigh(List.of(new AreaTimeline.Slice(version("A","10000",START,JULY),START,JULY),
                new AreaTimeline.Slice(version("B","12000",JULY,null),JULY,null)),START,END);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.days()).isEqualTo(366);
        assertThat(result.areaDays()).isEqualByComparingTo("4028000");
        var mid=START.plusDays(14);
        var january=AreaTimeline.weigh(List.of(new AreaTimeline.Slice(version("A","10",START,mid),START,mid),
                new AreaTimeline.Slice(version("B","20",mid,null),mid,null)),START,START.plusMonths(1));
        assertThat(january.areaDays()).isEqualByComparingTo("480");
    }
    @Test void sameAreaDifferentVersionsAreAllowedButGapsAndOverlapsAreNot() {
        var a=new AreaTimeline.Slice(version("A","100",START,JULY),START,JULY);
        var b=new AreaTimeline.Slice(version("B","100",JULY,null),JULY,null);
        assertThat(AreaTimeline.weigh(List.of(a,b),START,END).average()).isEqualByComparingTo("100");
        assertThat(AreaTimeline.weigh(List.of(a),START,END).status()).isEqualTo("AREA_GAP");
        assertThat(AreaTimeline.weigh(List.of(a,a,b),START,END).status()).isEqualTo("AREA_CONFLICT");
    }
}
