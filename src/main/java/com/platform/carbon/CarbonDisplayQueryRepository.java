package com.platform.carbon;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** 每个完整查询独占一行；条件发布和租约令牌防止多实例重复生成及旧来源覆盖新结果。 */
@Repository
class CarbonDisplayQueryRepository {
    private final JdbcTemplate jdbc;
    private final CarbonDisplayQueryProperties properties;
    CarbonDisplayQueryRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,CarbonDisplayQueryProperties properties){this.jdbc=jdbc;this.properties=properties;}
    record Head(String key,String building,String kind,String query,long user,String source,String built,String state,String hash,
                String token,long leaseUntil,long nextCheck,String error,Integer errorStatus) { }
    private static final String COLUMNS="query_key,building_id,query_kind,query_json,requested_by,source_version,built_version,state,result_hash,lease_token,lease_until,next_check,last_error,error_status";
    private Head row(ResultSet r,int n)throws SQLException{return new Head(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getLong(11),r.getLong(12),r.getString(13),(Integer)r.getObject(14));}
    Head head(String key){return jdbc.query("SELECT "+COLUMNS+" FROM biz_carbon_display_read_model WHERE query_key=?",this::row,key).stream().findFirst().orElse(null);}
    String payload(Head h){return jdbc.queryForObject("SELECT result_json FROM biz_carbon_display_read_model WHERE query_key=? AND built_version=?",String.class,h.key(),h.built());}
    void request(CarbonDisplayQueryContracts.Query q,String query,long user,String version,long now){
        jdbc.update("""
                INSERT INTO biz_carbon_display_read_model(query_key,building_id,query_kind,query_json,requested_by,source_version,state,next_check,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',?,?) ON DUPLICATE KEY UPDATE requested_by=VALUES(requested_by)
                """,q.key(),q.buildingId(),q.kind(),query,user,version,now,now);
        jdbc.update("""
                UPDATE biz_carbon_display_read_model SET source_version=?,state='PENDING',lease_token=NULL,lease_until=0,last_error=NULL,error_status=NULL,next_check=?,updated_at=?
                WHERE query_key=? AND (source_version<>? OR (state='FAILED' AND error_status=403))
                """,version,now,now,q.key(),version);
    }
    List<Head> due(long now){return jdbc.query("SELECT "+COLUMNS+" FROM biz_carbon_display_read_model WHERE next_check<=? AND (state<>'RUNNING' OR lease_until<?) ORDER BY next_check,query_key LIMIT 10",this::row,now,now);}
    String claim(Head h,long now){
        String token=UUID.randomUUID().toString().replace("-","");
        int changed=jdbc.update("UPDATE biz_carbon_display_read_model SET state='RUNNING',lease_token=?,lease_until=?,updated_at=? WHERE query_key=? AND source_version=? AND (state<>'RUNNING' OR lease_until<?)",token,now+properties.getLease().toMillis(),now,h.key(),h.source(),now);
        return changed==1?token:null;
    }
    boolean publish(Head h,String token,String result,String hash,long now){return jdbc.update("""
            UPDATE biz_carbon_display_read_model SET state='READY',built_version=?,result_json=?,result_hash=?,lease_token=NULL,lease_until=0,last_error=NULL,error_status=NULL,next_check=?,updated_at=?
            WHERE query_key=? AND source_version=? AND lease_token=? AND state='RUNNING' AND lease_until>=?
            """,h.source(),result,hash,now+properties.getRecheckInterval().toMillis(),now,h.key(),h.source(),token,now)==1;}
    void checked(Head h,long now){jdbc.update("UPDATE biz_carbon_display_read_model SET next_check=? WHERE query_key=? AND source_version=? AND state='READY'",now+properties.getRecheckInterval().toMillis(),h.key(),h.source());}
    void failed(Head h,String token,String code,int status,long now){jdbc.update("UPDATE biz_carbon_display_read_model SET state='FAILED',last_error=?,error_status=?,lease_token=NULL,lease_until=0,next_check=?,updated_at=? WHERE query_key=? AND source_version=? AND lease_token=?",code,status,now+properties.getRecheckInterval().toMillis(),now,h.key(),h.source(),token);}
    void defer(Head h,long now){jdbc.update("UPDATE biz_carbon_display_read_model SET next_check=? WHERE query_key=? AND source_version=? AND (state<>'RUNNING' OR lease_until<?)",now+properties.getRecheckInterval().toMillis(),h.key(),h.source(),now);}
}
