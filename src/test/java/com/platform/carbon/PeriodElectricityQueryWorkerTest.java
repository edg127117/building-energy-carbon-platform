package com.platform.carbon;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import static org.mockito.Mockito.*;

class PeriodElectricityQueryWorkerTest {
    final CarbonDisplayQueryRepository repo=mock(CarbonDisplayQueryRepository.class);
    final PeriodElectricityQueryService service=mock(PeriodElectricityQueryService.class);
    final DynamicElectricityRepository results=mock(DynamicElectricityRepository.class);
    final CarbonDisplayTaskIdentity identity=mock(CarbonDisplayTaskIdentity.class);
    final DailyEvidenceVerification evidence=mock(DailyEvidenceVerification.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PeriodElectricityQueryService.Query q=PeriodElectricityQueryService.validate("B","DAILY_TREND_DETAIL","2026-01");
    final CarbonDisplayQueryRepository.Head h=new CarbonDisplayQueryRepository.Head(q.key(),"B",q.kind(),"{}",1,"v",null,"PENDING",null,null,0,0,null,null);
    PeriodElectricityQueryWorker worker() {
        when(repo.duePeriods(anyLong())).thenReturn(List.of(h));when(repo.claim(eq(h),anyLong())).thenReturn("lease");
        when(service.parse(h)).thenReturn(q);when(service.current(q)).thenReturn("v");
        return new PeriodElectricityQueryWorker(repo,service,results,identity,new CarbonDisplayQueryProperties(),jdbc,evidence);
    }
    @Test void revokedActorCannotRebuildWithOldSavedPrivileges() {
        try(var worker=worker()) {
            when(identity.currentRoles(1)).thenThrow(new BusinessException(403,"ACTOR_DISABLED","disabled"));
            worker.refresh();verify(service,never()).rebuild(any(),any(),any());
            verify(repo).failed(eq(h),eq("lease"),eq("ACTOR_DISABLED"),eq(403),anyLong());
        }
    }
    @Test void resolvesCurrentRolesAndResumesPersistedTask() {
        try(var worker=worker()) {
            when(identity.currentRoles(1)).thenReturn(List.of("BUILDING_OWNER"));worker.refresh();
            verify(service).rebuild(h,"lease",List.of("BUILDING_OWNER"));
            verify(results).backfillChartProjections(16);verify(evidence).verify(2);
        }
    }
    List<CarbonDisplayQueryRepository.Head> pendingTasks() {
        return java.util.stream.IntStream.range(0,5).mapToObj(index->new CarbonDisplayQueryRepository.Head(
                "query-"+index,"B",q.kind(),"{}",1,"v",null,"PENDING",null,null,0,0,null,null)).toList();
    }
    @Test void drainsAtMostFourPendingTasksWithoutWaitingPerDate() {
        try(var worker=worker()) {
            var tasks=pendingTasks();when(repo.duePeriods(anyLong())).thenReturn(tasks);
            when(repo.claim(any(),anyLong())).thenReturn("lease");when(service.parse(any())).thenReturn(q);
            when(identity.currentRoles(1)).thenReturn(List.of("ENERGY_MANAGER"));worker.refresh();
            verify(service,times(4)).rebuild(any(),eq("lease"),eq(List.of("ENERGY_MANAGER")));
            verify(repo,never()).claim(eq(tasks.getLast()),anyLong());
        }
    }
    @Test void doesNotStartAnotherTaskAfterSlowTaskConsumesBatchBudget() {
        try(var worker=worker()) {
            var tasks=pendingTasks();when(repo.duePeriods(anyLong())).thenReturn(tasks);
            when(repo.claim(any(),anyLong())).thenReturn("lease");when(service.parse(any())).thenReturn(q);
            when(identity.currentRoles(1)).thenReturn(List.of("ENERGY_MANAGER"));
            when(service.rebuild(eq(tasks.getFirst()),eq("lease"),anyCollection())).thenAnswer(call->{Thread.sleep(2100);return true;});
            worker.refresh();verify(service,times(1)).rebuild(any(),anyString(),anyCollection());
            verify(repo,never()).claim(eq(tasks.get(1)),anyLong());
        }
    }
    @Test void evidenceRecoveryFailureDoesNotPreventAnotherIndependentDisplayTask() {
        try(var worker=worker()) {
            when(results.backfillChartProjections(16)).thenThrow(new BusinessException(503,"INTEGRITY","damaged"));
            when(identity.currentRoles(1)).thenReturn(List.of("ENERGY_MANAGER"));worker.refresh();
            verify(service).rebuild(h,"lease",List.of("ENERGY_MANAGER"));
        }
    }
    @Test void transientDatabaseConflictRemainsPreparingAndCanResume() {
        try(var worker=worker()) {
            when(identity.currentRoles(1)).thenReturn(List.of("ENERGY_MANAGER"));
            when(service.rebuild(h,"lease",List.of("ENERGY_MANAGER")))
                    .thenThrow(new org.springframework.dao.CannotAcquireLockException("concurrent publish"));
            worker.refresh();
            verify(repo).waitForInputs(eq(h),eq("lease"),anyLong());
            verify(repo,never()).failed(any(),any(),any(),anyInt(),anyLong());
        }
    }
    @Test void failedDiscoveryDoesNotBlockAlreadyPersistedWork() {
        try(var worker=worker()) {
            doThrow(new org.springframework.dao.QueryTimeoutException("discovery budget"))
                    .when(jdbc).query(contains("UNION SELECT"),any(RowCallbackHandler.class));
            when(identity.currentRoles(1)).thenReturn(List.of("ENERGY_MANAGER"));
            worker.refresh();
            verify(service).rebuild(h,"lease",List.of("ENERGY_MANAGER"));
        }
    }
    @Test void manualDiscoveryUsesBoundedCoveringPagesAndKeepsCrossMonthIntervals() throws Exception {
        try(var worker=worker()) {
            var batch=mock(java.sql.ResultSet.class);when(batch.getString(1)).thenReturn("B");when(batch.getLong(2)).thenReturn(1L);
            doAnswer(call->{call.getArgument(1,RowCallbackHandler.class).processRow(batch);return null;})
                    .when(jdbc).query(contains("MIN(reviewed_by)"),any(RowCallbackHandler.class));
            long start=java.time.LocalDate.parse("2026-02-28").atStartOfDay(PeriodElectricityQueryService.ZONE).toInstant().toEpochMilli();
            long end=java.time.LocalDate.parse("2026-04-02").atStartOfDay(PeriodElectricityQueryService.ZONE).toInstant().toEpochMilli();
            doAnswer(call->{
                RowMapper<?> mapper=call.getArgument(1);var rows=new java.util.ArrayList<>();
                for(int i=0;i<1000;i++) {
                    var rs=mock(java.sql.ResultSet.class);when(rs.getString(1)).thenReturn("fact-"+i);
                    when(rs.getLong(2)).thenReturn(start);when(rs.getLong(3)).thenReturn(end);rows.add(mapper.mapRow(rs,i));
                }
                return rows;
            }).when(jdbc).query(contains("MAX_EXECUTION_TIME(3000)"),any(RowMapper.class),eq("B"),eq(Long.MIN_VALUE),eq(Long.MIN_VALUE),eq(""));
            worker.discover();worker.discoverManualPage();worker.discoverManualPage();
            for(String month:List.of("2026-02","2026-03","2026-04"))
                verify(service).prepare(PeriodElectricityQueryService.validate("B","DAILY_TREND_DETAIL",month),1);
            verify(jdbc).query(contains("LIMIT 1000"),any(RowMapper.class),eq("B"),eq(start),eq(start),eq("fact-999"));
            verify(jdbc,never()).query(contains("JOIN biz_energy_manual_reading"),any(RowCallbackHandler.class));
        }
    }
    @Test void failedManualPageRetainsItsCursorForRetry() throws Exception {
        try(var worker=worker()) {
            var batch=mock(java.sql.ResultSet.class);when(batch.getString(1)).thenReturn("B");when(batch.getLong(2)).thenReturn(1L);
            doAnswer(call->{call.getArgument(1,RowCallbackHandler.class).processRow(batch);return null;})
                    .when(jdbc).query(contains("MIN(reviewed_by)"),any(RowCallbackHandler.class));
            when(jdbc.query(contains("MAX_EXECUTION_TIME(3000)"),any(RowMapper.class),eq("B"),eq(Long.MIN_VALUE),eq(Long.MIN_VALUE),eq("")))
                    .thenThrow(new org.springframework.dao.QueryTimeoutException("budget")).thenReturn(List.of());
            worker.discover();
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.QueryTimeoutException.class,worker::discoverManualPage);
            worker.discoverManualPage();
            verify(jdbc,times(2)).query(contains("MAX_EXECUTION_TIME(3000)"),any(RowMapper.class),eq("B"),eq(Long.MIN_VALUE),eq(Long.MIN_VALUE),eq(""));
        }
    }
}
