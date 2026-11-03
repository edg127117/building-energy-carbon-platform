package com.platform.carbon;

import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.model.entity.SysUser;
import org.junit.jupiter.api.Test;
import java.time.YearMonth;
import java.util.List;
import static org.mockito.Mockito.*;

class DailyTrendSummaryWorkerTest {
    final DailyTrendSummaryRepository repo=mock(DailyTrendSummaryRepository.class);
    final DailyTrendSummaryService service=mock(DailyTrendSummaryService.class);
    final SysUserMapper users=mock(SysUserMapper.class);
    final SysRoleMapper roles=mock(SysRoleMapper.class);
    final DailyTrendSummaryRepository.Head head=new DailyTrendSummaryRepository.Head("B","2026-01",1,"v",null,"PENDING",null,null,0,0);
    DailyTrendSummaryWorker worker() {
        when(repo.due(anyLong())).thenReturn(List.of(head));when(repo.sourceVersion("B",YearMonth.of(2026,1))).thenReturn("v");when(repo.claim(eq(head),anyLong())).thenReturn("lease");
        return new DailyTrendSummaryWorker(repo,service,new DailyTrendSummaryProperties(),users,roles);
    }
    @Test void disabledCreatorCannotRunWithPersistedAdministrativePrivileges() {
        try(var worker=worker()) {
            when(users.selectSecurityState(1L)).thenReturn(null);worker.refresh();
            verifyNoInteractions(service,roles);
            verify(repo).failed(eq(head),eq("lease"),anyString(),anyLong());
        }
    }
    @Test void executionResolvesCurrentRolesAndOriginalServiceRechecksBuildingScope() {
        try(var worker=worker()) {
            var user=new SysUser();user.setId(1L);user.setStatus(1);user.setDelFlag(0);user.setActivationPending(0);
            when(users.selectSecurityState(1L)).thenReturn(user);
            when(roles.selectRoleKeysByUserId(1L)).thenReturn(List.of("BUILDING_OWNER"));
            worker.refresh();verify(service).rebuild(head,"lease",List.of("BUILDING_OWNER"));
        }
    }
    @Test void oneBuildingVersionFailureDoesNotStarveAnotherBuilding() {
        try(var worker=worker()) {
            var bad=new DailyTrendSummaryRepository.Head("BAD","2026-01",1,"v",null,"PENDING",null,null,0,0);
            when(repo.due(anyLong())).thenReturn(List.of(bad,head));
            when(repo.sourceVersion(eq("BAD"),any())).thenThrow(new com.platform.framework.exception.BusinessException(503,"DAILY_SUMMARY_VERSION_CAPACITY","capacity"));
            var user=new SysUser();user.setId(1L);user.setStatus(1);user.setDelFlag(0);user.setActivationPending(0);
            when(users.selectSecurityState(1L)).thenReturn(user);when(roles.selectRoleKeysByUserId(1L)).thenReturn(List.of("ENERGY_MANAGER"));
            worker.refresh();verify(repo).defer(eq(bad),eq("DAILY_SUMMARY_VERSION_CAPACITY"),anyLong());
            verify(service).rebuild(head,"lease",List.of("ENERGY_MANAGER"));
        }
    }
}
