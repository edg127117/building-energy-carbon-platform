package com.platform.carbon;

import com.platform.energy.classification.ClassificationCore.SourceSlice;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.platform.carbon.CarbonProjectContracts.*;
import static org.assertj.core.api.Assertions.*;

class CarbonProjectCoreTest {
    private final CarbonProjectCore core=new CarbonProjectCore();
    private static final Energy ELECTRICITY=new Energy("ELEC_CUSTOM","PURCHASED_ELECTRICITY","ENABLED");
    private static SourceSlice slice(String point,String quantity,String... parents){return new SourceSlice(point,new BigDecimal(quantity),Set.of(parents),false,List.of("sealed-"+point),Map.of());}
    private static Exclusion exclude(String... points){return new Exclusion("ELEC_CUSTOM",List.of(points),"外部租户独立边界","确认单");}
    private static Configuration config(Exclusion... exclusions){return new Configuration("class-v1",List.of(ELECTRICITY),List.of(exclusions));}
    @Test void frozenSliceEvidenceIsIndependentOfSetInsertionOrder() {
        var first=new SourceSlice("B",BigDecimal.TEN,new LinkedHashSet<>(List.of("B","A")),false,List.of("s2","s1"),Map.of("U",new LinkedHashSet<>(List.of("N2","N1"))));
        var second=new SourceSlice("B",BigDecimal.TEN,new LinkedHashSet<>(List.of("A","B")),false,List.of("s1","s2"),Map.of("U",new LinkedHashSet<>(List.of("N1","N2"))));
        assertThat(CarbonEvidence.canonical(CarbonEvidence.tree(first))).isEqualTo(CarbonEvidence.canonical(CarbonEvidence.tree(second)));
    }
    @Test void excludesSubtreeExactlyOnceAndPreservesResidual() {
        var sources=List.of(slice("A","100","A"),slice("B","600","A","B"),slice("C","300","A","C"));
        var result=core.apply(config(exclude("B"),exclude("B")),"ELEC_CUSTOM",sources);
        assertThat(result.included()).isEqualByComparingTo("400");assertThat(result.excluded()).isEqualByComparingTo("600");
        assertThat(result.excludedSlices()).hasSize(1);assertThat(sources).hasSize(3);
    }
    @Test void ancestorAndDescendantSelectionsFormUnion() {
        var sources=List.of(slice("A","400","A"),slice("C","600","A","B","C"));
        var result=core.apply(config(exclude("A","B")),"ELEC_CUSTOM",sources);
        assertThat(result.included()).isZero();assertThat(result.excluded()).isEqualByComparingTo("1000");
    }
    @Test void unknownOrUnsplitChildDoesNotBecomeAnEstimatedSubtraction() {
        assertThatThrownBy(()->core.apply(config(exclude("B")),"ELEC_CUSTOM",List.of(slice("A","1000","A"))))
                .hasMessageContaining("独立计量组成");
    }
    @Test void rejectsDuplicateAtomsAndNegativeQuantities() {
        assertThatThrownBy(()->core.apply(config(),"ELEC_CUSTOM",List.of(slice("A","1","A"),slice("A","1","A")))).hasMessageContaining("重复");
        assertThatThrownBy(()->core.apply(config(),"ELEC_CUSTOM",List.of(slice("A","-1","A")))).hasMessageContaining("无效");
    }
    @Test void reservedWaterAndGreenNeverBecomeZeroEmissionCalculations() {
        for(String path:List.of("WATER","GREEN_ENERGY")) {
            var reserved=new Configuration(null,List.of(new Energy(path,path,"RESERVED")),List.of());core.validate(reserved);
            assertThatThrownBy(()->core.apply(reserved,path,List.of())).hasMessageContaining("未在项目中启用");
            assertThatThrownBy(()->core.validate(new Configuration(null,List.of(new Energy(path,path,"ENABLED")),List.of()))).hasMessageContaining("只能预留");
        }
    }
    @Test void reservedCapabilityDoesNotOccupyCustomEnergyCodeNamespace() {
        var cfg=new Configuration("CLASS",List.of(new Energy("WATER","WATER","RESERVED"),new Energy("WATER","PURCHASED_ELECTRICITY","ENABLED")),List.of());
        assertThat(core.apply(cfg,"WATER",List.of(slice("A","100","A"))).included()).isEqualByComparingTo("100");
    }
    @Test void mobileAndMissingClassificationAreRejected() {
        assertThatThrownBy(()->core.validate(new Configuration(null,List.of(new Energy("DIESEL","MOBILE_COMBUSTION","ENABLED")),List.of()))).hasMessageContaining("移动源");
        assertThatThrownBy(()->core.validate(new Configuration(null,List.of(ELECTRICITY),List.of()))).hasMessageContaining("缺失");
    }
    @Test void exclusionRequiresReasonEvidenceAndAnEnabledEnergy() {
        assertThatThrownBy(()->core.validate(config(new Exclusion("ELEC_CUSTOM",List.of("A"),"","e")))).hasMessageContaining("缺失");
        assertThatThrownBy(()->core.validate(config(new Exclusion("GAS",List.of("A"),"r","e")))).hasMessageContaining("未启用能源");
    }
    @Test void unconfiguredActivityCannotBeSilentlyDropped() {
        assertThatThrownBy(()->core.apply(config(),"GAS",List.of(slice("A","10","A")))).hasMessageContaining("静默忽略");
    }
}
