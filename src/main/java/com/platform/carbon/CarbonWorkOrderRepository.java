package com.platform.carbon;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
/** 按建筑、项目版本及幂等键保存工作单回执；调用方须把域操作与回执置于同一事务。 */
public class CarbonWorkOrderRepository {
    private final JdbcTemplate jdbc;
    public CarbonWorkOrderRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Stored(String hash,long author,String json) { }
    public Stored find(String building,String version,String key) {
        return jdbc.query("SELECT request_hash,created_by,receipt_json FROM biz_carbon_configuration_command WHERE building_id=? AND project_version_id=? AND idempotency_key=?",
                (r,n)->new Stored(r.getString(1),r.getLong(2),r.getString(3)),building,version,key).stream().findFirst().orElse(null);
    }
    public void insert(String id,String building,String version,String key,String hash,String fingerprint,String json,long user) {
        jdbc.update("INSERT INTO biz_carbon_configuration_command(command_id,building_id,project_version_id,idempotency_key,request_hash,guide_fingerprint,receipt_json,created_by) VALUES (?,?,?,?,?,?,?,?)",
                id,building,version,key,hash,fingerprint,json,user);
    }
}
