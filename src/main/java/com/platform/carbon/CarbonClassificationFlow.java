package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.*;

/** 从冻结的互斥分类来源构建守恒流图；交叉分类不生成会重复计量的流线。 */
final class CarbonClassificationFlow {
    private CarbonClassificationFlow() { }
    public record Node(String id, String type, String code, String name) { }
    public record Link(String source, String target,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal value) { }
    public record Graph(String schemeCode, boolean available, String unavailableReason,
                        List<Node> nodes, List<Link> links) { }
    private record Edge(String source, String target) { }

    static List<Graph> build(List<CarbonClassificationReportService.Entry> entries) {
        Map<String,Builder> builders=new LinkedHashMap<>();
        for(var entry:entries) {
            JsonNode upstream=entry.evidence().path("upstream");
            JsonNode config=upstream.path("classificationConfiguration");
            BigDecimal amount=entry.evidence().has("rawKgCO2e")
                    ?new BigDecimal(entry.evidence().path("rawKgCO2e").asText()):entry.item().emissionKgCo2e();
            for(JsonNode scheme:config.path("schemes")) {
                String code=scheme.path("code").asText();
                Builder builder=builders.computeIfAbsent(code,k->new Builder(code));
                if(!"ADDITIVE".equals(scheme.path("mode").asText())) {
                    builder.unavailable="交叉分类不能相加形成守恒流图，请查看去重明细";
                    continue;
                }
                if(builder.unavailable!=null)continue;
                Map<String,JsonNode> index=new HashMap<>();
                config.path("nodes").forEach(n->{if(code.equals(n.path("schemeCode").asText()))index.put(n.path("code").asText(),n);});
                List<String> path=new ArrayList<>();
                path.add(builder.node("TOTAL","TOTAL","TOTAL"));
                String scope=entry.item().scopeType().name(),energy=entry.item().energyItemCode();
                String context=upstream.path("projectVersion").path("configuration").path("classificationVersionId").asText("");
                path.add(builder.node("SCOPE",scope,scope));
                path.add(builder.node("ENERGY",scope+"/"+energy,energy));
                Set<String> targets=new LinkedHashSet<>();
                upstream.path("sourceSlice").path("classifications").path(code).forEach(n->targets.add(n.asText()));
                if(targets.size()!=1)path.add(builder.node("UNALLOCATED",scope+"/"+energy+"/"+context+"/UNALLOCATED","UNALLOCATED"));
                else {
                    String cursor=targets.iterator().next();Set<String> seen=new HashSet<>();List<String> reversed=new ArrayList<>();
                    while(!cursor.isBlank()) {
                        if(!seen.add(cursor)||!index.containsKey(cursor)||seen.size()>64) {
                            builder.unavailable="冻结分类路径无效，不能生成流图";break;
                        }
                        JsonNode node=index.get(cursor);
                        // 各月树结构可能改变，按冻结版本隔离节点，避免历史路径合并成环。
                        reversed.add(builder.node("CLASSIFICATION",scope+"/"+energy+"/"+context+"/"+cursor,node.path("name").asText(cursor)));
                        cursor=node.path("parentCode").asText("");
                    }
                    Collections.reverse(reversed);path.addAll(reversed);
                }
                if(builder.unavailable==null)for(int i=1;i<path.size();i++)
                    builder.links.merge(new Edge(path.get(i-1),path.get(i)),amount,BigDecimal::add);
                if(builder.nodes.size()>500||builder.links.size()>2000)builder.unavailable="分类流图超过500节点或2000连线上限，请缩小范围或查看明细";
            }
        }
        // 同批次可能混有旧明细或不同方案；部分覆盖不能以“总量”起点画出伪守恒全楼流图。
        for (Builder builder : builders.values()) {
            for (var entry : entries) {
                var upstream = entry.evidence().path("upstream");
                if ("EXCLUDED".equals(upstream.path("activityStatus").asText())) continue;
                boolean covered = false;
                for (JsonNode scheme : upstream.path("classificationConfiguration").path("schemes")) {
                    if (builder.code.equals(scheme.path("code").asText())) { covered = true; break; }
                }
                if (!covered) {
                    builder.unavailable = "部分明细缺少该方案的冻结分类证据，不能把分类小计画成建筑总量";
                    break;
                }
            }
        }
        return builders.values().stream().map(Builder::view).toList();
    }

    private static final class Builder {
        final String code;String unavailable;
        final Map<String,Node> nodes=new LinkedHashMap<>();
        final Map<Edge,BigDecimal> links=new LinkedHashMap<>();
        Builder(String code){this.code=code;}
        String node(String type,String code,String name) {
            String id=type+":"+code;
            nodes.putIfAbsent(id,new Node(id,type,code,name));return id;
        }
        Graph view() {
            return unavailable==null?new Graph(code,true,null,List.copyOf(nodes.values()),
                    links.entrySet().stream().map(e->new Link(e.getKey().source(),e.getKey().target(),e.getValue())).toList())
                    :new Graph(code,false,unavailable,List.of(),List.of());
        }
    }
}
