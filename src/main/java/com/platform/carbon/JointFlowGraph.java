package com.platform.carbon;

import java.math.BigDecimal;
import java.util.*;
import static com.platform.carbon.CarbonErrors.*;
import static com.platform.carbon.JointFlowContracts.*;

/** 每个互斥叶项按配置顺序经过各分组一次；每段单独守恒，不跨段累加为建筑总量。 */
final class JointFlowGraph {
    record Target(String context, String code, String name, boolean allocated, int order) { }
    private record Edge(String source, String target) { }
    private record Identity(int depth, String context, String code, boolean allocated) { }
    private final String unit;
    private final Map<Identity, String> ids = new LinkedHashMap<>();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<Edge, BigDecimal> links = new LinkedHashMap<>();
    private BigDecimal total = BigDecimal.ZERO, unallocated = BigDecimal.ZERO;
    private boolean incompleteClassification;
    JointFlowGraph(String unit) { this.unit = unit; }

    void add(List<Target> groups, BigDecimal value) {
        if (value == null || value.signum() < 0) throw error(409, ACTIVITY_INCOMPLETE, "联合流图不能使用缺失值或负值");
        if(groups==null||groups.isEmpty()||groups.size()>3)throw error(400,VALIDATION_FAILED,"仅允许1至3个分组层");
        String previous=null;
        for(int depth=0;depth<=groups.size();depth++) {
            Target target=depth==groups.size()?new Target("", "TOTAL", "建筑合计", true,0):groups.get(depth);
            String current=node(depth,target,value);
            if(previous!=null)links.merge(new Edge(previous,current),value,BigDecimal::add);
            previous=current;
        }
        total = total.add(value);
        if (groups.stream().anyMatch(t->!t.allocated())) { unallocated = unallocated.add(value); incompleteClassification = true; }
        if (nodes.size() > 500 || links.size() > 2000) throw error(409, LIMIT_EXCEEDED, "联合流图超过500节点或2000连线，请缩小期间");
    }
    private String node(int depth, Target target, BigDecimal value) {
        Identity identity = new Identity(depth, target.context() + (target.allocated() ? "" : "/" + target.name()), target.code(), target.allocated());
        String id = ids.computeIfAbsent(identity, key -> "N" + ids.size());
        Node before = nodes.get(id);
        if (before != null && !before.name().equals(target.name())) throw error(409, VERSION_CONFLICT, "同版分类节点名称不一致");
        nodes.put(id, new Node(id, target.code(), target.name(), depth,
                before == null ? value : before.value().add(value),target.order()));
        return id;
    }
    Graph view() {
        return new Graph(incompleteClassification ? "PARTIAL" : "READY", null, unit, total, unallocated,
                nodes.values().stream().sorted(Comparator.comparingInt(Node::depth).thenComparingInt(Node::order).thenComparing(Node::code).thenComparing(Node::id)).toList(),
                links.entrySet().stream().sorted(Comparator.comparing((Map.Entry<Edge,BigDecimal> e)->e.getKey().source()).thenComparing(e->e.getKey().target()))
                        .map(e -> new Link(e.getKey().source(), e.getKey().target(), e.getValue())).toList());
    }
    static Graph unavailable(String state, String reason, String unit) {
        return new Graph(state, reason, unit, null, null, List.of(), List.of());
    }
}
