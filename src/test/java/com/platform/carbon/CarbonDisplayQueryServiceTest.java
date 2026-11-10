package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.platform.carbon.CarbonDisplayQueryContracts.*;

class CarbonDisplayQueryServiceTest {
    final EnergyPeriodAuthorization auth=mock(EnergyPeriodAuthorization.class);
    final CarbonDisplayQueryRepository repo=mock(CarbonDisplayQueryRepository.class);
    final CarbonDisplaySourceVersion versions=mock(CarbonDisplaySourceVersion.class);
    final CarbonDisplayQueryCache cache=mock(CarbonDisplayQueryCache.class);
    final JointFlowService flow=mock(JointFlowService.class);
    final CarbonIntensityService intensity=mock(CarbonIntensityService.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final CarbonDisplayQueryService service=new CarbonDisplayQueryService(auth,repo,versions,cache,flow,intensity,json);
    final Query q=CarbonDisplayQueryContracts.intensity("B","MONTHLY_YTD",2026,10,"Asia/Shanghai");
    final List<String> roles=List.of("BUILDING_OWNER");
    CarbonDisplayQueryRepository.Head head;
    String payload;
    @BeforeEach void setup()throws Exception {
        payload=json.writeValueAsString(json.readValue("""
                {"buildingId":"B","mode":"MONTHLY_YTD","start":"2026-01-01","endExclusive":"2026-11-01","timezoneId":"Asia/Shanghai",
                "actual":{"value":"4.791448","status":"AVAILABLE"},"percentage":"8.449126"}
                """,CarbonIntensityContracts.View.class));
        head=new CarbonDisplayQueryRepository.Head(q.key(),"B",q.kind(),json.writeValueAsString(q),1,"v","v","READY",CarbonCalculationCore.sha256(payload),null,0,0,null,null);
        when(versions.version(q)).thenReturn("v");when(repo.head(q.key())).thenReturn(head);
    }
    View query(){return service.intensity(1,roles,"B","MONTHLY_YTD",2026,10,"Asia/Shanghai");}
    @Test void readinessInspectionDoesNotRegisterOrBuildMissingResults(){
        when(repo.head(q.key())).thenReturn(null);
        assertThat(service.inspect(1,roles,q).state()).isEqualTo("PREPARING");
        verify(repo,never()).request(any(),anyString(),anyLong(),anyString(),anyLong());verifyNoInteractions(flow,intensity);
    }
    @Test void prewarmRegistersCurrentSourceUnderAuthorizedTaskIdentityWithoutReadingFacts(){
        service.prepare(1,roles,q);
        verify(auth).requireReader(roles);verify(auth).checkBuilding(1,roles,"B");
        verify(repo).request(eq(q),anyString(),eq(1L),eq("v"),anyLong());verifyNoInteractions(flow,intensity,cache);
    }
    @Test void scopeCheckPrecedesAnyCacheLookup(){
        doThrow(new BusinessException(403,"denied")).when(auth).checkBuilding(1,roles,"B");
        assertThatThrownBy(this::query).isInstanceOf(BusinessException.class);verifyNoInteractions(repo,versions,cache,flow,intensity);
    }
    @Test void warmResultRetainsExactDecimalsWithoutCallingHeavyServices(){
        when(cache.get(q.key(),"v")).thenReturn(payload);
        var result=query();assertThat(result.state()).isEqualTo("READY");
        assertThat(((CarbonIntensityContracts.View)result.data()).actual().value()).isEqualByComparingTo("4.791448");
        verify(repo,never()).payload(any());verifyNoInteractions(flow,intensity);
    }
    @Test void corruptedRedisFallsBackToVerifiedPersistentResult(){
        when(cache.get(q.key(),"v")).thenReturn("broken");when(repo.payload(head)).thenReturn(payload);
        assertThat(query().state()).isEqualTo("READY");verify(cache).put(q.key(),"v",payload);
    }
    @Test void changedSourceDoesNotServeOldResultOrRunHeavyWorkOnRequest(){
        when(versions.version(q)).thenReturn("new");assertThat(query().state()).isEqualTo("PREPARING");
        verify(repo).request(eq(q),anyString(),eq(1L),eq("new"),anyLong());verifyNoInteractions(cache,flow,intensity);
    }
    @Test void firstFlowReadQueuesOnlyFormalQueryAndKeepsPreviewSeparate(){
        var request=new JointFlowContracts.Request("B","YEAR","2026","2026-10",null);var fq=CarbonDisplayQueryContracts.flow(request);
        when(versions.version(fq)).thenReturn("f");
        assertThat(service.jointFlow(1,roles,request).state()).isEqualTo("PREPARING");verifyNoInteractions(flow,intensity);
    }
    @Test void versionChangeDuringBuildAndLostLeaseCannotPopulateCache()throws Exception {
        when(intensity.query(1,roles,"B","MONTHLY_YTD",2026,10,"Asia/Shanghai")).thenReturn(json.readValue(payload,CarbonIntensityContracts.View.class));
        when(versions.version(q)).thenReturn("new");assertThat(service.rebuild(head,"lease",roles)).isFalse();
        verify(repo,never()).publish(any(),any(),any(),any(),anyLong());verifyNoInteractions(cache);
        when(versions.version(q)).thenReturn("v");assertThat(service.rebuild(head,"lost",roles)).isFalse();verifyNoInteractions(cache);
    }
    @Test void damagedPersistentPayloadIsRejectedInsteadOfRecalculatedOnRequest(){
        when(repo.payload(head)).thenReturn("{}");assertThatThrownBy(this::query).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo("CARBON_DISPLAY_INTEGRITY");verifyNoInteractions(flow,intensity);
    }
    @Test void malformedPeriodsAreRejectedBeforeRegistrationAndAllQueryDimensionsSeparateKeys(){
        assertThatThrownBy(()->service.intensity(1,roles,"B","FORMAL_YEAR",2026,1,"Asia/Shanghai")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.jointFlow(1,roles,new JointFlowContracts.Request("B","YEAR","2026","2025-10",null))).isInstanceOf(BusinessException.class);
        assertThat(q.key()).isNotEqualTo(CarbonDisplayQueryContracts.intensity("OTHER","MONTHLY_YTD",2026,10,"Asia/Shanghai").key())
                .isNotEqualTo(CarbonDisplayQueryContracts.intensity("B","MONTH",2026,10,"Asia/Shanghai").key())
                .isNotEqualTo(CarbonDisplayQueryContracts.intensity("B","MONTHLY_YTD",2026,9,"Asia/Shanghai").key());
    }
}
