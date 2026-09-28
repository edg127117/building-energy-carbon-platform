package com.platform.weather;

import com.platform.energy.aggregation.EnergyAggregationAuthorization;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.service.*;
import com.platform.weather.energy.DailyElectricityAdapter;
import com.platform.weather.source.WeatherSourceModels.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.platform.weather.WeatherModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeatherServiceTest {
    WeatherRepository repo=mock(WeatherRepository.class);
    BuildingScopeService scope=mock(BuildingScopeService.class);
    EnergyAggregationAuthorization authorization=mock(EnergyAggregationAuthorization.class);
    SysUserService users=mock(SysUserService.class);
    SysRoleMapper roles=mock(SysRoleMapper.class);
    DailyElectricityAdapter energy=mock(DailyElectricityAdapter.class);
    WeatherService service=new WeatherService(repo,scope,authorization,users,roles,energy);
    @Test void ownerCannotSubmitSourceJobs() {
        var request=new FetchRequest("building",Source.OPEN_METEO,Product.HISTORY_DAILY,LocalDate.of(2026,1,1),LocalDate.of(2026,1,2));
        assertThatThrownBy(()->service.submit(1,List.of("BUILDING_OWNER"),"key",request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repo);
    }
    @Test void scopeDenialPrecedesConfigurationReads() {
        doThrow(new BusinessException(403,"denied")).when(scope).checkAccess(1L,List.of("PLATFORM_ADMIN"),"other");
        assertThatThrownBy(()->service.saveBinding(1,List.of("PLATFORM_ADMIN"),"other","key",null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repo);
    }
    @Test void revokedTaskAccountCannotExecuteEvenIfJobWasPreviouslyAuthorized() {
        Job job=WeatherService.newJob("WEATHER","b","v",Source.OPEN_METEO,Product.CURRENT,LocalDate.now(ZONE),LocalDate.now(ZONE),null,null,1);
        assertThatThrownBy(()->service.authorizeJob(job)).isInstanceOf(BusinessException.class).hasMessageContaining("账号");
        verifyNoInteractions(repo,scope,authorization);
    }
    @Test void noScopeIsDifferentFromAdminAllScope() {
        when(scope.getAccessibleBuildingIds(1L,List.of("BUILDING_OWNER"))).thenReturn(Set.of());
        service.locations(1,List.of("BUILDING_OWNER"),1,20);
        verify(repo).locations(eq(Set.of()),eq(1),eq(20),any());
        when(scope.getAccessibleBuildingIds(2L,List.of("PLATFORM_ADMIN"))).thenReturn(null);
        service.locations(2,List.of("PLATFORM_ADMIN"),1,20);
        verify(repo).locations(isNull(),eq(1),eq(20),any());
    }
    @Test void timezoneAndHistoryRangeAreBounded() {
        assertThatThrownBy(()->WeatherService.range(LocalDate.of(2023,12,31),LocalDate.of(2024,1,1),366)).isInstanceOf(BusinessException.class);
        var req=new BindingRequest("n",32d,118d,null,"CITY",Instant.parse("2026-01-01T00:00:00Z"),0,true);
        assertThatThrownBy(()->service.saveBinding(1,List.of("PLATFORM_ADMIN"),"b","key",req)).hasMessageContaining("零点");
        verifyNoInteractions(repo);
    }
}
