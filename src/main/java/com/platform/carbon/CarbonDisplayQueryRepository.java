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
    private String periodCursor="",generalCursor="";
    CarbonDisplayQueryRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,CarbonDisplayQueryProperties properties){this.jdbc=jdbc;this.properties=properties;}
    record Head(String key,String building,String kind,String query,long user,String source,String built,String state,String hash,
                String token,long leaseUntil,long nextCheck,String error,Integer errorStatus) { }
    private static final String COLUMNS="query_key,building_id,query_kind,query_json,requested_by,source_version,built_version,state,result_hash,lease_token,lease_until,next_check,last_error,error_status";
    private Head row(ResultSet r,int n)throws SQLException{return new Head(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getLong(11),r.getLong(12),r.getString(13),(Integer)r.getObject(14));}
    Head head(String key){return jdbc.query("SELECT "+COLUMNS+" FROM biz_carbon_display_read_model WHERE query_key=?",this::row,key).stream().findFirst().orElse(null);}
    String payload(Head h){return jdbc.queryForObject("SELECT result_json FROM biz_carbon_display_read_model WHERE query_key=? AND built_version=?",String.class,h.key(),h.built());}
    String onlinePayload(Head h) {
        // 单条有界读取不占用版本快照事务；哈希和读取后来源复核由服务执行，不能拼接两代结果。
        return jdbc.query(connection->{
            var statement=connection.prepareStatement("SELECT result_json FROM biz_carbon_display_read_model WHERE query_key=? AND built_version=? AND result_hash=?");
            statement.setString(1,h.key());statement.setString(2,h.built());statement.setString(3,h.hash());statement.setQueryTimeout(3);
            return statement;
        },rs->rs.next()?rs.getString(1):null);
    }
    record Summary(String payload,String hash) { }
    Summary summary(Head h,String month){return jdbc.query("SELECT summary_json,summary_hash FROM biz_daily_trend_read_model WHERE building_id=? AND month_key=? AND source_version=? AND built_version=? AND state='READY'",(r,n)->new Summary(r.getString(1),r.getString(2)),h.building(),month,h.source(),h.built()).stream().findFirst().orElse(null);}
    void publishSummary(Head h,String month,String payload,String hash,long now){
        jdbc.update("""
                INSERT INTO biz_daily_trend_read_model(building_id,month_key,requested_by,source_version,built_version,state,summary_json,summary_hash,updated_at,next_check)
                VALUES (?,?,?,?,?,'READY',?,?,?,?) ON DUPLICATE KEY UPDATE
                requested_by=VALUES(requested_by),source_version=VALUES(source_version),built_version=VALUES(built_version),
                state='READY',summary_json=VALUES(summary_json),summary_hash=VALUES(summary_hash),updated_at=VALUES(updated_at),
                next_check=VALUES(next_check),lease_token=NULL,lease_until=0,attempts=0,last_error=NULL
                """,h.building(),month,h.user(),h.source(),h.source(),payload,hash,now,now);
    }
    boolean hasRawSources(String building,java.time.Instant from,java.time.Instant to){
        return !jdbc.queryForList("SELECT result_id FROM biz_dynamic_electricity_chart_projection WHERE building_id=? AND start_ms<? AND end_ms>? AND source_kind='RAW_CUMULATIVE' LIMIT 1",String.class,building,to.toEpochMilli(),from.toEpochMilli()).isEmpty();
    }
    void request(CarbonDisplayQueryContracts.Query q,String query,long user,String version,long now){
        request(q.key(),q.buildingId(),q.kind(),query,user,version,now);
    }
    void request(String key,String building,String kind,String query,long user,String version,long now){
        jdbc.update("""
                INSERT INTO biz_carbon_display_read_model(query_key,building_id,query_kind,query_json,requested_by,source_version,state,next_check,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',?,?) ON DUPLICATE KEY UPDATE requested_by=VALUES(requested_by)
                """,key,building,kind,query,user,version,now,now);
        jdbc.update("""
                UPDATE biz_carbon_display_read_model SET source_version=?,state='PENDING',lease_token=NULL,lease_until=0,last_error=NULL,error_status=NULL,next_check=?,updated_at=?
                WHERE query_key=? AND (source_version<>? OR (state='FAILED' AND error_status=403))
                """,version,now,now,key,version);
    }
    /** 登记任务不发布数据；仅替换观察到的旧版本，迟到请求不能撤销另一请求已登记的新版本。 */
    void requestPeriod(String key,String building,String kind,String query,long user,String version,long now,Head observed) {
        if(observed==null) {
            jdbc.update("""
                    INSERT INTO biz_carbon_display_read_model(query_key,building_id,query_kind,query_json,requested_by,source_version,state,next_check,updated_at)
                    VALUES (?,?,?,?,?,?,'PENDING',?,?) ON DUPLICATE KEY UPDATE query_key=query_key
                    """,key,building,kind,query,user,version,now,now);
            return;
        }
        jdbc.update("""
                UPDATE biz_carbon_display_read_model SET requested_by=?,source_version=?,state='PENDING',lease_token=NULL,lease_until=0,
                last_error=NULL,error_status=NULL,next_check=?,updated_at=?
                WHERE query_key=? AND source_version=? AND (source_version<>? OR (state='FAILED' AND error_status=403))
                """,user,version,now,now,key,observed.source(),version);
    }
    List<Head> due(long now){
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM biz_carbon_display_read_model WHERE query_kind IN ('JOINT_FLOW','CARBON_INTENSITY') AND next_check<=? AND (state<>'RUNNING' OR lease_until<?) AND query_key>? ORDER BY query_key LIMIT 10",this::row,now,now,generalCursor);
        if(rows.isEmpty())generalCursor="";
        return rows;
    }
    List<Head> duePeriods(long now){
        // 内部叶子由整月/整年任务核对来源并按需失效；已就绪叶子不再周期性写调度时间。
        // 仍扫描全部未完成叶子，保证中断后的租约恢复；公开期间继续主动检查并触发受影响叶子重建。
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM biz_carbon_display_read_model WHERE query_kind IN ('ELECTRICITY_YEAR','ELECTRICITY_YEAR_MONTH','ELECTRICITY_MONTH','ELECTRICITY_DAY','DAILY_TREND_DETAIL','DAILY_TREND_DAY') AND NOT (state='READY' AND query_kind IN ('ELECTRICITY_YEAR_MONTH','DAILY_TREND_DAY')) AND next_check<=? AND (state<>'RUNNING' OR lease_until<?) AND query_key>? ORDER BY query_key LIMIT 100",this::row,now,now,periodCursor);
        if(rows.isEmpty())periodCursor="";
        return rows;
    }
    void waitForInputs(Head h,String token,long now){jdbc.update("UPDATE biz_carbon_display_read_model SET state='PENDING',lease_token=NULL,lease_until=0,next_check=? WHERE query_key=? AND source_version=? AND lease_token=?",now+5000,h.key(),h.source(),token);}
    String claim(Head h,long now){
        String token=UUID.randomUUID().toString().replace("-","");
        int changed=jdbc.update("UPDATE biz_carbon_display_read_model SET state='RUNNING',lease_token=?,lease_until=?,updated_at=? WHERE query_key=? AND source_version=? AND (state<>'RUNNING' OR lease_until<?)",token,now+properties.getLease().toMillis(),now,h.key(),h.source(),now);
        return changed==1?token:null;
    }
    boolean publish(Head h,String token,String result,String hash,long now){return jdbc.update("""
            UPDATE biz_carbon_display_read_model SET state='READY',built_version=?,result_json=?,result_hash=?,lease_token=NULL,lease_until=0,last_error=NULL,error_status=NULL,next_check=?,updated_at=?
            WHERE query_key=? AND source_version=? AND lease_token=? AND state='RUNNING' AND lease_until>=?
            """,h.source(),result,hash,now+properties.getRecheckInterval().toMillis(),now,h.key(),h.source(),token,now)==1;}
    void checked(Head h,long now){advance(h);}
    // 空闲核对不产生持久写入；游标在实际访问后推进，批次提前结束也不会跳过未处理任务。
    void advance(Head h){if(Set.of("JOINT_FLOW","CARBON_INTENSITY").contains(h.kind()))generalCursor=h.key();else periodCursor=h.key();}
    void failed(Head h,String token,String code,int status,long now){jdbc.update("UPDATE biz_carbon_display_read_model SET state='FAILED',last_error=?,error_status=?,lease_token=NULL,lease_until=0,next_check=?,updated_at=? WHERE query_key=? AND source_version=? AND lease_token=?",code,status,now+properties.getRecheckInterval().toMillis(),now,h.key(),h.source(),token);}
    void defer(Head h,long now){jdbc.update("UPDATE biz_carbon_display_read_model SET next_check=? WHERE query_key=? AND source_version=? AND (state<>'RUNNING' OR lease_until<?)",now+properties.getRecheckInterval().toMillis(),h.key(),h.source(),now);}
}
