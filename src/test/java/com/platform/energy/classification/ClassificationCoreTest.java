package com.platform.energy.classification;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.platform.energy.classification.ClassificationContracts.*;
import static com.platform.energy.classification.ClassificationCore.*;
import static org.assertj.core.api.Assertions.*;

class ClassificationCoreTest {
    private final ClassificationCore core=new ClassificationCore();
    private final List<Meter> meters=List.of(m("A",null,"MAIN"),m("B","A","SUB"),m("C","B","SUB"),m("D","A","SUB"));
    private static Meter m(String id,String parent,String role){return new Meter(id,parent,"BOUNDARY",role,"INBOUND","CONFIRMED");}
    private static Fact f(String id,String qty){return new Fact(id,new BigDecimal(qty),"evidence-"+id,"REVIEWED_MANUAL",true);}
    private List<Fact> facts(){return List.of(f("A","1000"),f("B","600"),f("C","200"),f("D","300"));}
    private Binding b(String scheme,String node,String point){return new Binding(scheme,node,"POINT",point,null,"METERING","CONFIRMED","test evidence");}
    private Configuration config(String mode,List<Binding> bindings){return new Configuration("R1",
            List.of(new Scheme("use","用途","USAGE",mode),new Scheme("space","区域","REGION","ADDITIVE")),
            List.of(new Node("use","root","全部",null),new Node("use","hvac","空调","root"),new Node("use","light","照明","root"),
                    new Node("space","room","房间",null)),bindings,List.of(new BoundaryRule("BOUNDARY","MAIN_WITH_SUBMETER_BREAKDOWN","CONFIRMED","test evidence","ELECTRICITY","kWh")));}
    private Computation run(Configuration c,List<Fact> facts,List<Filter> filters){
        Map<String,Set<String>> resolved=new HashMap<>();c.bindings().forEach(b->resolved.put(bindingKey(b),Set.of(b.objectId())));
        return core.compute(c,"use",filters,new Input(meters,facts,resolved));
    }
    @Test void decomposesThreeLevelsWithoutCountingDescendantTwice(){
        var r=run(config("ADDITIVE",List.of(b("use","hvac","B"),b("use","light","D"))),facts(),List.of());
        assertThat(r.total()).isEqualByComparingTo("1000");assertThat(r.assigned()).isEqualByComparingTo("900");
        assertThat(r.unallocated()).isEqualByComparingTo("100");
        assertThat(r.nodes()).anySatisfy(n->{assertThat(n.nodeCode()).isEqualTo("hvac");assertThat(n.quantity()).isEqualByComparingTo("600");});
        assertThat(r.balances()).anySatisfy(n->{assertThat(n.pointId()).isEqualTo("B");assertThat(n.residual()).isEqualByComparingTo("400");});
    }
    @Test void independentDimensionsUseSameAtomsAndEvidenceForIntersection(){
        var r=run(config("ADDITIVE",List.of(b("use","hvac","B"),b("space","room","C"))),facts(),List.of(new Filter("space","room")));
        assertThat(r.total()).isNull(); // A与B剩余范围没有房间归属，不猜测交集之外的范围。
        assertThat(r.status()).isEqualTo("UNDETERMINED");
        assertThat(r.issues()).anyMatch(i->i.code().equals("UNDETERMINED"));
    }
    @Test void completeCrossDimensionMappingProducesExactIntersection(){
        Configuration c=config("ADDITIVE",List.of(b("use","hvac","B"),b("use","light","D"),b("space","room","A")));
        var r=run(c,facts(),List.of(new Filter("space","room")));
        assertThat(r.total()).isEqualByComparingTo("1000");assertThat(r.assigned()).isEqualByComparingTo("900");
    }
    @Test void sameDimensionAmbiguityRemainsUnallocated(){
        var r=run(config("ADDITIVE",List.of(b("use","hvac","B"),b("use","light","B"))),facts(),List.of());
        assertThat(r.assigned()).isZero();assertThat(r.unallocated()).isEqualByComparingTo("1000");
        assertThat(r.issues()).anyMatch(i->"OVERLAP".equals(i.code()));
    }
    @Test void overlappingAnalysisGroupsDoNotInflateAuthority(){
        var r=run(config("OVERLAPPING",List.of(b("use","hvac","B"),b("use","light","B"))),facts(),List.of());
        assertThat(r.total()).isEqualByComparingTo("1000");assertThat(r.assigned()).isEqualByComparingTo("600");assertThat(r.additive()).isFalse();
        assertThat(r.nodes().stream().filter(n->!"root".equals(n.nodeCode())).map(NodeResult::quantity)).containsExactly(new BigDecimal("600"),new BigDecimal("600"));
    }
    @Test void parentAndDescendantClassificationBindingIsNotDuplicated(){
        var r=run(config("ADDITIVE",List.of(b("use","root","B"),b("use","hvac","C"))),facts(),List.of());
        assertThat(r.assigned()).isEqualByComparingTo("600");
        assertThat(r.nodes().getFirst().quantity()).isEqualByComparingTo("600");
    }
    @Test void descriptionBindingCannotProduceEnergy(){
        Binding description=new Binding("use","hvac","POINT","B",null,"DESCRIPTION","CONFIRMED","test evidence");
        var r=run(config("ADDITIVE",List.of(description)),facts(),List.of());
        assertThat(r.assigned()).isZero();assertThat(r.nodes().get(1).quantity()).isNull();
    }
    @Test void pendingBindingsDoNotProduceNumbers(){
        Binding pending=new Binding("use","hvac","POINT","B",null,"METERING","PENDING_EXPERT","needs wiring");
        var r=run(config("ADDITIVE",List.of(pending)),facts(),List.of());
        assertThat(r.unallocated()).isEqualByComparingTo("1000");
    }
    @Test void missingRootIsNotZeroOrSubmeterSum(){
        var r=run(config("ADDITIVE",List.of()),List.of(f("B","600")),List.of());
        assertThat(r.total()).isNull();assertThat(r.status()).isEqualTo("INCOMPLETE");
    }
    @Test void missingChildIsVisibleAndDoesNotInventZero(){
        var r=run(config("ADDITIVE",List.of()),List.of(f("A","1000"),f("B","600")),List.of());
        assertThat(r.status()).isEqualTo("INCOMPLETE");assertThat(r.issues()).anyMatch(i->i.reference().equals("D"));
    }
    @Test void negativeResidualIsPreservedWithoutTakingAbsoluteValue(){
        var r=run(config("ADDITIVE",List.of()),List.of(f("A","100"),f("B","600")),List.of());
        assertThat(r.balances().getFirst().residual()).isEqualByComparingTo("-500");assertThat(r.status()).isEqualTo("INCOMPLETE");
    }
    @Test void unknownClassificationIsNotAZeroMeasurement(){
        var r=run(config("ADDITIVE",List.of()),facts(),List.of());assertThat(r.nodes()).allMatch(n->n.quantity()==null&&n.status().equals("UNMETERED"));
    }
    @Test void roleSumSelectsNonoverlappingSubmeters(){
        Configuration c=config("ADDITIVE",List.of(b("use","hvac","B")));
        c=new Configuration(c.relationVersionId(),c.schemes(),c.nodes(),c.bindings(),List.of(new BoundaryRule("BOUNDARY","SUBMETER_SUM","CONFIRMED","test","ELECTRICITY","kWh")));
        assertThat(run(c,facts(),List.of()).total()).isEqualByComparingTo("900");
    }
    @Test void duplicateFactsAndCyclesAreRejected(){
        Configuration c=config("ADDITIVE",List.of());
        assertThatThrownBy(()->run(c,List.of(f("A","1"),f("A","1")),List.of())).hasMessageContaining("冲突");
        var cycle=new Configuration("R",c.schemes(),List.of(new Node("use","a","A","b"),new Node("use","b","B","a")),List.of(),c.boundaryRules());
        assertThatThrownBy(()->core.validate(cycle)).hasMessageContaining("循环");
        assertThatThrownBy(()->core.compute(c,"use",List.of(),new Input(List.of(m("A","B","SUB"),m("B","A","SUB")),facts(),Map.of()))).hasMessageContaining("循环");
    }
    @Test void rejectsCrossBoundaryParentAndUnknownFilter(){
        Configuration c=config("ADDITIVE",List.of());
        assertThatThrownBy(()->core.compute(c,"use",List.of(),new Input(List.of(m("A",null,"MAIN"),new Meter("B","A","OTHER","SUB","INBOUND","CONFIRMED")),facts(),Map.of()))).hasMessageContaining("跨边界");
        assertThatThrownBy(()->run(c,facts(),List.of(new Filter("use","absent")))).hasMessageContaining("筛选节点");
    }
    @Test void independentLayoutWithThreeHundredMetersNeedsNoBuildingSpecificLogic(){
        List<Meter> all=new ArrayList<>();List<Fact> facts=new ArrayList<>();
        for(int i=0;i<300;i++) {all.add(m("M"+i,null,"INDEPENDENT"));facts.add(f("M"+i,"1"));}
        Configuration c=config("ADDITIVE",List.of());
        c=new Configuration(c.relationVersionId(),c.schemes(),c.nodes(),c.bindings(),List.of(new BoundaryRule("BOUNDARY","INDEPENDENT_METER_SUM","CONFIRMED","test","ELECTRICITY","kWh")));
        var result=core.compute(c,"use",List.of(),new Input(all,facts,Map.of()));
        assertThat(result.total()).isEqualByComparingTo("300");assertThat(result.balances()).hasSize(300);
    }
    @Test void unknownAndMissingNodesNeverDefaultToAZeroReading(){
        var pending=config("ADDITIVE",List.of(new Binding("use","hvac","POINT","B",null,"METERING","PENDING_EXPERT","test")));
        assertThat(run(pending,facts(),List.of()).nodes().get(1).status()).isEqualTo("PENDING_EXPERT");
    }
    @Test void reversedIndependentFlowKeepsSignedQuantity(){
        Configuration c=config("ADDITIVE",List.of());
        c=new Configuration(c.relationVersionId(),c.schemes(),c.nodes(),c.bindings(),List.of(new BoundaryRule("BOUNDARY","INDEPENDENT_METER_SUM","CONFIRMED","test","ELECTRICITY","kWh")));
        var result=core.compute(c,"use",List.of(),new Input(List.of(m("IN",null,"INDEPENDENT"),new Meter("OUT",null,"BOUNDARY","INDEPENDENT","OUTBOUND","CONFIRMED")),List.of(f("IN","1000"),f("OUT","200")),Map.of()));
        assertThat(result.total()).isEqualByComparingTo("800");
    }
}
