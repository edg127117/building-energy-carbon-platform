package com.platform.energy.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.platform.energy.classification.ClassificationContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildingDailyElectricityReaderTest {
    @Test void reusesExistingUnfilteredDailyQueryWithEffectiveVersionAndAuthorization() throws Exception {
        var repo=mock(ClassificationRepository.class);var query=mock(ClassificationService.class);var auth=mock(EnergyPeriodAuthorization.class);var json=new ObjectMapper();
        var reader=new BuildingDailyElectricityReader(repo,query,auth,json);
        var date=LocalDate.of(2026,1,1);var start=Instant.parse("2025-12-31T16:00:00Z");var end=start.plusSeconds(86400);var roles=Set.of("ENERGY_MANAGER");
        var config=new Configuration("relation",List.of(new Scheme("Z","楼层","SPACE","EXCLUSIVE"),new Scheme("A","用途","USE","EXCLUSIVE")),List.of(),List.of(),
                List.of(new BoundaryRule("boundary","MAIN_METER_TOTAL","CONFIRMED","test","ELECTRICITY","KWH")));
        when(repo.effective("B",start,end)).thenReturn(List.of(new ClassificationRepository.Version("v","B",1,1,"ACTIVE",0L,null,json.writeValueAsString(config),1,null)));
        reader.read(1,roles,"B",date);
        verify(query).query(1,roles,"B",new QueryRequest(start,end,"DAY","ELECTRICITY","KWH","A","v",List.of()));
        var order=inOrder(auth,repo,query);order.verify(auth).requireReader(roles);order.verify(auth).checkBuilding(1,roles,"B");order.verify(repo).effective("B",start,end);
        doThrow(new BusinessException(403,"无权限")).when(auth).checkBuilding(1,roles,"C");
        assertThatThrownBy(()->reader.read(1,roles,"C",date)).isInstanceOf(BusinessException.class);
        verify(repo,never()).effective(eq("C"),any(),any());
        when(repo.effective("B",start,end)).thenReturn(List.of());
        assertThatThrownBy(()->reader.read(1,roles,"B",date)).extracting(e->((BusinessException)e).getErrorCode()).isEqualTo("DAILY_CLASSIFICATION_REQUIRED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"KWH","kWh","kwh"})
    void retainsTheApprovedKilowattHourIdentifierInsteadOfInventingAnotherSpelling(String unit) throws Exception {
        var repo=mock(ClassificationRepository.class);var query=mock(ClassificationService.class);var auth=mock(EnergyPeriodAuthorization.class);var json=new ObjectMapper();
        var reader=new BuildingDailyElectricityReader(repo,query,auth,json);
        var date=LocalDate.of(2026,10,1);var start=Instant.parse("2026-09-30T16:00:00Z");var end=start.plusSeconds(86400);var roles=Set.of("ENERGY_MANAGER");
        var config=new Configuration("relation",List.of(new Scheme("use","用途","USAGE","ADDITIVE")),List.of(),List.of(),
                List.of(new BoundaryRule("boundary","MAIN_METER_TOTAL","CONFIRMED","test","ELECTRICITY",unit)));
        when(repo.effective("B",start,end)).thenReturn(List.of(new ClassificationRepository.Version("v","B",1,1,"APPROVED",0L,null,json.writeValueAsString(config),1,null)));
        reader.read(1,roles,"B",date);
        verify(query).query(1,roles,"B",new QueryRequest(start,end,"DAY","ELECTRICITY",unit,"use","v",List.of()));
        var other=new Configuration("relation",config.schemes(),List.of(),List.of(),
                List.of(new BoundaryRule("boundary","MAIN_METER_TOTAL","CONFIRMED","test","ELECTRICITY","MWH")));
        when(repo.effective("B",start,end)).thenReturn(List.of(new ClassificationRepository.Version("v","B",1,1,"APPROVED",0L,null,json.writeValueAsString(other),1,null)));
        assertThatThrownBy(()->reader.read(1,roles,"B",date)).extracting(e->((BusinessException)e).getErrorCode())
                .isEqualTo("DAILY_ELECTRICITY_UNIT_REQUIRED");
    }
}
