package com.platform.energy.activity;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.qualityusage.*;
import com.platform.iot.qualityusage.QualityUsageModels.*;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.platform.energy.activity.EnergyActivityDataReader.*;
import static com.platform.energy.activity.EnergyActivityPointCatalog.*;
import static com.platform.iot.qualityusage.QualityUsageModels.ENERGY_ACTIVITY_AGGREGATION;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only local port doubles; verifies bulk monitoring keeps the original governance gates. */
class EnergyActivityMonitoringSnapshotsTest {
    final BuildingScopeService scope=mock(BuildingScopeService.class);
    final EnergyActivityPointCatalog catalog=mock(EnergyActivityPointCatalog.class);
    final EnergyActivityDataReader reader=mock(EnergyActivityDataReader.class);
    final QualityUsagePolicyResolver quality=mock(QualityUsagePolicyResolver.class);
    final ResolutionContext context=mock(ResolutionContext.class);
    final EnergyActivityDataService service=new EnergyActivityDataService(scope,catalog,reader,quality);
    final Set<String> roles=Set.of("ENERGY_MANAGER");
    @BeforeEach void setup() {
        when(catalog.find(eq("B"),anySet())).thenReturn(List.of(profile("A","CONFIRMED"),profile("B","CONFIRMED")));
        when(quality.historyContext(anySet(),eq(ENERGY_ACTIVITY_AGGREGATION),anyLong(),anyLong())).thenReturn(context);
        when(quality.resolve(eq(context),anyString(),eq(ENERGY_ACTIVITY_AGGREGATION),anyLong(),anyInt())).thenAnswer(call->{
            int q=call.getArgument(4);return new Resolution(q==1?Decision.BLOCK:Decision.ALLOW,q,ENERGY_ACTIVITY_AGGREGATION,PolicySource.SYSTEM_DEFAULT_Q0_ONLY,null,7,"test");
        });
    }
    PointProfile profile(String point,String status){return new PointProfile(point,point,"KWH","profile-"+point,"ELECTRICITY","GRID","CUMULATIVE",status,3);}
    RawEvent event(String point,String building,long at,long received,int q){return new RawEvent(point,point,building,"TEST",point,"DEVICE",at,at,received,q,false);}
    @Test void pagesShareOnePolicyAndKeepWatermarkQualityAndInclusiveBoundaryFacts() {
        var points=Set.of("A","B");var next=new Cursor(150,"A");
        when(reader.readRawEvents("B",points,100,201,null,500)).thenReturn(new RawEventPage(List.of(event("A","B",100,100,0),event("A","B",150,301,0)),true,next));
        when(reader.readRawEvents("B",points,100,201,next,500)).thenReturn(new RawEventPage(List.of(event("B","B",190,200,1),event("A","B",200,200,0)),false,null));
        var snapshots=service.monitoringSnapshots(7L,roles,"B",List.of("A","B"),100,200,300);
        assertThat(snapshots).hasSize(2);assertThat(snapshots.get("A").items()).extracting(v->v.eventTime()).containsExactly(100L,200L);
        assertThat(snapshots.get("B").items()).isEmpty();assertThat(snapshots.get("A").activityWatermark()).isEqualTo(300);
        assertThat(snapshots.get("A").profileRevision()).isEqualTo(3);
        assertThat(snapshots.get("A").items()).allSatisfy(v->assertThat(v.policyConfigRevision()).isEqualTo(7));
        verify(quality).historyContext(points,ENERGY_ACTIVITY_AGGREGATION,100,201);verify(scope).checkAccess(7L,roles,"B");
        verify(reader,never()).readLatestAtOrBefore(anyString(),anyString(),anyLong());
    }
    @Test void unconfirmedProfileAndDeniedBuildingNeverReadRawFacts() {
        when(catalog.find(eq("B"),anySet())).thenReturn(List.of(profile("A","CONFIRMED"),profile("B","PENDING_EXPERT")));
        assertThatThrownBy(()->service.monitoringSnapshots(7L,roles,"B",List.of("A","B"),100,200,300)).isInstanceOf(BusinessException.class);
        doThrow(new BusinessException(403,"DENIED","denied")).when(scope).checkAccess(8L,roles,"B");
        assertThatThrownBy(()->service.monitoringSnapshots(8L,roles,"B",List.of("A"),100,200,300)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reader);
    }
    @Test void wrongBuildingOrWindowAndMissingCursorCannotYieldPartialSuccess() {
        when(reader.readRawEvents(eq("B"),anySet(),anyLong(),anyLong(),any(),eq(500))).thenReturn(new RawEventPage(List.of(event("A","OTHER",100,100,0)),false,null));
        assertThatThrownBy(()->service.monitoringSnapshots(7L,roles,"B",List.of("A"),100,200,300)).isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getErrorCode()).isEqualTo("ENERGY_ACTIVITY_DATA_SCOPE_MISMATCH");
        when(reader.readRawEvents(eq("B"),anySet(),anyLong(),anyLong(),any(),eq(500))).thenReturn(new RawEventPage(List.of(event("A","B",99,100,0)),false,null));
        assertThatThrownBy(()->service.monitoringSnapshots(7L,roles,"B",List.of("A"),100,200,300)).isInstanceOf(BusinessException.class);
        when(reader.readRawEvents(eq("B"),anySet(),anyLong(),anyLong(),any(),eq(500))).thenReturn(new RawEventPage(List.of(event("A","B",100,100,0)),true,null));
        assertThatThrownBy(()->service.monitoringSnapshots(7L,roles,"B",List.of("A"),100,200,300)).isInstanceOf(BusinessException.class);
    }
    @Test void originalPerPointFactLimitStillRejectsAnOversizedBulkPageSequence() {
        var offset=new AtomicInteger();
        when(reader.readRawEvents(eq("B"),anySet(),eq(0L),eq(20001L),any(),eq(500))).thenAnswer(call->{
            int from=offset.getAndAdd(500),to=Math.min(from+500,10001);var rows=new ArrayList<RawEvent>();
            for(int i=from;i<to;i++)rows.add(event("A","B",i,i,0));
            return new RawEventPage(rows,to<10001,to<10001?new Cursor(to-1,"A"):null);
        });
        assertThatThrownBy(()->service.monitoringSnapshots(7L,roles,"B",List.of("A"),0,20000,30000)).isInstanceOf(BusinessException.class).hasMessageContaining("上限");
    }
}
