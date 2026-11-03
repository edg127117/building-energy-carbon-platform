package com.platform.carbon;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class JointFlowGraphTest {
    @Test void supportsOneToThreeGroupsAndExplicitOrderingWithoutChangingTotals() {
        var first=new JointFlowGraph.Target("USAGE","Z","优先节点",true,0);
        var second=new JointFlowGraph.Target("USAGE","A","后置节点",true,9);
        var floor=new JointFlowGraph.Target("FLOOR","F1","一楼",true,0);
        var energy=new JointFlowGraph.Target("ENERGY_TYPE","POWER","电力",true,0);
        for(var order:List.of(List.of(first),List.of(first,floor),List.of(floor,first),List.of(energy,first,floor))) {
            var graph=new JointFlowGraph("tce");graph.add(order,new BigDecimal("1.23456789"));
            var other=new java.util.ArrayList<>(order);other.set(order.indexOf(first),second);graph.add(other,BigDecimal.ONE);
            var result=graph.view();assertThat(result.total()).isEqualByComparingTo("2.23456789");
            assertThat(result.nodes().stream().filter(n->n.depth()==order.indexOf(first)).map(JointFlowContracts.Node::code)).containsExactly("Z","A");
            for(int i=0;i<=order.size();i++) {int depth=i;assertThat(result.nodes().stream().filter(n->n.depth()==depth).map(JointFlowContracts.Node::value).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(result.total());}
        }
    }
    @Test void conservesEveryStageWithDifferentFloorsAndUnallocatedValues() {
        var graph=new JointFlowGraph("tce");
        var lab=new JointFlowGraph.Target("V","LAB","实验室",true,0);
        graph.add(List.of(lab,new JointFlowGraph.Target("V","F2","二层",true,0)),new BigDecimal("2.30"));
        graph.add(List.of(lab,new JointFlowGraph.Target("V","F3","三层",true,0)),new BigDecimal("1.20"));
        graph.add(List.of(new JointFlowGraph.Target("V","UNALLOCATED","未分配",false,0),new JointFlowGraph.Target("V","F3","三层",true,0)),BigDecimal.ONE);
        var view=graph.view();assertThat(view.total()).isEqualByComparingTo("4.5");assertThat(view.unallocated()).isEqualByComparingTo("1");
        for(int depth=0;depth<=2;depth++) { final int stage=depth;assertThat(view.nodes().stream().filter(n->n.depth()==stage).map(n->n.value()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(view.total()); }
        assertThat(view.state()).isEqualTo("PARTIAL");
    }
    @Test void zeroIsRealAndUnknownZeroStillHasIncompleteClassification() {
        var graph=new JointFlowGraph("tCO2e");var unknown=new JointFlowGraph.Target("V","UNALLOCATED","未分配",false,0);
        graph.add(List.of(unknown,unknown),BigDecimal.ZERO);
        assertThat(graph.view().total()).isZero();assertThat(graph.view().state()).isEqualTo("PARTIAL");
        assertThatThrownBy(()->graph.add(List.of(unknown,unknown),new BigDecimal("-1"))).hasMessageContaining("负值");
    }
}
