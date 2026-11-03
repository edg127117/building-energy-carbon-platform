package com.platform.carbon;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

/** 专用建筑锁只串行化展示配置，不更新正式分类或计量数据。 */
@Repository
public class JointFlowConfigurationRepository {
    private final JdbcTemplate jdbc;
    public JointFlowConfigurationRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Row(String id,String building,int number,long revision,String status,String json,String hash,long author,Long reviewer) { }
    public void lock(String building) {
        jdbc.update("INSERT INTO biz_joint_flow_selection(building_id,revision) VALUES (?,0) ON DUPLICATE KEY UPDATE revision=revision",building);
        jdbc.queryForObject("SELECT revision FROM biz_joint_flow_selection WHERE building_id=? FOR UPDATE",Long.class,building);
    }
    public List<Row> list(String building) {
        return jdbc.query("SELECT * FROM biz_joint_flow_configuration WHERE building_id=? ORDER BY version_no DESC LIMIT 501",(r,n)->new Row(r.getString("version_id"),r.getString("building_id"),r.getInt("version_no"),r.getLong("revision"),r.getString("status"),r.getString("configuration_json"),r.getString("configuration_hash"),r.getLong("created_by"),(Long)r.getObject("reviewed_by")),building);
    }
    public Row get(String building,String id) {
        return jdbc.query("SELECT * FROM biz_joint_flow_configuration WHERE building_id=? AND version_id=?",(r,n)->new Row(r.getString("version_id"),r.getString("building_id"),r.getInt("version_no"),r.getLong("revision"),r.getString("status"),r.getString("configuration_json"),r.getString("configuration_hash"),r.getLong("created_by"),(Long)r.getObject("reviewed_by")),building,id).stream().findFirst().orElse(null);
    }
    public void insert(String building,String id,String json,String hash,String reason,long user) {
        Integer number=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM biz_joint_flow_configuration WHERE building_id=?",Integer.class,building);
        jdbc.update("INSERT INTO biz_joint_flow_configuration(version_id,building_id,version_no,revision,status,configuration_json,configuration_hash,reason,created_by) VALUES (?,?,?,0,'DRAFT',?,?,?,?)",id,building,number,json,hash,reason,user);
    }
    public int save(Row row,long revision,String json,String hash,String reason) {
        return jdbc.update("UPDATE biz_joint_flow_configuration SET configuration_json=?,configuration_hash=?,reason=?,revision=revision+1 WHERE building_id=? AND version_id=? AND revision=? AND status='DRAFT'",json,hash,reason,row.building(),row.id(),revision);
    }
    public int transition(Row row,long revision,String from,String to,Long reviewer,String reason) {
        return jdbc.update("UPDATE biz_joint_flow_configuration SET status=?,reviewed_by=?,reason=?,revision=revision+1 WHERE building_id=? AND version_id=? AND revision=? AND status=?",to,reviewer,reason,row.building(),row.id(),revision,from);
    }
    public JointFlowConfigurationContracts.Selection selection(String building) {
        return jdbc.query("SELECT version_id,revision FROM biz_joint_flow_selection WHERE building_id=?",(r,n)->new JointFlowConfigurationContracts.Selection(building,r.getString("version_id"),r.getLong("revision")),building).stream().findFirst().orElse(new JointFlowConfigurationContracts.Selection(building,null,0));
    }
    public int select(String building,long revision,String version) {
        return jdbc.update("UPDATE biz_joint_flow_selection SET version_id=?,revision=revision+1 WHERE building_id=? AND revision=?",version,building,revision);
    }
}
