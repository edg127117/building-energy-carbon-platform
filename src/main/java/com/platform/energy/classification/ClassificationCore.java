package com.platform.energy.classification;

import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.platform.energy.classification.ClassificationContracts.*;

/** 将已确认计量树分成互不重叠的贡献片段，再按独立分类投影；不按设备数或面积估算。 */
@Component
public class ClassificationCore {
    public record Meter(String pointId, String parentId, String boundaryId, String role,
                        String direction, String status) {}
    public record Fact(String pointId, BigDecimal quantity, String evidenceId, String nature,
                       boolean complete) {}
    public record Input(List<Meter> meters, List<Fact> facts,
                        Map<String, Set<String>> resolvedBindingPoints) {}
    public record Computation(BigDecimal total, BigDecimal assigned, BigDecimal unallocated,
                              BigDecimal residual, String status, String nature, boolean additive,
                              List<NodeResult> nodes, List<Balance> balances, List<Issue> issues,
                              List<String> evidenceIds, List<SourceSlice> sourceSlices) {}
    /** 同一计量树的互斥组成；分类仅投影归属，不另生成一份活动量。 */
    public record SourceSlice(String pointId, BigDecimal quantity, Set<String> ancestors,
                              boolean residual, List<String> evidenceIds,
                              Map<String, Set<String>> classifications) {
        public SourceSlice {
            // 集合会进入冻结证据摘要，必须稳定排序，不能依赖JVM的Set迭代顺序。
            ancestors=Collections.unmodifiableSet(new TreeSet<>(ancestors));
            evidenceIds=evidenceIds.stream().distinct().sorted().toList();
            Map<String,Set<String>> ordered=new TreeMap<>();
            classifications.forEach((scheme,nodes)->ordered.put(scheme,Collections.unmodifiableSet(new TreeSet<>(nodes))));
            classifications=Collections.unmodifiableMap(ordered);
        }
    }
    private record Atom(String pointId, BigDecimal quantity, Set<String> ancestors,
                        boolean residual, List<String> evidenceIds) {}

    public List<Issue> validate(Configuration config) {
        if (config == null || config.schemes() == null || config.nodes() == null
                || config.bindings() == null || config.boundaryRules() == null) fail("配置列表不能为空");
        if (config.schemes().size() > 30 || config.nodes().size() > 5000
                || config.bindings().size() > 10000 || config.boundaryRules().size() > 500) fail("配置超过有界处理上限");
        Map<String, Scheme> schemes = new HashMap<>();
        for (Scheme scheme : config.schemes()) {
            if (scheme == null) fail("分类方案不能为null");
            code(scheme.code()); text(scheme.name(), 160);
            if (scheme.dimension() == null || scheme.mode() == null || !Set.of("USAGE", "DEPARTMENT", "CIRCUIT", "REGION", "SPACE").contains(scheme.dimension())
                    || !Set.of("ADDITIVE", "OVERLAPPING").contains(scheme.mode())) fail("分类维度或汇总模式无效");
            if (schemes.put(scheme.code(), scheme) != null) fail("分类方案编码重复");
        }
        Map<String, Node> nodes = new HashMap<>();
        for (Node node : config.nodes()) {
            if (node == null) fail("分类节点不能为null");
            code(node.code()); text(node.name(), 160);
            if (!schemes.containsKey(node.schemeCode())) fail("分类节点引用不存在的方案");
            if (nodes.put(key(node.schemeCode(), node.code()), node) != null) fail("同方案分类节点编码重复");
        }
        for (Node node : config.nodes()) {
            Set<String> seen = new HashSet<>(); Node current = node;
            while (current != null) {
                if (!seen.add(current.code())) fail("分类父子关系形成循环");
                if (seen.size() > 64) fail("分类树深度超过64级上限");
                if (blank(current.parentCode())) break;
                current = nodes.get(key(current.schemeCode(), current.parentCode()));
                if (current == null) fail("分类父节点不存在");
            }
        }
        List<Issue> issues = new ArrayList<>(); Set<String> bindings = new HashSet<>();
        for (Binding binding : config.bindings()) {
            if (binding == null) fail("绑定不能为null");
            if (!nodes.containsKey(key(binding.schemeCode(), binding.nodeCode()))) fail("绑定分类节点不存在");
            if (binding.objectType() == null || !Set.of("POINT", "EQUIPMENT", "SPACE").contains(binding.objectType())) fail("绑定对象类型无效");
            text(binding.objectId(), 80);
            if (binding.semantics() == null || !Set.of("DESCRIPTION", "METERING").contains(binding.semantics())) fail("绑定语义无效");
            confirmation(binding.status(), binding.evidence());
            String identity = binding.schemeCode()+"/"+binding.nodeCode()+"/"+binding.objectType()+"/"
                    +binding.objectId()+"/"+binding.meterPointId()+"/"+binding.semantics();
            if (!bindings.add(identity)) fail("重复绑定");
            if ("PENDING_EXPERT".equals(binding.status())) issues.add(issue("PENDING_EXPERT", binding.nodeCode(), "绑定待专业确认"));
        }
        Set<String> boundaries = new HashSet<>();
        for (BoundaryRule rule : config.boundaryRules()) {
            if (rule == null) fail("边界规则不能为null");
            text(rule.boundaryId(), 80); confirmation(rule.status(), rule.evidence());
            text(rule.energyItemCode(), 64); text(rule.unit(), 32);
            if (rule.mode() == null || !Set.of("MAIN_METER_TOTAL", "SUBMETER_SUM", "INDEPENDENT_METER_SUM", "MAIN_WITH_SUBMETER_BREAKDOWN")
                    .contains(rule.mode())) fail("计量汇总口径无效");
            if (!boundaries.add(rule.boundaryId())) fail("同一边界汇总规则重复");
            if ("PENDING_EXPERT".equals(rule.status())) issues.add(issue("PENDING_EXPERT", rule.boundaryId(), "计量口径待专业确认"));
        }
        if (blank(config.relationVersionId())) issues.add(issue("PENDING_EXPERT", "relationVersionId", "尚未关联正式计量关系版本"));
        return List.copyOf(issues);
    }

