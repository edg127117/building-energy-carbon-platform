package com.platform.carbon;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** MySQL 保存独立年度版本；范围行锁与 CAS 共同保护跨实例修订和替代。 */
@Repository
class CarbonPlanRepository {
    private final JdbcTemplate jdbc;
    CarbonPlanRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Row(String id, String building, int year, int number, long revision, String status,
            String json, String copiedFrom, long author, Long reviewer, Long activator, Long activatedAt) { }
    boolean buildingExists(String building) {
        return Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM building WHERE building_id=? AND del_flag=0",Long.class,building));
    }
    void lock(String building, int year) {
        jdbc.update("INSERT INTO biz_carbon_plan_scope(building_id,plan_year,revision) VALUES (?,?,0) ON DUPLICATE KEY UPDATE revision=revision",building,year);
        jdbc.queryForObject("SELECT revision FROM biz_carbon_plan_scope WHERE building_id=? AND plan_year=? FOR UPDATE",Long.class,building,year);
    }
    List<Row> versions(String building,int year) {
        return jdbc.query("SELECT * FROM biz_carbon_plan_version WHERE building_id=? AND plan_year=? ORDER BY version_no DESC LIMIT 101",this::row,building,year);
    }
    /** 写事务可能已建立旧快照；范围锁之后须用当前读检查候选，不能重复读取旧快照。 */
    List<Row> versionsForUpdate(String building,int year) {
        return jdbc.query("SELECT * FROM biz_carbon_plan_version WHERE building_id=? AND plan_year=? ORDER BY version_no DESC LIMIT 101 FOR UPDATE",this::row,building,year);
    }
    Row find(String building,String id) {
        var values=jdbc.query("SELECT * FROM biz_carbon_plan_version WHERE building_id=? AND version_id=?",this::row,building,id);
        return values.isEmpty()?null:values.getFirst();
    }
    Row findForUpdate(String building,String id) {
        var values=jdbc.query("SELECT * FROM biz_carbon_plan_version WHERE building_id=? AND version_id=? FOR UPDATE",this::row,building,id);
        return values.isEmpty()?null:values.getFirst();
    }
    Row active(String building,int year) {
        var rows=jdbc.query("SELECT * FROM biz_carbon_plan_version WHERE building_id=? AND plan_year=? AND status='ACTIVE'",this::row,building,year);
        if(rows.size()>1) throw CarbonErrors.error(409,CarbonErrors.VERSION_CONFLICT,"年度计划有效版本冲突");
        return rows.isEmpty()?null:rows.getFirst();
    }
    void insert(String id,String building,int year,String json,String copiedFrom,long user,String reason) {
        var latest=jdbc.queryForList("SELECT version_no FROM biz_carbon_plan_version WHERE building_id=? AND plan_year=? ORDER BY version_no DESC LIMIT 1 FOR UPDATE",Integer.class,building,year);
        int number=latest.isEmpty()?1:latest.getFirst()+1;
        jdbc.update("INSERT INTO biz_carbon_plan_version(version_id,building_id,plan_year,version_no,status,configuration_json,copied_from_version_id,reason,created_by,created_at) VALUES (?,?,?,?,'DRAFT',?,?,?,?,?)",id,building,year,number,json,copiedFrom,reason,user,Instant.now().toEpochMilli());
    }
    int save(Row row,long revision,String json,String reason) {
        return jdbc.update("UPDATE biz_carbon_plan_version SET configuration_json=?,reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='DRAFT'",json,reason,row.id(),revision);
    }
    int transition(Row row,long revision,String from,String to,Long reviewer,String reason) {
        return jdbc.update("UPDATE biz_carbon_plan_version SET status=?,reviewed_by=?,reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status=?",to,reviewer,reason,row.id(),revision,from);
    }
    int activate(Row row,long revision,long user,String reason) {
        int changed=jdbc.update("UPDATE biz_carbon_plan_version SET status='ACTIVE',activated_by=?,activated_at=?,reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='APPROVED'",user,Instant.now().toEpochMilli(),reason,row.id(),revision);
        if(changed==1) jdbc.update("UPDATE biz_carbon_plan_version SET status='RETIRED',revision=revision+1 WHERE building_id=? AND plan_year=? AND status='ACTIVE' AND version_id<>?",row.building(),row.year(),row.id());
        return changed;
    }
    private Row row(ResultSet r,int index) throws SQLException {
        return new Row(r.getString("version_id"),r.getString("building_id"),r.getInt("plan_year"),r.getInt("version_no"),r.getLong("revision"),r.getString("status"),r.getString("configuration_json"),r.getString("copied_from_version_id"),r.getLong("created_by"),(Long)r.getObject("reviewed_by"),(Long)r.getObject("activated_by"),(Long)r.getObject("activated_at"));
    }
}
