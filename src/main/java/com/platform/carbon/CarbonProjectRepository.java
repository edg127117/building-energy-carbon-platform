package com.platform.carbon;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

/** 建筑级写锁和修订号保护独立项目版本，不修改历史核算结果。 */
@Repository
class CarbonProjectRepository {
    private final JdbcTemplate jdbc;
    CarbonProjectRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Row(String id,String building,int number,long revision,String status,Long from,Long to,
               String json,long author,Long reviewer) { }
    void lock(String building) {
        jdbc.update("INSERT INTO biz_carbon_project_scope(building_id,revision) VALUES (?,0) ON DUPLICATE KEY UPDATE revision=revision",building);
        jdbc.queryForObject("SELECT revision FROM biz_carbon_project_scope WHERE building_id=? FOR UPDATE",Long.class,building);
    }
    boolean buildingExists(String building) {
        return Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM building WHERE building_id=? AND del_flag=0",Long.class,building));
    }
    List<Row> versions(String building) {
        return jdbc.query("SELECT * FROM biz_carbon_project_version WHERE building_id=? ORDER BY version_no DESC LIMIT 101",(r,n)->new Row(
                r.getString("version_id"),r.getString("building_id"),r.getInt("version_no"),r.getLong("revision"),r.getString("status"),
                (Long)r.getObject("effective_from"),(Long)r.getObject("effective_to"),r.getString("configuration_json"),r.getLong("created_by"),(Long)r.getObject("reviewed_by")),building);
    }
    void insert(String id,String building,String json,String reason,long author) {
        Integer no=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM biz_carbon_project_version WHERE building_id=?",Integer.class,building);
        jdbc.update("INSERT INTO biz_carbon_project_version(version_id,building_id,version_no,status,configuration_json,reason,created_by,created_at) VALUES (?,?,?,'DRAFT',?,?,?,?)",
                id,building,no,json,reason,author,Instant.now().toEpochMilli());
    }
    int save(Row row,long revision,String json,String reason) {
        return jdbc.update("UPDATE biz_carbon_project_version SET configuration_json=?,reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='DRAFT'",json,reason,row.id(),revision);
    }
    int transition(Row row,long revision,String from,String to,String reason,Long reviewer) {
        return jdbc.update("UPDATE biz_carbon_project_version SET status=?,reason=?,reviewed_by=?,revision=revision+1 WHERE version_id=? AND revision=? AND status=?",to,reason,reviewer,row.id(),revision,from);
    }
    int activate(Row row,long revision,Instant from,long user,String reason) {
        int changed=jdbc.update("UPDATE biz_carbon_project_version SET status='ACTIVE',effective_from=?,activated_by=?,reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='APPROVED'",
                from.toEpochMilli(),user,reason,row.id(),revision);
        if(changed==1) jdbc.update("UPDATE biz_carbon_project_version SET status='RETIRED',effective_to=? WHERE building_id=? AND status='ACTIVE' AND version_id<>?",from.toEpochMilli(),row.building(),row.id());
        return changed;
    }
}