    public Computation compute(Configuration config, String schemeCode, List<Filter> filters, Input input) {
        validate(config);
        Scheme scheme = config.schemes().stream().filter(s -> s.code().equals(schemeCode)).findFirst()
                .orElseThrow(() -> error(400, "分类方案不存在"));
        if (filters == null) filters = List.of();
        if (filters.size() > 5) fail("联合筛选最多五个条件");
        for (Filter filter : filters) if (filter == null || config.nodes().stream().noneMatch(n ->
                n.schemeCode().equals(filter.schemeCode()) && n.code().equals(filter.nodeCode()))) fail("筛选节点不存在");
        Map<String, Meter> meters = new LinkedHashMap<>();
        for (Meter meter : input.meters()) if (meters.put(meter.pointId(), meter) != null) fail("计量来源重复");
        for (Meter meter : meters.values()) {
            Set<String> seen = new HashSet<>(); Meter current = meter;
            while (current != null) {
                if (!seen.add(current.pointId())) fail("计量关系形成循环");
                if (blank(current.parentId())) break;
                Meter parent = meters.get(current.parentId());
                if (parent == null) fail("计量父节点缺失");
                if (!Objects.equals(parent.boundaryId(), current.boundaryId()) || !Objects.equals(parent.direction(), current.direction())) fail("跨边界或跨方向的上下级计量不能直接求差");
                current = parent;
            }
        }
        Map<String, Fact> facts = new HashMap<>();
        for (Fact fact : input.facts()) {
            if (fact.quantity() == null || fact.quantity().signum() < 0) fail("原生用量无效");
            if (facts.put(fact.pointId(), fact) != null) fail("同期间计量来源存在冲突数据，需先确认来源");
        }
        List<Issue> issues = new ArrayList<>(validate(config)); List<Balance> balances = new ArrayList<>(); List<Atom> atoms = new ArrayList<>();
        for (Binding binding : config.bindings()) if ("METERING".equals(binding.semantics())
                && "CONFIRMED".equals(binding.status()) && input.resolvedBindingPoints().getOrDefault(bindingKey(binding), Set.of()).isEmpty()) {
            issues.add(issue("PENDING_EXPERT", binding.nodeCode(), "对象尚无已确认的正式计量覆盖，不能从描述推算用量"));
        }
        BigDecimal total = BigDecimal.ZERO; boolean totalKnown = true; boolean hasRoot = false;
        for (BoundaryRule rule : config.boundaryRules()) {
            List<Meter> group = meters.values().stream().filter(m -> rule.boundaryId().equals(m.boundaryId())).toList();
            if (group.isEmpty()) { issues.add(issue("INCOMPLETE", rule.boundaryId(), "边界缺少计量结构")); totalKnown = false; continue; }
            if (!"CONFIRMED".equals(rule.status()) || group.stream().anyMatch(m -> !"CONFIRMED".equals(m.status()))) {
                issues.add(issue("PENDING_EXPERT", rule.boundaryId(), "计量结构或汇总口径待确认")); totalKnown = false; continue;
            }
            String role = switch (rule.mode()) {
                case "MAIN_METER_TOTAL", "MAIN_WITH_SUBMETER_BREAKDOWN" -> "MAIN";
                case "SUBMETER_SUM" -> "SUB";
                default -> "INDEPENDENT";
            };
            List<Meter> selected = group.stream().filter(m -> role.equals(m.role())).toList();
            Set<String> candidates = selected.stream().map(Meter::pointId).collect(Collectors.toSet());
            selected = selected.stream().filter(m -> ancestorSet(m, meters).stream()
                    .noneMatch(a -> !a.equals(m.pointId()) && candidates.contains(a))).toList();
            if (selected.isEmpty() || ("MAIN".equals(role) && selected.size() != 1)) {
                issues.add(issue("PENDING_EXPERT", rule.boundaryId(), "总量来源缺失或总表数量冲突")); totalKnown = false; continue;
            }
            for (Meter root : selected) {
                hasRoot = true;
                if (!Set.of("INBOUND", "OUTBOUND").contains(root.direction())) { totalKnown = false; issues.add(issue("PENDING_EXPERT", root.pointId(), "计量方向未知")); continue; }
                Fact fact = facts.get(root.pointId());
                if (fact == null) { totalKnown = false; issues.add(issue("INCOMPLETE", root.pointId(), "总量来源缺数")); continue; }
                total = total.add(signed(fact.quantity(), root));
                split(root, meters, facts, atoms, balances, issues, "MAIN_METER_TOTAL".equals(rule.mode()));
            }
        }
        if (!hasRoot) totalKnown = false;
        Map<String, Node> nodeMap = config.nodes().stream().collect(Collectors.toMap(n -> key(n.schemeCode(), n.code()), Function.identity()));
        List<NodeResult> results = new ArrayList<>();
        Map<Atom, Set<String>> matched = new LinkedHashMap<>();
        Map<Atom, Boolean> filterPass = new LinkedHashMap<>();
        for (Atom atom : atoms) {
            boolean passes = true;
            for (Filter filter : filters) {
                Set<String> assigned = assigned(config, filter.schemeCode(), atom, input.resolvedBindingPoints(), nodeMap);
                boolean overlapping = config.schemes().stream().anyMatch(s -> s.code().equals(filter.schemeCode()) && "OVERLAPPING".equals(s.mode()));
                if (assigned.isEmpty() || (!overlapping && assigned.size() != 1)) { issues.add(issue("UNDETERMINED", atom.pointId(), "联合筛选缺少明确交集归属证据")); passes = false; }
                else if (assigned.stream().noneMatch(a -> descendant(a, filter.nodeCode(), filter.schemeCode(), nodeMap))) passes = false;
            }
            filterPass.put(atom, passes);
            matched.put(atom, assigned(config, schemeCode, atom, input.resolvedBindingPoints(), nodeMap));
        }
        boolean additive = "ADDITIVE".equals(scheme.mode());
        BigDecimal assignedTotal = BigDecimal.ZERO; BigDecimal unallocated = BigDecimal.ZERO;
        for (Atom atom : atoms) if (filterPass.get(atom)) {
            Set<String> targets = matched.get(atom);
            if (targets.isEmpty() || (additive && targets.size() != 1)) {
                unallocated = unallocated.add(atom.quantity());
                if (targets.size() > 1) issues.add(issue("OVERLAP", atom.pointId(), "可加总分类存在多重归属，保留未分配"));
            } else assignedTotal = assignedTotal.add(atom.quantity());
        }
        boolean unknownFilter = issues.stream().anyMatch(i -> "UNDETERMINED".equals(i.code()));
        boolean incomplete = issues.stream().anyMatch(i -> Set.of("INCOMPLETE", "PENDING_EXPERT", "NEGATIVE_RESIDUAL").contains(i.code()));
        for (Node node : config.nodes()) if (node.schemeCode().equals(schemeCode)) {
            boolean pending = config.bindings().stream().anyMatch(b -> b.schemeCode().equals(schemeCode)
                    && descendant(b.nodeCode(), node.code(), schemeCode, nodeMap) && "METERING".equals(b.semantics())
                    && ("PENDING_EXPERT".equals(b.status()) || input.resolvedBindingPoints().getOrDefault(bindingKey(b), Set.of()).isEmpty()));
            List<Atom> selected = atoms.stream().filter(a -> filterPass.get(a)).filter(a -> {
                Set<String> targets = matched.get(a);
                return (!additive || targets.size() == 1) && targets.stream().anyMatch(t -> descendant(t, node.code(), schemeCode, nodeMap));
            }).toList();
            results.add(new NodeResult(node.code(), node.name(), selected.isEmpty() || unknownFilter ? null : sum(selected),
                    unknownFilter ? "UNDETERMINED" : selected.isEmpty() ? pending ? "PENDING_EXPERT" : "UNMETERED" : incomplete ? "INCOMPLETE" : "CALCULABLE",
                    selected.stream().map(Atom::pointId).distinct().toList(), selected.stream().flatMap(a -> a.evidenceIds().stream()).distinct().toList()));
        }
        if (!filters.isEmpty()) total = assignedTotal.add(unallocated);
        String status = !totalKnown || issues.stream().anyMatch(i -> Set.of("INCOMPLETE", "PENDING_EXPERT", "NEGATIVE_RESIDUAL").contains(i.code())) ? "INCOMPLETE" : unknownFilter ? "UNDETERMINED" : "CALCULABLE";
        String nature = input.facts().isEmpty() ? "UNDETERMINED" : input.facts().stream().allMatch(f -> "FORMAL".equals(f.nature())) ? "FORMAL_SOURCE" : "SOURCE_LABELLED_STATISTICS";
        return new Computation(totalKnown && !unknownFilter ? total : null, unknownFilter ? null : assignedTotal,
                unknownFilter ? null : unallocated, atoms.stream().filter(Atom::residual).map(Atom::quantity).reduce(BigDecimal.ZERO, BigDecimal::add),
                status, nature, additive, results, balances, issues,
                atoms.stream().flatMap(a -> a.evidenceIds().stream()).distinct().toList(),
                atoms.stream().map(a -> {
                    Map<String,Set<String>> assignments=new LinkedHashMap<>();
                    for(Scheme s:config.schemes()) assignments.put(s.code(),Set.copyOf(
                            assigned(config,s.code(),a,input.resolvedBindingPoints(),nodeMap)));
                    return new SourceSlice(a.pointId(),a.quantity(),a.ancestors(),a.residual(),
                            a.evidenceIds(),Map.copyOf(assignments));
                }).toList());
    }

