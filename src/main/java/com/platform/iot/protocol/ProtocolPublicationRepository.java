package com.platform.iot.protocol;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/** 发布序号在目标行锁内分配；配置内容只插入，加载回执只修改状态列。 */
@Repository
public class ProtocolPublicationRepository {
    private final JdbcTemplate jdbc;
    public ProtocolPublicationRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Target(String id,String name,String output,String topics,String keyHash,long seen,long sequence) {}
    public record Version(String id,String draftId,long revision,String json,String entryJson,String productHash,String digest,long created) {}
    public record Deployment(String target,long sequence,String digest,String json,String versions,String approval,
            String status,String error,long created,long loaded) {}
    private static final RowMapper<Target> TARGET=(r,n)->new Target(r.getString("target_id"),r.getString("target_name"),
            r.getString("output_version"),r.getString("allowed_topics_json"),r.getString("key_sha256"),r.getLong("last_seen"),r.getLong("current_sequence"));
    private static final RowMapper<Version> VERSION=(r,n)->new Version(r.getString("version_id"),r.getString("draft_id"),
            r.getLong("draft_revision"),r.getString("configuration_json"),r.getString("entry_json"),r.getString("product_sha256"),r.getString("content_sha256"),r.getLong("created_at"));
    private static final RowMapper<Deployment> DEPLOYMENT=(r,n)->new Deployment(r.getString("target_id"),r.getLong("sequence_no"),
            r.getString("content_sha256"),r.getString("content_json"),r.getString("version_ids_json"),r.getString("approval_id"),
            r.getString("load_status"),r.getString("error_code"),r.getLong("created_at"),r.getLong("loaded_at"));
    public void insertTarget(Target t) {jdbc.update("INSERT INTO biz_protocol_target(target_id,target_name,output_version,allowed_topics_json,key_sha256) VALUES(?,?,?,?,?)",t.id(),t.name(),t.output(),t.topics(),t.keyHash());}
    public List<Target> targets() {return jdbc.query("SELECT * FROM biz_protocol_target ORDER BY target_id LIMIT 100",TARGET);}
    public Optional<Target> target(String id,boolean lock) {return jdbc.query("SELECT * FROM biz_protocol_target WHERE target_id=?"+(lock?" FOR UPDATE":""),TARGET,id).stream().findFirst();}
    public void seen(String id,long now) {jdbc.update("UPDATE biz_protocol_target SET last_seen=? WHERE target_id=?",now,id);}
    public void advance(String id,long sequence) {jdbc.update("UPDATE biz_protocol_target SET current_sequence=? WHERE target_id=?",sequence,id);}
    public void insertVersion(Version v) {jdbc.update("INSERT INTO biz_protocol_version VALUES(?,?,?,?,?,?,?,?)",v.id(),v.draftId(),v.revision(),v.json(),v.entryJson(),v.productHash(),v.digest(),v.created());}
    public void archive(String id,String json,String digest,long actor) {jdbc.update("INSERT INTO biz_protocol_migration_archive VALUES(?,?,?,?,?)",id,json,digest,System.currentTimeMillis(),actor);}
    public Optional<Version> version(String id) {return jdbc.query("SELECT * FROM biz_protocol_version WHERE version_id=?",VERSION,id).stream().findFirst();}
    public Optional<Version> frozen(String draft,long revision) {return jdbc.query("SELECT * FROM biz_protocol_version WHERE draft_id=? AND draft_revision=?",VERSION,draft,revision).stream().findFirst();}
    public List<Version> versions() {return jdbc.query("SELECT * FROM biz_protocol_version ORDER BY created_at DESC,version_id LIMIT 100",VERSION);}
    public void insertDeployment(Deployment d) {jdbc.update("INSERT INTO biz_protocol_deployment VALUES(?,?,?,?,?,?,?,?,?,?)",d.target(),d.sequence(),d.digest(),d.json(),d.versions(),d.approval(),d.status(),d.error(),d.created(),d.loaded());}
    public Optional<Deployment> deployment(String target,long sequence) {return jdbc.query("SELECT * FROM biz_protocol_deployment WHERE target_id=? AND sequence_no=?",DEPLOYMENT,target,sequence).stream().findFirst();}
    public List<Deployment> deployments(String target) {return jdbc.query("SELECT * FROM biz_protocol_deployment WHERE target_id=? ORDER BY sequence_no DESC LIMIT 100",DEPLOYMENT,target);}
    public void receipt(String target,long sequence,String status,String error,long now) {jdbc.update("UPDATE biz_protocol_deployment SET load_status=?,error_code=?,loaded_at=? WHERE target_id=? AND sequence_no=?",status,error,now,target,sequence);}
    /** 发布事务锁定产品，使用数据库当前契约避免其他Mapper会话缓存隐藏已批准产品的变更。 */
    public List<?> productContract(String productId) {
        var product=jdbc.queryForList("SELECT product_id,expected_profile_code,identity_type,equipment_type_code,status FROM biz_device_product WHERE product_id=? FOR UPDATE",productId);
        if(product.size()!=1||!"ENABLED".equals(product.getFirst().get("status"))) throw ProtocolErrors.invalid("发布协议必须关联已批准启用的产品");
        var points=jdbc.queryForList("SELECT metric_code,unit,required_flag,status FROM biz_product_point_template WHERE product_id=? ORDER BY template_point_id",productId);
        return List.of(product,points);
    }
    public void validateBindings(String productId,String profileCode,List<String> metrics,String sourceSystem) {
        var identities=jdbc.queryForList("SELECT i.identity_type,i.identity_value,i.building_id,i.expected_profile_code FROM biz_device_identity i JOIN biz_equipment e ON e.equip_id=i.equip_id WHERE e.product_id=? AND i.status=1",productId);
        for(var identity:identities) {
            if(!profileCode.equals(identity.get("expected_profile_code"))) throw ProtocolErrors.invalid("已绑定设备的协议标识不兼容");
            String prefix=identity.get("identity_type")+":"+identity.get("identity_value")+":";
            for(String metric:metrics) {
                Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM biz_point_alias WHERE building_id=? AND source_system=? AND source_point_code=? AND status=1",Integer.class,identity.get("building_id"),sourceSystem,prefix+metric);
                if(count==null||count!=1) throw ProtocolErrors.invalid("已绑定设备缺少对应指标别名，请先完成接入配置");
            }
        }
    }
}
