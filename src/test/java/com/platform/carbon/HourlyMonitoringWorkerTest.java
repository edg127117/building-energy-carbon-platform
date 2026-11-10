package com.platform.carbon;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HourlyMonitoringWorkerTest {
    final HourlyMonitoringRepository repo=mock(HourlyMonitoringRepository.class);
    final HourlyMonitoringCalculator calculator=mock(HourlyMonitoringCalculator.class);
    final CarbonDisplayTaskIdentity identity=mock(CarbonDisplayTaskIdentity.class);
    final LocalDate day=LocalDate.of(2026,1,2);
    final HourlyMonitoringRepository.Target target=new HourlyMonitoringRepository.Target("B",day,7);
    final Instant start=day.atStartOfDay(HourlyMonitoringCalculator.ZONE).toInstant();
    Map<Long,String> versions(){var map=new HashMap<Long,String>();for(int h=0;h<24;h++)map.put(start.plusSeconds(h*3600).toEpochMilli(),"v");return map;}
    HourlyMonitoringRepository.Snapshot unchanged() {
        var rows=new HashMap<Long,HourlyMonitoringRepository.Stored>();
        for(int h=0;h<24;h++) {long at=start.plusSeconds(h*3600).toEpochMilli();rows.put(at,new HourlyMonitoringRepository.Stored(at,"v","{}","h","e",System.currentTimeMillis()));}
        return new HourlyMonitoringRepository.Snapshot(versions(),rows);
    }
    @Test void unchangedTargetsAreCheckedWithoutLeaseWritesOrCalculation() {
        when(repo.due(anyLong(),eq(false),isNull())).thenReturn(List.of(target));
        when(repo.snapshot(any(),any(),any(),any())).thenReturn(unchanged());
        try(var worker=new HourlyMonitoringWorker(repo,calculator,identity,new CarbonDisplayQueryProperties())) {
            worker.refresh(false);worker.refresh(false);
        }
        verify(repo,times(2)).snapshot(eq("B"),eq(day),any(),any());
        verify(repo,never()).claim(any(),any(),anyLong());
        verify(repo,never()).release(any(),any(),anyLong());
        verify(calculator,never()).calculate(anyLong(),anyCollection(),any(),any(),any(),any());
        verifyNoInteractions(identity);
    }
    @Test void idleFirstPageDoesNotStarveChangedTargetOnNextPage() {
        var first=new ArrayList<HourlyMonitoringRepository.Target>();
        for(int i=0;i<8;i++)first.add(new HourlyMonitoringRepository.Target("B"+i,day,7));
        var ninth=new HourlyMonitoringRepository.Target("B8",day,7);
        when(repo.due(anyLong(),eq(false),isNull())).thenReturn(first);
        when(repo.due(anyLong(),eq(false),eq(first.getLast()))).thenReturn(List.of(ninth));
        when(repo.snapshot(any(),any(),any(),any())).thenReturn(unchanged());
        var changed=new HashMap<>(unchanged().results());changed.remove(start.toEpochMilli());
        when(repo.snapshot(eq("B8"),any(),any(),any())).thenReturn(new HourlyMonitoringRepository.Snapshot(versions(),changed));
        when(repo.claim(eq(ninth),any(),anyLong())).thenReturn(true);
        when(identity.currentRoles(7)).thenReturn(List.of("ENERGY_MANAGER"));
        when(repo.sourceKind(any(),any(),any())).thenReturn("RAW_CUMULATIVE");
        when(calculator.calculate(anyLong(),anyCollection(),any(),any(),any(),any())).thenThrow(new IllegalStateException("retry"));
        try(var worker=new HourlyMonitoringWorker(repo,calculator,identity,new CarbonDisplayQueryProperties())) {
            worker.refresh(false);worker.refresh(false);worker.refresh(false);
        }
        verify(repo,times(2)).due(anyLong(),eq(false),isNull());
        verify(repo).claim(eq(ninth),any(),anyLong());
        verify(repo).publish(eq(ninth),any(),eq(start.toEpochMilli()),eq("v"),any(),any());
        verify(repo).release(eq(ninth),any(),eq(1000L));
        for(var idle:first)verify(repo,never()).claim(eq(idle),any(),anyLong());
    }
    @Test void onlyChangedHoursCalculateAndTransientFailureIsPublishedForRetry() {
        var rows=new HashMap<Long,HourlyMonitoringRepository.Stored>();
        for(int h=0;h<24;h++) {long at=start.plusSeconds(h*3600).toEpochMilli();rows.put(at,new HourlyMonitoringRepository.Stored(at,h==10?"old":"v","{}","h","e",System.currentTimeMillis()));}
        when(repo.snapshot(any(),any(),any(),any())).thenReturn(new HourlyMonitoringRepository.Snapshot(versions(),rows));
        when(identity.currentRoles(7)).thenReturn(List.of("ENERGY_MANAGER"));
        when(repo.sourceKind(any(),any(),any())).thenReturn("RAW_CUMULATIVE");
        when(calculator.calculate(anyLong(),anyCollection(),any(),any(),any(),any())).thenThrow(new IllegalStateException("source temporarily unavailable"));
        try(var worker=new HourlyMonitoringWorker(repo,calculator,identity,new CarbonDisplayQueryProperties())){worker.refresh(target,"token");}
        var value=org.mockito.ArgumentCaptor.forClass(HourlyMonitoringCalculator.Calculated.class);
        verify(repo).publish(eq(target),eq("token"),eq(start.plusSeconds(36000).toEpochMilli()),eq("v"),any(),value.capture());
        assertThat(value.getValue().point().electricityStatus()).isEqualTo("FAILED");
        verify(calculator,times(1)).calculate(eq(7L),anyCollection(),eq("B"),eq(start.plusSeconds(36000)),eq("RAW_CUMULATIVE"),any());
    }
    @Test void revokedIdentityCannotStartAnyCalculation() {
        when(identity.currentRoles(7)).thenThrow(new com.platform.framework.exception.BusinessException(403,"DENIED","revoked"));
        try(var worker=new HourlyMonitoringWorker(repo,calculator,identity,new CarbonDisplayQueryProperties())) {
            assertThatThrownBy(()->worker.refresh(target,"token")).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        }
        verifyNoInteractions(calculator,repo);
    }
}