    private void split(Meter meter, Map<String, Meter> meters, Map<String, Fact> facts, List<Atom> atoms,
                       List<Balance> balances, List<Issue> issues, boolean totalOnly) {
        Fact fact = facts.get(meter.pointId()); if (fact == null) return;
        List<Meter> children = totalOnly ? List.of() : meters.values().stream().filter(m -> meter.pointId().equals(m.parentId())).toList();
        BigDecimal childSum = BigDecimal.ZERO; List<String> evidence = new ArrayList<>(evidence(fact));
        boolean complete = fact.complete();
        for (Meter child : children) {
            Fact childFact = facts.get(child.pointId());
            if (childFact == null) { complete = false; issues.add(issue("INCOMPLETE", child.pointId(), "下级计量缺数，差额包含未知用量")); }
            else { childSum = childSum.add(childFact.quantity()); evidence.addAll(evidence(childFact)); }
        }
        BigDecimal remainder = fact.quantity().subtract(childSum);
        if (remainder.signum() < 0) {
            balances.add(new Balance(meter.boundaryId(), meter.pointId(), fact.quantity(), childSum, remainder, "NEGATIVE_RESIDUAL"));
            issues.add(issue("NEGATIVE_RESIDUAL", meter.pointId(), "下级合计超过上级，停止该范围分类分解"));
            atoms.add(new Atom(meter.pointId(), signed(fact.quantity(), meter), ancestorSet(meter, meters), true, evidence(fact)));
            return;
        }
        balances.add(new Balance(meter.boundaryId(), meter.pointId(), fact.quantity(), children.isEmpty() ? null : childSum,
                children.isEmpty() ? null : remainder, complete ? "CALCULABLE" : "INCOMPLETE"));
        if (!complete) issues.add(issue("INCOMPLETE", meter.pointId(), "计量期间数据不完整"));
        if (children.isEmpty() || remainder.signum() != 0) atoms.add(new Atom(meter.pointId(), signed(remainder, meter),
                ancestorSet(meter, meters), !children.isEmpty(), List.copyOf(evidence)));
        for (Meter child : children) split(child, meters, facts, atoms, balances, issues, false);
    }

