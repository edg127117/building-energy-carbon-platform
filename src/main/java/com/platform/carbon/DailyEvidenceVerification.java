package com.platform.carbon;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.*;

/** 按持久化检查时间轮转完整证据；错误状态与展示失效由同一数据库事务内的触发器提交。 */
@Component
class DailyEvidenceVerification {
    private final JdbcTemplate jdbc;
    private final HourlyCarbonRepository hourly;
    private final DailyStaticCarbonRepository daily;
    DailyEvidenceVerification(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,HourlyCarbonRepository hourly,DailyStaticCarbonRepository daily) {
        this.jdbc=jdbc;this.hourly=hourly;this.daily=daily;
    }
    record Item(String kind,String id,String building,LocalDate date) { }
    int verify(int limit) {
        if(limit<1||limit>16)throw new IllegalArgumentException("Invalid evidence verification batch");
        long now=System.currentTimeMillis();int checked=0;
        for(String kind:List.of("HOUR","DAY")) {
            String table=kind.equals("HOUR")?"biz_carbon_hourly_batch":"biz_carbon_daily_static_batch";
            var items=jdbc.query("SELECT b.batch_id,b.building_id,b.day_date FROM "+table+" b LEFT JOIN biz_daily_evidence_verification v ON v.batch_id=b.batch_id AND v.evidence_kind=? WHERE v.batch_id IS NULL OR v.last_verified_at<? ORDER BY COALESCE(v.last_verified_at,0),b.batch_id LIMIT ?",
                    (rs,n)->new Item(kind,rs.getString(1),rs.getString(2),rs.getDate(3).toLocalDate()),kind,now-86_400_000,limit-checked);
            for(var item:items) {
                String error=null;
                try {
                    if(kind.equals("HOUR")) {
                        var stored=hourly.byId(item.id());
                        if(stored==null||!item.building().equals(stored.view().buildingId())||!item.date().equals(stored.view().date()))throw new IllegalStateException("Evidence scope mismatch");
                    }else {
                        var stored=daily.byId(item.id());
                        if(stored==null||!item.building().equals(stored.view().buildingId())||!item.date().equals(stored.view().date()))throw new IllegalStateException("Evidence scope mismatch");
                    }
                }catch(org.springframework.dao.DataAccessException unavailable){throw unavailable;}
                catch(RuntimeException failure) {
                    error="DAILY_EVIDENCE_INTEGRITY";
                    org.slf4j.LoggerFactory.getLogger(getClass()).error("Daily evidence verification failed kind={} batch={}",kind,item.id());
                }
                jdbc.update("""
                        INSERT INTO biz_daily_evidence_verification(evidence_kind,batch_id,building_id,day_date,last_verified_at,integrity_error)
                        VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE last_verified_at=VALUES(last_verified_at),integrity_error=VALUES(integrity_error)
                        """,kind,item.id(),item.building(),java.sql.Date.valueOf(item.date()),now,error);
                checked++;
            }
            if(checked==limit)break;
        }
        return checked;
    }
}
