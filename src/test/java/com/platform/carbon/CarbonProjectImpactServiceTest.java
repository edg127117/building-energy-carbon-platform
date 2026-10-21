package com.platform.carbon;

import com.platform.energy.classification.ClassificationCore.SourceSlice;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.period.EnergyPeriodSnapshotReader;
import com.platform.energy.summary.EnergyBoundarySummaryService;
import com.platform.energy.summary.api.EnergySummaryContracts.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static com.platform.carbon.CarbonProjectContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离替身只提供真实月封账契约形状；不连接外部数据库或现场设备。 */
class CarbonProjectImpactServiceTest {
    private static final Instant FROM=Instant.parse("2026-07-31T16:00:00Z"),TO=Instant.parse("2026-08-31T16:00:00Z");
    private static final List<String> ROLES=List.of("ENERGY_MANAGER");
    private final CarbonProjectService projects=mock(CarbonProjectService.class);
    private final ClassificationService classifications=mock(ClassificationService.class);
    private final EnergyBoundarySummaryService summaries=mock(EnergyBoundarySummaryService.class);
    private final EnergyPeriodSnapshotReader snapshots=mock(EnergyPeriodSnapshotReader.class);
    private final CarbonProjectImpactService service=new CarbonProjectImpactService(projects,new CarbonProjectCore(),classifications,summaries,snapshots);

    @BeforeEach void setup() {
        var config=new Configuration("CLASS",List.of(new Energy("ELEC","PURCHASED_ELECTRICITY","ENABLED")),
                List.of(new Exclusion("ELEC",List.of("B"),"租户不纳入","合同证据")));
        when(projects.versions(1,ROLES,"BLD")).thenReturn(List.of(new Version("P","BLD",1,3,"DRAFT",null,null,1,null,config)));
        when(snapshots.listVisibleSnapshots("BLD","MONTH",FROM,TO,5001)).thenReturn(List.of(snapshot()));
        var group=new SummaryGroupView(Map.of("ENERGY_ITEM","ELEC","PERIOD",FROM+"/"+TO),Map.of("KWH",new BigDecimal("1000")),
                Map.of("KWH",new BigDecimal("1000")),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),
                BigDecimal.ONE,0,"LOCKED_COMPLETE","COMPLETE",List.of("REL"),List.of(),List.of("POLICY"),List.of("hash"),"FORMAL");
        when(summaries.query(eq(1L),eq(ROLES),any())).thenReturn(new SummaryQueryView("BLD","MONTH",FROM,TO,List.of("ENERGY_ITEM","PERIOD"),1,List.of(group)));
    }

    @Test void previewUsesSavedRevisionAndLeavesEnergyTotalUnchanged() {
        var a=new SourceSlice("A",new BigDecimal("400"),Set.of("A"),true,List.of("S"),Map.of());
        var b=new SourceSlice("B",new BigDecimal("600"),Set.of("A","B"),false,List.of("S"),Map.of());
        when(classifications.projectSealed(eq(1L),eq(ROLES),eq("BLD"),eq("CLASS"),eq(FROM),eq(TO),eq("ELEC"),eq("KWH"),any()))
                .thenReturn(new ClassificationService.SealedProjection("CLASS","REL",new BigDecimal("1000"),List.of(a,b),null));
        var impact=service.preview(1,ROLES,"BLD","P",FROM,TO);
        assertThat(impact.ready()).isTrue();assertThat(impact.revision()).isEqualTo(3);
        assertThat(impact.calculationStatus()).isEqualTo("NOT_CALCULATED");
        assertThat(impact.lines()).singleElement().satisfies(line->{
            assertThat(line.grossQuantity()).isEqualByComparingTo("1000");
            assertThat(line.includedQuantity()).isEqualByComparingTo("400");
            assertThat(line.excludedQuantity()).isEqualByComparingTo("600");
        });
        verify(projects,never()).activate(anyLong(),any(),any(),any(),any());
    }

    @Test void unverifiableExclusionStaysUnavailableRatherThanZero() {
        when(classifications.projectSealed(anyLong(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenThrow(CarbonErrors.error(409,CarbonErrors.ACTIVITY_INCOMPLETE,"缺少子表封账"));
        var result=service.preview(1,ROLES,"BLD","P",FROM,TO);
        assertThat(result.ready()).isFalse();
        assertThat(result.lines().getFirst().excludedQuantity()).isNull();
        assertThat(result.issues().getFirst().message()).contains("缺少子表");
    }

    @Test void authorizationFailureCannotReadSnapshotsOrTurnIntoPreviewWarning() {
        doThrow(CarbonErrors.error(403,CarbonErrors.FORBIDDEN,"建筑越权")).when(projects).versions(1,ROLES,"OTHER");
        assertThatThrownBy(()->service.preview(1,ROLES,"OTHER","P",FROM,TO)).hasMessageContaining("越权");
        verifyNoInteractions(snapshots,summaries,classifications);
    }

    @Test void changedSnapshotCountRejectsMixedReadVersions() {
        when(snapshots.listVisibleSnapshots("BLD","MONTH",FROM,TO,5001)).thenReturn(List.of());
        assertThatThrownBy(()->service.preview(1,ROLES,"BLD","P",FROM,TO)).hasMessageContaining("版本不一致");
        verifyNoInteractions(classifications);
    }

    @Test void missingAndReservedHaveNoFabricatedQuantities() {
        var cfg=new Configuration("CLASS",List.of(new Energy("ELEC","PURCHASED_ELECTRICITY","ENABLED"),new Energy("WATER","WATER","RESERVED")),List.of());
        when(projects.versions(1,ROLES,"BLD")).thenReturn(List.of(new Version("P","BLD",1,3,"DRAFT",null,null,1,null,cfg)));
        when(summaries.query(eq(1L),eq(ROLES),any())).thenReturn(new SummaryQueryView("BLD","MONTH",FROM,TO,List.of(),0,List.of()));
        when(snapshots.listVisibleSnapshots("BLD","MONTH",FROM,TO,5001)).thenReturn(List.of());
        var result=service.preview(1,ROLES,"BLD","P",FROM,TO);
        assertThat(result.ready()).isFalse();assertThat(result.lines()).hasSize(2);
        assertThat(result.lines()).allSatisfy(line->assertThat(line.grossQuantity()).isNull());
        assertThat(result.lines()).extracting(CarbonProjectImpactService.Line::status).containsExactly("MISSING","RESERVED");
    }

    private PeriodSnapshot snapshot() {
        return new PeriodSnapshot("S","KEY","PROJECTION","BLD","A","MONTH",FROM,TO,"Asia/Shanghai","P",1,
                "LOCKED_COMPLETE","FORMAL","ELEC",new BigDecimal("1000"),"KWH",BigDecimal.ZERO,"TCE",BigDecimal.ONE,"","",
                "{}","hash","{}",TO,null,null,1,LocalDateTime.of(2026,9,1,0,0));
    }
}
