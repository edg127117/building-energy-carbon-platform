package com.platform.relation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.hvac.model.entity.BizPointNamingRule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.platform.relation.RelationErrors.*;
import static com.platform.relation.RelationStagingContracts.*;

/** 暂存身份只对所属版本可见；发布与原关系投影使用同一 MySQL 事务。 */
@Repository
public class RelationStagingRepository {
    private final JdbcTemplate jdbc;
    private static final ObjectMapper JSON = new ObjectMapper();
    public RelationStagingRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Row(String id,String version,String building,String type,String object,String node,
                      String code,String name,String definition,String hash,String status,
                      String space,String system,String equipment) { }
    public List<Row> list(String version) {
        return jdbc.query("SELECT * FROM biz_relation_staged_asset WHERE version_id=? AND status<>'CANCELLED' ORDER BY object_type,object_code",(r,n)->new Row(
                r.getString("staged_id"),r.getString("version_id"),r.getString("building_id"),r.getString("object_type"),
                r.getString("object_id"),r.getString("node_id"),r.getString("object_code"),r.getString("object_name"),
                r.getString("definition_json"),r.getString("definition_sha256"),r.getString("status"),
                r.getString("space_id"),r.getString("system_group_id"),r.getString("equipment_id")),version);
    }
    public boolean exists(String version,String building,String type,String object) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM biz_relation_staged_asset WHERE version_id=? AND building_id=? AND object_type=? AND object_id=? AND status='STAGED'",Long.class,version,building,type,object)>0;
    }
    public int unpublishedCount(String version,String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM biz_relation_staged_asset WHERE version_id=? AND object_type=? AND status='STAGED'",Integer.class,version,type);
    }
    public Map<String,Object> type(String code) {
        return one("SELECT type_code,equip_category,asset_code_prefix,status FROM biz_equipment_type WHERE type_code=? FOR UPDATE",code);
    }
    public Map<String,Object> product(String id) {
        return one("SELECT product_id,equipment_type_code,status FROM biz_device_product WHERE product_id=? FOR UPDATE",id);
    }
    public Map<String,Object> rule(String id) {
        return one("SELECT rule_id,standard_version,family_code,component_code,code_template,standard_source,status FROM biz_point_naming_rule WHERE rule_id=? FOR UPDATE",id);
    }
    private Map<String,Object> one(String sql,String id) {
        var rows=jdbc.queryForList(sql,id);
        if(rows.size()!=1)throw error(409,REFERENCE_CONFLICT,"档案引用的类型、产品或命名规则不存在");
        return rows.getFirst();
    }
    public Options options() {
        return new Options(jdbc.query("SELECT type_code,type_name,asset_code_prefix FROM biz_equipment_type WHERE status=1 ORDER BY type_code",(r,n)->new Option(r.getString(1),r.getString(2),r.getString(3))),
                jdbc.query("SELECT product_id,product_name,equipment_type_code FROM biz_device_product WHERE status='ENABLED' ORDER BY product_name,product_id",(r,n)->new Option(r.getString(1),r.getString(2),r.getString(3))),
                jdbc.query("SELECT rule_id,code_template,family_code FROM biz_point_naming_rule WHERE status=1 ORDER BY family_code,component_code,rule_id",(r,n)->new Option(r.getString(1),r.getString(2),r.getString(3))));
    }
    public List<String> codes(String building,String type) {
        String table=type.equals("EQUIPMENT")?"biz_equipment":"biz_data_point";
        String column=type.equals("EQUIPMENT")?"equip_code":"point_code";
        List<String> result=new ArrayList<>(jdbc.queryForList("SELECT "+column+" FROM "+table+" WHERE building_id=?",String.class,building));
        result.addAll(jdbc.queryForList("SELECT object_code FROM biz_relation_staged_asset WHERE building_id=? AND object_type=?",String.class,building,type));
        return result;
    }
    public void insert(String id,String version,String building,String type,String object,String node,String code,
                       String name,String definition,long user,String space,String system,String equipment) {
        jdbc.update("INSERT INTO biz_relation_staged_asset(staged_id,version_id,building_id,object_type,object_id,node_id,object_code,object_name,definition_json,definition_sha256,created_by,space_id,system_group_id,equipment_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,version,building,type,object,node,code,name,definition,sha(definition),user,space,system,equipment);
        jdbc.update("INSERT INTO biz_relation_node(node_id,building_id,node_type,business_object_id,status,create_by,update_by,create_time,update_time) VALUES (?,?,?,?,'STAGED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",node,building,type,object,user,user);
    }
    public int updateAssignment(String version,String type,String object,String space,String system,String equipment) {
        return jdbc.update("UPDATE biz_relation_staged_asset SET space_id=?,system_group_id=?,equipment_id=? WHERE version_id=? AND object_type=? AND object_id=? AND status='STAGED'",space,system,equipment,version,type,object);
    }
    public List<RelationGovernanceRepository.AssignmentRow> assignments(String version) {
        return list(version).stream().filter(r->r.status().equals("STAGED"))
                .map(r->new RelationGovernanceRepository.AssignmentRow(r.type(),r.object(),r.space(),r.system(),r.equipment(),r.building(),"MANUAL")).toList();
    }
    public void remove(Row row) {
        Long references=jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM biz_semantic_relation_version_item WHERE version_id=? AND (source_node_id=? OR target_node_id=?))
                  +(SELECT COUNT(*) FROM biz_meter_structure_version_item WHERE version_id=? AND (meter_point_node_id=? OR parent_meter_point_node_id=?))
                  +(SELECT COUNT(*) FROM biz_metering_assignment_version_item WHERE version_id=? AND (meter_point_node_id=? OR target_node_id=?))
                  +(SELECT COUNT(*) FROM biz_asset_assignment_version_item WHERE version_id=? AND (equipment_id=? OR staged_equipment_id=?))
                  +(SELECT COUNT(*) FROM biz_relation_staged_asset WHERE version_id=? AND equipment_id=? AND status='STAGED')
                """,Long.class,row.version(),row.node(),row.node(),row.version(),row.node(),row.node(),row.version(),row.node(),row.node(),row.version(),row.object(),row.object(),row.version(),row.object());
        if(references>0)throw error(409,REFERENCE_CONFLICT,"暂存档案仍被测点、表计或覆盖关系引用，请先移除相关引用");
        jdbc.update("DELETE FROM biz_asset_assignment_version_item WHERE version_id=? AND object_type=? AND object_id=?",row.version(),row.type(),row.object());
        jdbc.update("UPDATE biz_relation_staged_asset SET status='CANCELLED' WHERE staged_id=? AND status='STAGED'",row.id());
        jdbc.update("UPDATE biz_relation_node SET status='RETIRED' WHERE node_id=? AND status='STAGED'",row.node());
    }
    public void validateDefinitions(String version) {
        for(Row row:list(version)) {
            if(!sha(row.definition()).equals(row.hash()))throw error(409,REVIEW_CONFLICT,"暂存档案摘要不一致");
            if(!row.status().equals("STAGED"))continue;
            if(row.type().equals("EQUIPMENT")) {
                var d=decode(row.definition(),EquipmentDefinition.class);
                if(!fingerprint(type(d.typeCode())).equals(d.typeFingerprint()))throw error(409,REVIEW_CONFLICT,"设备类型已变化，须重新建立并审核档案");
                if(d.productId()!=null&&!fingerprint(product(d.productId())).equals(d.productFingerprint()))throw error(409,REVIEW_CONFLICT,"产品状态已变化，须重新审核档案");
            } else {
                var d=decode(row.definition(),PointDefinition.class);
                if(!fingerprint(rule(d.namingRuleId())).equals(d.ruleFingerprint()))throw error(409,REVIEW_CONFLICT,"测点命名规则已变化，须重新审核档案");
                if(row.status().equals("STAGED")&&!d.componentCode().equals("ENV")&&(row.equipment()==null||row.system()==null))
                    throw error(409,VALIDATION_FAILED,"暂存设备测点必须确认设备与系统归属");
            }
        }
    }
    public void publish(String version) {
        validateDefinitions(version);
        List<Row> rows=list(version);
        // 设备先于其测点进入正式档案；身份、别名及南向接入授权始终留在原注册审批链。
        for(Row row:rows)if(row.status().equals("STAGED")&&row.type().equals("EQUIPMENT")) {
            var d=decode(row.definition(),EquipmentDefinition.class);
            jdbc.update("INSERT INTO biz_equipment(equip_id,equip_code,equip_name,type_code,equip_category,building_id,product_id,manufacturer,space_id,system_group_id,del_flag,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",row.object(),row.code(),row.name(),d.typeCode(),d.category(),row.building(),d.productId(),d.manufacturer(),row.space(),row.system());
        }
        for(Row row:rows)if(row.status().equals("STAGED")&&row.type().equals("POINT")) {
            var d=decode(row.definition(),PointDefinition.class);
            jdbc.update("INSERT INTO biz_data_point(point_id,point_code,point_name,building_id,naming_rule_id,family_code,component_code,suffix_code,data_type,unit,is_for_calc,status,equip_id,system_group_id,del_flag,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",row.object(),row.code(),row.name(),row.building(),d.namingRuleId(),d.familyCode(),d.componentCode(),d.suffixCode(),d.dataType(),d.unit(),d.isForCalc(),d.status(),row.equipment(),row.system());
        }
        jdbc.update("INSERT INTO biz_asset_assignment_version_item(version_id,building_id,object_type,object_id,space_id,system_group_id,equipment_id,source_type) SELECT version_id,building_id,object_type,object_id,space_id,system_group_id,equipment_id,'MANUAL' FROM biz_relation_staged_asset WHERE version_id=? AND status='STAGED'",version);
        jdbc.update("UPDATE biz_asset_assignment_version_item SET equipment_id=staged_equipment_id,staged_equipment_id=NULL WHERE version_id=? AND staged_equipment_id IS NOT NULL",version);
        jdbc.update("UPDATE biz_relation_node SET status='ACTIVE' WHERE node_id IN (SELECT node_id FROM biz_relation_staged_asset WHERE version_id=? AND status='STAGED')",version);
        jdbc.update("UPDATE biz_relation_staged_asset SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP WHERE version_id=? AND status='STAGED'",version);
    }
    public static BizPointNamingRule namingRule(Map<String,Object> values) {
        var r=new BizPointNamingRule();r.setRuleId(string(values,"rule_id"));r.setFamilyCode(string(values,"family_code"));r.setComponentCode(string(values,"component_code"));r.setCodeTemplate(string(values,"code_template"));r.setStatus(((Number)values.get("status")).intValue());return r;
    }
    public static String string(Map<String,Object> values,String key) {return Objects.toString(values.get(key),null);}
    public static String fingerprint(Map<String,Object> values) {return sha(encode(new TreeMap<>(values)));}
    public static String encode(Object value) {try{return JSON.writeValueAsString(value);}catch(Exception e){throw error(409,VALIDATION_FAILED,"暂存档案无法序列化");}}
    public static <T>T decode(String value,Class<T> type) {try{return JSON.readValue(value,type);}catch(Exception e){throw error(409,VALIDATION_FAILED,"暂存档案存储无效");}}
    private static String sha(String text) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
