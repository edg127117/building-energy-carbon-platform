package com.platform.carbon;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 可重建摘要和任务共用一行；租约令牌阻止过时任务发布，来源版本只读取索引及不可变头信息。 */
@Repository
public class DailyTrendSummaryRepository {
    private final JdbcTemplate jdbc;
    private final DailyTrendSummaryProperties properties;
    public DailyTrendSummaryRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,DailyTrendSummaryProperties properties){this.jdbc=jdbc;this.properties=properties;}
    public record Head(String building,String month,long user,String sourceVersion,String builtVersion,String state,String hash,String token,long leaseUntil,long nextCheck) { }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public String sourceVersion(String building,YearMonth month) {
        var zone=ZoneId.of("Asia/Shanghai");
        var from=Timestamp.from(month.atDay(1).atStartOfDay(zone).toInstant());
        var to=Timestamp.from(month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
        var version=new ArrayList<Object>();
        version.add(List.of("DAILY_SUMMARY_ALGORITHM_2",building,month.toString(),month.isBefore(YearMonth.now(zone))?"CLOSED":LocalDate.now(zone).toString()));
        add(version,"SELECT version_id,revision,effective_from,effective_to FROM biz_energy_classification_version WHERE building_id=? AND status='APPROVED' ORDER BY version_id",building);
        // 人工批次审核后不可变；按建筑核对全部有效批次头，避免扫描数十万条小时事实或漏掉跨月修订。
        add(version,"SELECT batch_id,payload_hash,revision,reviewed_at FROM biz_energy_manual_batch WHERE building_id=? AND status='APPROVED' ORDER BY batch_id",building);
        add(version,"SELECT version_id,config_revision,status,snapshot_sha256,effective_at,superseded_at FROM biz_relation_version WHERE building_id=? AND effective_at IS NOT NULL ORDER BY version_id",building);
        add(version,"SELECT model_id,governance_mode,active_version_id FROM biz_relation_model WHERE building_id=? ORDER BY model_id",building);
        add(version,"SELECT scope_id,relation_version_id,start_epoch_ms,end_epoch_ms,status FROM biz_historical_metering_scope WHERE building_id=? AND status='APPROVED' ORDER BY scope_id",building);
        add(version,"SELECT point_id,equip_id,del_flag FROM biz_data_point WHERE building_id=? ORDER BY point_id",building);
        add(version,"""
                SELECT s.snapshot_id,s.snapshot_version,s.status,s.evidence_hash,b.status AS batch_status
                FROM biz_energy_period_result_snapshot s LEFT JOIN biz_energy_recalculation_batch b ON b.batch_id=s.source_batch_id
                WHERE s.building_id=? AND s.period_type='DAY' AND s.period_start>=? AND s.period_end<=? ORDER BY s.snapshot_id
                """,building,from,to);
        add(version,"""
                SELECT batch_id,revision,calculation_as_of,view_hash,evidence_hash FROM biz_carbon_hourly_batch
                WHERE building_id=? AND day_date>=? AND day_date<? ORDER BY batch_id
                """,building,java.sql.Date.valueOf(month.atDay(1)),java.sql.Date.valueOf(month.plusMonths(1).atDay(1)));
        return CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(version)));
    }
    private void add(List<Object> target,String sql,Object... args) {
        var values=new ArrayList<Object>(Arrays.asList(args));values.add(properties.getMaximumVersionRows()+1);
        var rows=jdbc.queryForList(sql+" LIMIT ?",values.toArray());
        if(rows.size()>properties.getMaximumVersionRows())throw new com.platform.framework.exception.BusinessException(503,"DAILY_SUMMARY_VERSION_CAPACITY","日趋势来源版本超过读取容量");
        target.add(rows.stream().map(row->row.values().stream().map(v->Objects.toString(v,"")).toList()).toList());
    }
    public Head head(String building,String month) {
        return jdbc.query("SELECT building_id,month_key,requested_by,source_version,built_version,state,summary_hash,lease_token,lease_until,next_check FROM biz_daily_trend_read_model WHERE building_id=? AND month_key=?",
                (r,n)->new Head(r.getString(1),r.getString(2),r.getLong(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getLong(9),r.getLong(10)),building,month).stream().findFirst().orElse(null);
    }
    public String payload(Head h){return jdbc.queryForObject("SELECT summary_json FROM biz_daily_trend_read_model WHERE building_id=? AND month_key=? AND built_version=?",String.class,h.building(),h.month(),h.builtVersion());}
    public void request(String building,String month,long user,String version,long now) {
        jdbc.update("""
                INSERT INTO biz_daily_trend_read_model(building_id,month_key,requested_by,source_version,state,updated_at,next_check)
                VALUES (?,?,?,?,'PENDING',?,?) ON DUPLICATE KEY UPDATE requested_by=VALUES(requested_by)
                """,building,month,user,version,now,now);
        jdbc.update("""
                UPDATE biz_daily_trend_read_model SET source_version=?,state='PENDING',lease_token=NULL,lease_until=0,attempts=0,last_error=NULL,next_check=?,updated_at=?
                WHERE building_id=? AND month_key=? AND source_version<>?
                """,version,now,now,building,month,version);
    }
    public List<Head> due(long now) {
        return jdbc.query("SELECT building_id,month_key FROM biz_daily_trend_read_model WHERE next_check<=? AND (state<>'RUNNING' OR lease_until<?) ORDER BY next_check,building_id,month_key LIMIT 10",
                (r,n)->head(r.getString(1),r.getString(2)),now,now);
    }
    public String claim(Head h,long now) {
        String token=UUID.randomUUID().toString().replace("-","");
        int changed=jdbc.update("""
                UPDATE biz_daily_trend_read_model SET state='RUNNING',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=?
                WHERE building_id=? AND month_key=? AND source_version=? AND (state<>'RUNNING' OR lease_until<?)
                """,token,now+properties.getLease().toMillis(),now,h.building(),h.month(),h.sourceVersion(),now);
        return changed==1?token:null;
    }
    public boolean publish(Head h,String token,String payload,String hash,long now) {
        return jdbc.update("""
                UPDATE biz_daily_trend_read_model SET state='READY',built_version=?,summary_json=?,summary_hash=?,lease_token=NULL,lease_until=0,last_error=NULL,next_check=?,updated_at=?
                WHERE building_id=? AND month_key=? AND source_version=? AND lease_token=? AND state='RUNNING' AND lease_until>=?
                """,h.sourceVersion(),payload,hash,now+properties.getRecheckInterval().toMillis(),now,h.building(),h.month(),h.sourceVersion(),token,now)==1;
    }
    public void checked(Head h,long now){jdbc.update("UPDATE biz_daily_trend_read_model SET next_check=? WHERE building_id=? AND month_key=? AND source_version=? AND state='READY'",now+properties.getRecheckInterval().toMillis(),h.building(),h.month(),h.sourceVersion());}
    public void defer(Head h,String reason,long now){jdbc.update("UPDATE biz_daily_trend_read_model SET last_error=?,next_check=? WHERE building_id=? AND month_key=? AND source_version=? AND (state<>'RUNNING' OR lease_until<?)",reason,now+properties.getRecheckInterval().toMillis(),h.building(),h.month(),h.sourceVersion(),now);}
    public void failed(Head h,String token,String reason,long now){jdbc.update("UPDATE biz_daily_trend_read_model SET state='FAILED',last_error=?,lease_token=NULL,lease_until=0,next_check=?,updated_at=? WHERE building_id=? AND month_key=? AND lease_token=?",reason,now+properties.getRecheckInterval().toMillis(),now,h.building(),h.month(),token);}
    public record Candidate(String building,String month,long user) { }
    public void registerDiscovered(Candidate c,long now) {
        // 全零仅表示待解析的任务版本；插入冲突绝不覆盖用户已经登记或发布的有效版本。
        jdbc.update("INSERT INTO biz_daily_trend_read_model(building_id,month_key,requested_by,source_version,state,updated_at,next_check) VALUES (?,?,?,?,'PENDING',?,?) ON DUPLICATE KEY UPDATE building_id=VALUES(building_id)",c.building(),c.month(),c.user(),"0".repeat(64),now,now);
    }
    public List<Candidate> discover() {
        // 只预热已有小时结果且尚未登记的月份；不用扫描读数，不为无数据历史自动扩建整包结果。
        return jdbc.query("""
                SELECT h.building_id,SUBSTRING(CAST(h.day_date AS CHAR(10)),1,7) AS month_key,MIN(h.created_by) AS actor FROM biz_carbon_hourly_batch h
                WHERE NOT EXISTS (SELECT 1 FROM biz_daily_trend_read_model m WHERE m.building_id=h.building_id AND m.month_key=SUBSTRING(CAST(h.day_date AS CHAR(10)),1,7))
                GROUP BY h.building_id,SUBSTRING(CAST(h.day_date AS CHAR(10)),1,7) ORDER BY h.building_id,month_key LIMIT 20
                """,(r,n)->new Candidate(r.getString(1),r.getString(2),r.getLong(3)));
    }
}
