package com.platform.carbon;

import java.time.*;
import java.util.*;

/** 一次读取配置头，按实际核算区间选取相交版本；未来换版不改变已结束区间的指纹。 */
record MetadataSnapshot(List<List<Map<String,Object>>> groups) {
    static MetadataSnapshot catalog(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new MetadataSnapshot(List.of(
                catalogRows(jdbc,"SELECT i.item_code,v.version_id,v.config_revision,v.status,v.effective_from,v.effective_to FROM biz_energy_item i JOIN biz_energy_item_version v ON v.item_id=i.item_id WHERE v.status='APPROVED' ORDER BY v.version_id"),
                catalogRows(jdbc,"SELECT s.version_id,s.usage_scope,v.effective_from,v.effective_to FROM biz_energy_item_version_scope s JOIN biz_energy_item_version v ON v.version_id=s.version_id WHERE v.status='APPROVED' ORDER BY s.version_id,s.usage_scope"),
                catalogRows(jdbc,"SELECT u.unit_code,v.version_id,v.config_revision,v.status,v.effective_from,v.effective_to,v.symbol,v.dimension_code,v.canonical_unit_code,v.scale_factor,v.conversion_type,v.standard_condition_code FROM biz_measurement_unit u JOIN biz_measurement_unit_version v ON v.unit_id=u.unit_id WHERE v.status='APPROVED' ORDER BY v.version_id")));
    }
    private static List<Map<String,Object>> catalogRows(org.springframework.jdbc.core.JdbcTemplate jdbc,String sql) {
        var rows=jdbc.queryForList(sql+" LIMIT 10001");
        if(rows.size()>10000)throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY","动态输入配置版本超过读取上限");
        return rows;
    }
    String version(Instant from,Instant to) {
        var scoped=new ArrayList<List<Map<String,Object>>>();
        for(var group:groups) {
            var rows=new ArrayList<Map<String,Object>>();
            for(var original:group) {
                var row=new TreeMap<String,Object>(String.CASE_INSENSITIVE_ORDER);row.putAll(original);
                String startKey=row.containsKey("effective_from")?"effective_from":row.containsKey("effective_at")?"effective_at":row.containsKey("start_epoch_ms")?"start_epoch_ms":null;
                String endKey=row.containsKey("effective_to")?"effective_to":row.containsKey("superseded_at")?"superseded_at":row.containsKey("end_epoch_ms")?"end_epoch_ms":null;
                if(startKey!=null) {
                    Instant start=instant(row.get(startKey)),end=endKey==null?null:instant(row.get(endKey));
                    if(start!=null&&!start.isBefore(to)||end!=null&&!end.isAfter(from))continue;
                    // 版本在区间外延长/结束不改变区间内含义；版本身份与修订仍参与摘要。
                    row.put(startKey,start==null||start.isBefore(from)?from:start);
                    if(endKey!=null)row.put(endKey,end==null||end.isAfter(to)?to:end);
                }
                rows.add(row);
            }
            scoped.add(rows);
        }
        return DynamicElectricityRepository.hash(DynamicElectricityRepository.encode(scoped));
    }
    private static Instant instant(Object value) {
        if(value==null)return null;
        if(value instanceof Number n)return Instant.ofEpochMilli(n.longValue());
        if(value instanceof Instant i)return i;
        if(value instanceof java.sql.Timestamp t)return t.toLocalDateTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        if(value instanceof LocalDateTime t)return t.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        throw new IllegalArgumentException("Unsupported metadata boundary: "+value.getClass().getSimpleName());
    }
}
