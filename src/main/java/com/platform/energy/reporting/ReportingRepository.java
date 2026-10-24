package com.platform.energy.reporting;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

/** 建筑锁、修订号和封账载荷在同一MySQL事务内更新，与表计内部表解耦。 */
@Repository
public class ReportingRepository {
    private final JdbcTemplate jdbc;
    public ReportingRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean buildingExists(String building) {
        return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM building WHERE building_id=? AND del_flag=0",Integer.class,building));
    }
    public record Stored(String id,String building,String kind,String status,long revision,String month,String payload) { }
    public void lock(String building) {
        jdbc.update("INSERT INTO biz_energy_reporting_scope(building_id,revision) VALUES (?,0) ON DUPLICATE KEY UPDATE revision=revision",building);
        jdbc.queryForObject("SELECT revision FROM biz_energy_reporting_scope WHERE building_id=? FOR UPDATE",Long.class,building);
    }
    public List<Stored> list(String building,String kind) {
        return jdbc.query("SELECT * FROM biz_energy_reporting_record WHERE building_id=? AND record_kind=? ORDER BY record_id LIMIT 501",
            (r,n)->new Stored(r.getString("record_id"),r.getString("building_id"),r.getString("record_kind"),r.getString("status"),
                r.getLong("revision"),r.getString("month_date"),r.getString("payload_json")),building,kind);
    }
    public Stored find(String building,String id) {
        return jdbc.query("SELECT * FROM biz_energy_reporting_record WHERE building_id=? AND record_id=?",
            (r,n)->new Stored(r.getString("record_id"),r.getString("building_id"),r.getString("record_kind"),r.getString("status"),
                r.getLong("revision"),r.getString("month_date"),r.getString("payload_json")),building,id).stream().findFirst().orElse(null);
    }
    public void insert(Stored r) {
        jdbc.update("INSERT INTO biz_energy_reporting_record(record_id,building_id,record_kind,status,revision,month_date,payload_json) VALUES (?,?,?,?,?,?,?)",
            r.id(),r.building(),r.kind(),r.status(),r.revision(),r.month(),r.payload());
    }
    public int update(Stored r,long expected) {
        return jdbc.update("UPDATE biz_energy_reporting_record SET status=?,revision=?,payload_json=? WHERE record_id=? AND building_id=? AND revision=?",
            r.status(),r.revision(),r.payload(),r.id(),r.building(),expected);
    }
    public record Receipt(String hash,String json) { }
    public Receipt receipt(String building,String key) {
        return jdbc.query("SELECT request_hash,response_json FROM biz_energy_reporting_receipt WHERE building_id=? AND request_key=?",
            (r,n)->new Receipt(r.getString(1),r.getString(2)),building,key).stream().findFirst().orElse(null);
    }
    public void receipt(String building,String key,String hash,String json) {
        jdbc.update("INSERT INTO biz_energy_reporting_receipt(building_id,request_key,request_hash,response_json) VALUES (?,?,?,?)",building,key,hash,json);
    }
}
