package com.platform.carbon;

import com.platform.system.mapper.*;
import com.platform.system.model.entity.SysUser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

class CarbonDisplayQueryWorkerTest {
    final CarbonDisplayQueryRepository repo=mock(CarbonDisplayQueryRepository.class);
    final CarbonDisplaySourceVersion versions=mock(CarbonDisplaySourceVersion.class);
    final CarbonDisplayQueryService service=mock(CarbonDisplayQueryService.class);
    final SysUserMapper users=mock(SysUserMapper.class);final SysRoleMapper roles=mock(SysRoleMapper.class);
    final CarbonDisplayQueryContracts.Query q=CarbonDisplayQueryContracts.intensity("B","MONTHLY_YTD",2026,10,"Asia/Shanghai");
    final CarbonDisplayQueryRepository.Head h=new CarbonDisplayQueryRepository.Head(q.key(),"B",q.kind(),"{}",1,"v",null,"PENDING",null,null,0,0,null,null);
    CarbonDisplayQueryWorker worker(){
        when(repo.due(anyLong())).thenReturn(List.of(h));when(service.parse(h)).thenReturn(q);when(versions.version(q)).thenReturn("v");when(repo.claim(eq(h),anyLong())).thenReturn("lease");
        return new CarbonDisplayQueryWorker(repo,versions,service,new CarbonDisplayQueryProperties(),users,roles);
    }
    @Test void currentDisabledAccountCannotUseSavedPrivileges(){try(var worker=worker()){
        worker.refresh();verify(service,never()).rebuild(any(),any(),any());verifyNoInteractions(roles);
        verify(repo).failed(eq(h),eq("lease"),eq("CARBON_DISPLAY_ACTOR_DISABLED"),eq(403),anyLong());
    }}
    @Test void resolvesCurrentRolesAndIsolatesAnotherFailedTask(){try(var worker=worker()){
        var bad=new CarbonDisplayQueryRepository.Head("bad","BAD",q.kind(),"bad",2,"v",null,"PENDING",null,null,0,0,null,null);
        when(repo.due(anyLong())).thenReturn(List.of(bad,h));when(service.parse(bad)).thenThrow(new IllegalStateException("bad task"));
        var user=new SysUser();user.setId(1L);user.setStatus(1);user.setDelFlag(0);user.setActivationPending(0);
        when(users.selectSecurityState(1L)).thenReturn(user);when(roles.selectRoleKeysByUserId(1L)).thenReturn(List.of("BUILDING_OWNER"));
        worker.refresh();verify(repo).defer(eq(bad),anyLong());verify(service).rebuild(h,"lease",List.of("BUILDING_OWNER"));
    }}
}
