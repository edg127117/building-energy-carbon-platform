package com.platform.adapter.parser;

import com.platform.adapter.model.ElectricityInterval;
import com.platform.adapter.model.StandardMetric;
import com.platform.adapter.profile.ProtocolFieldMapping;
import java.math.BigDecimal;
import java.util.*;

/** 复用既有value_type字段声明周期角色，JDBC与发布快照使用同一规则，不猜测厂家字段名。 */
public final class IntervalMappings {
    public static final Set<String> TYPES=Set.of("DECIMAL","ELECTRICITY_KWH","CO2_FACTOR","INTERVAL_START","INTERVAL_END","INTERVAL_REVISION");
    private static final Set<String> META=Set.of("INTERVAL_START","INTERVAL_END","INTERVAL_REVISION");
    private IntervalMappings() { }
    public static String type(String value) {return value==null||value.isBlank()?"DECIMAL":value.toUpperCase(Locale.ROOT);}
    public static boolean interval(String value) {return !"DECIMAL".equals(type(value));}
    public static boolean metadata(String value) {return META.contains(type(value));}
    public static boolean parameter(String value) {return metadata(value)||"CO2_FACTOR".equals(type(value));}
    public static boolean hasInterval(List<ProtocolFieldMapping> mappings) {
        return mappings.stream().anyMatch(m->m.enabled()&&interval(m.valueType()));
    }
    public static void validate(List<ProtocolFieldMapping> mappings) {
        Map<String,ProtocolFieldMapping> roles=new HashMap<>();
        for(var m:mappings)if(m.enabled()) {
            String kind=type(m.valueType());
            if(!TYPES.contains(kind))throw new IllegalArgumentException("UNSUPPORTED_VALUE_TYPE");
            if(!"DECIMAL".equals(kind)&&roles.put(kind,m)!=null)throw new IllegalArgumentException("DUPLICATE_INTERVAL_ROLE");
            if(metadata(kind)&&(!m.required()||m.scale()!=null&&m.scale().compareTo(BigDecimal.ONE)!=0
                    ||m.offset()!=null&&m.offset().signum()!=0||!("INTERVAL_REVISION".equals(kind)?"1":"ms").equals(m.targetUnit())))
                throw new IllegalArgumentException("INVALID_INTERVAL_METADATA_MAPPING");
            if("ELECTRICITY_KWH".equals(kind)&&!"kWh".equals(m.targetUnit())
                    ||"CO2_FACTOR".equals(kind)&&(!"kgCO2/kWh".equals(m.targetUnit())||m.sourceUnit().toUpperCase(Locale.ROOT).contains("CO2E")))
                throw new IllegalArgumentException("INVALID_INTERVAL_UNIT_MAPPING");
        }
        if(!roles.isEmpty()&&(!roles.keySet().containsAll(Set.of("ELECTRICITY_KWH","CO2_FACTOR"))
                ||!roles.containsKey("INTERVAL_START")&&!roles.containsKey("INTERVAL_END")))
            throw new IllegalArgumentException("INCOMPLETE_INTERVAL_MAPPING");
    }
    public static ElectricityInterval extract(List<ProtocolFieldMapping> mappings,List<StandardMetric> metrics,String reference) {
        validate(mappings);if(!hasInterval(mappings))return null;
        Map<String,StandardMetric> values=new HashMap<>();metrics.forEach(m->values.put(m.code(),m));
        Map<String,ProtocolFieldMapping> roles=new HashMap<>();
        mappings.stream().filter(m->m.enabled()&&interval(m.valueType())).forEach(m->roles.put(type(m.valueType()),m));
        var q=roles.get("ELECTRICITY_KWH");
        var result=new ElectricityInterval(q.metricCode(),integer(roles,values,"INTERVAL_START"),integer(roles,values,"INTERVAL_END"),
                integer(roles,values,"INTERVAL_REVISION"),value(values,q),value(values,roles.get("CO2_FACTOR")),
                "kWh","kgCO2/kWh","SOURCE_LABELLED",reference);
        return result.boundaries(null);
    }
    private static BigDecimal value(Map<String,StandardMetric> values,ProtocolFieldMapping mapping) {
        var value=values.get(mapping.metricCode());return value==null?null:value.value();
    }
    private static long integer(Map<String,ProtocolFieldMapping> roles,Map<String,StandardMetric> values,String role) {
        if(!roles.containsKey(role))return 0;
        var number=value(values,roles.get(role));
        if(number==null)throw new IllegalArgumentException("INTERVAL_TIME_OR_REVISION_MISSING");
        return number.longValueExact();
    }
}
