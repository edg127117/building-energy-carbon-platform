package com.platform.carbon;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HourlyMonitoringServiceTest {
    final CarbonAuthorization auth=mock(CarbonAuthorization.class);
    final HourlyMonitoringRepository repo=mock(HourlyMonitoringRepository.class);
    final HourlyMonitoringCalculator calculator=mock(HourlyMonitoringCalculator.class);
    final CarbonDisplayQueryProperties properties=new CarbonDisplayQueryProperties();
    final HourlyMonitoringService service=new HourlyMonitoringService(auth,repo,calculator,properties);
    @Test void coldReadReturns24BeijingSlotsAndNeverCalculatesInRequest() {
        when(repo.snapshot(any(),any(),any(),any())).thenReturn(new HourlyMonitoringRepository.Snapshot(Map.of(),Map.of()));
        var view=service.query(7,Set.of("BUILDING_OWNER"),"B",LocalDate.of(2026,1,2));
        assertThat(view.points()).hasSize(24).allMatch(p->p.electricityKwh()==null&&p.electricityStatus().equals("PREPARING"));
        assertThat(view.points().getFirst().startInclusive()).isEqualTo(Instant.parse("2026-01-01T16:00:00Z"));
        verify(calculator,never()).calculate(anyLong(),anyCollection(),any(),any(),any(),any());
        verify(repo).register("B",LocalDate.of(2026,1,2),7);
    }
    @Test void unauthorizedBuildingCannotRegisterOrReadEvidence() {
        doThrow(new BusinessException(403,"DENIED","denied")).when(auth).requireReader(anyLong(),anyCollection(),eq("B"));
        assertThatThrownBy(()->service.query(7,Set.of("BUILDING_OWNER"),"B",LocalDate.now())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.evidence(7,Set.of("BUILDING_OWNER"),"B","a".repeat(64))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repo);
    }
}