    private Set<String> assigned(Configuration config, String scheme, Atom atom, Map<String, Set<String>> resolved, Map<String, Node> nodes) {
        Set<String> targets = new LinkedHashSet<>();
        for (Binding binding : config.bindings()) if (scheme.equals(binding.schemeCode())
                && "METERING".equals(binding.semantics()) && "CONFIRMED".equals(binding.status())) {
            Set<String> points = resolved.getOrDefault(bindingKey(binding), Set.of());
            if (points.stream().anyMatch(atom.ancestors()::contains)) targets.add(binding.nodeCode());
        }
        // 同一分类路径上父节点绑定与子节点绑定只贡献到最具体节点，再逐级回卷。
        Set<String> result = new LinkedHashSet<>(targets);
        for (String a : targets) for (String b : targets) if (!a.equals(b) && descendant(b, a, scheme, nodes)) result.remove(a);
        return result;
    }

    public static String bindingKey(Binding b) { return key(b.schemeCode(), b.nodeCode())+"/"+b.objectType()+"/"+b.objectId()+"/"+b.meterPointId(); }
    private static boolean descendant(String child, String parent, String scheme, Map<String, Node> nodes) {
        Node current = nodes.get(key(scheme, child));
        while (current != null) { if (current.code().equals(parent)) return true; current = blank(current.parentCode()) ? null : nodes.get(key(scheme, current.parentCode())); }
        return false;
    }
    private static Set<String> ancestorSet(Meter meter, Map<String, Meter> meters) {
        Set<String> result = new LinkedHashSet<>(); Meter current = meter;
        while (current != null && result.add(current.pointId())) current = meters.get(current.parentId());
        return Set.copyOf(result);
    }
    private static BigDecimal signed(BigDecimal value, Meter meter) { return "OUTBOUND".equals(meter.direction()) ? value.negate() : value; }
    private static BigDecimal sum(List<Atom> atoms) { return atoms.stream().map(Atom::quantity).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private static List<String> evidence(Fact fact) { return Arrays.asList(fact.evidenceId().split(",")); }
    private static String key(String scheme, String node) { return scheme+"/"+node; }
    static boolean blank(String value) { return value == null || value.isBlank(); }
    static void text(String value, int max) { if (blank(value) || value.length() > max) fail("文本缺失或超过长度上限"); }
    private static void code(String value) { if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) fail("编码只允许字母数字及下划线横线，最多64位"); }
    private static void confirmation(String status, String evidence) {
        if (status == null || !Set.of("CONFIRMED", "PENDING_EXPERT").contains(status)) fail("确认状态无效");
        text(evidence, 500);
    }
    static Issue issue(String code, String reference, String message) { return new Issue("WARNING", code, reference, message); }
    static BusinessException error(int status, String message) { return new BusinessException(status, "ENERGY_CLASSIFICATION_INVALID", message); }
    static void fail(String message) { throw error(400, message); }
}
