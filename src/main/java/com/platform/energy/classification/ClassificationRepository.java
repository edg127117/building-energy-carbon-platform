package com.platform.energy.classification;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 分类版本与人工来源的 MySQL 边界；建筑锁串行化版本发布、导入幂等及重复期间检查。 */
@Repository
public class ClassificationRepository {
    private final JdbcTemplate jdbc;
    public ClassificationRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Version(String id, String buildingId, int no, long revision, String status,
                          Long from, Long to, String json, long author, Long reviewer) {}
    public record Batch(String id, String buildingId, String hash, String status, long revision, long author) {}
    public record StoredReading(String id, String batchId, String json, BigDecimal quantity, long author, String batchStatus) {}

    public void lock(String buildingId) {
        try { jdbc.update("INSERT INTO biz_energy_classification_scope(building_id,revision) VALUES (?,0)", buildingId); }
        catch (DuplicateKeyException ignored) { /* 已存在的建筑行仍需加锁，不能依靠进程内同步。 */ }
        jdbc.queryForObject("SELECT revision FROM biz_energy_classification_scope WHERE building_id=? FOR UPDATE", Long.class, buildingId);
    }
    public boolean buildingExists(String id) { return count("SELECT COUNT(*) FROM building WHERE building_id=? AND del_flag=0", id)>0; }
    public boolean objectExists(String building, String type, String id) {
        String sql = switch (type) {
            case "POINT" -> "SELECT COUNT(*) FROM biz_data_point WHERE building_id=? AND point_id=? AND del_flag=0";
            case "EQUIPMENT" -> "SELECT COUNT(*) FROM biz_equipment WHERE building_id=? AND equip_id=? AND del_flag=0";
            case "SPACE" -> "SELECT COUNT(*) FROM biz_space WHERE building_id=? AND space_id=? AND del_flag=0";
            default -> null;
        };
        return sql != null && count(sql, building, id)>0;
    }
    public java.util.Map<String,String> pointEquipment(String building,java.util.Collection<String> pointIds) {
        java.util.Map<String,String> result=new java.util.HashMap<>();
        if(pointIds.isEmpty())return result;
        java.util.List<Object> args=new java.util.ArrayList<>();args.add(building);args.addAll(pointIds);
        String parameters=String.join(",",java.util.Collections.nCopies(pointIds.size(),"?"));
        jdbc.query("SELECT point_id,equip_id FROM biz_data_point WHERE building_id=? AND del_flag=0 AND equip_id IS NOT NULL AND point_id IN ("+parameters+")",r->{result.put(r.getString("point_id"),r.getString("equip_id"));},args.toArray());
        return result;
    }
    public List<Version> versions(String building) {
        return jdbc.query("SELECT * FROM biz_energy_classification_version WHERE building_id=? ORDER BY version_no DESC LIMIT 100", (r,n)->new Version(r.getString("version_id"), r.getString("building_id"), r.getInt("version_no"), r.getLong("revision"), r.getString("status"), (Long)r.getObject("effective_from"), (Long)r.getObject("effective_to"), r.getString("configuration_json"),r.getLong("created_by"),(Long)r.getObject("reviewed_by")), building);
    }
    public Version version(String building, String id) {
        return jdbc.query("SELECT * FROM biz_energy_classification_version WHERE building_id=? AND version_id=?", (r,n)->new Version(r.getString("version_id"),r.getString("building_id"),r.getInt("version_no"),r.getLong("revision"),r.getString("status"),(Long)r.getObject("effective_from"),(Long)r.getObject("effective_to"),r.getString("configuration_json"),r.getLong("created_by"),(Long)r.getObject("reviewed_by")),building,id).stream().findFirst().orElse(null);
    }
    public void insertVersion(String id, String building, String json, String reason, long user) {
        Integer no=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM biz_energy_classification_version WHERE building_id=?",Integer.class,building);
        jdbc.update("INSERT INTO biz_energy_classification_version(version_id,building_id,version_no,revision,status,configuration_json,reason,created_by,created_at) VALUES (?,?,?,0,'DRAFT',?,?,?,?)",id,building,no,json,reason,user,Instant.now().toEpochMilli());
    }
    public int save(Version v, long revision, String json, String reason) {
        return jdbc.update("UPDATE biz_energy_classification_version SET configuration_json=?,reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='DRAFT'",json,reason,v.id(),revision);
    }
    public int submit(Version v, long revision, String reason) {
        return jdbc.update("UPDATE biz_energy_classification_version SET status='PENDING_REVIEW',reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='DRAFT'",reason,v.id(),revision);
    }
    public int reopen(Version v,long revision,String reason) {
        return jdbc.update("UPDATE biz_energy_classification_version SET status='DRAFT',reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='PENDING_REVIEW'",reason,v.id(),revision);
    }
    public int approve(Version v, long revision, Instant from, long reviewer, String reason) {
        jdbc.update("UPDATE biz_energy_classification_version SET effective_to=? WHERE building_id=? AND status='APPROVED' AND effective_to IS NULL",from.toEpochMilli(),v.buildingId());
        return jdbc.update("UPDATE biz_energy_classification_version SET status='APPROVED',effective_from=?,reviewed_by=?,reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='PENDING_REVIEW'",from.toEpochMilli(),reviewer,reason,v.id(),revision);
    }
    public List<Version> effective(String building, Instant from, Instant to) {
        return jdbc.query("SELECT * FROM biz_energy_classification_version WHERE building_id=? AND status='APPROVED' AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)", (r,n)->new Version(r.getString("version_id"),r.getString("building_id"),r.getInt("version_no"),r.getLong("revision"),r.getString("status"),(Long)r.getObject("effective_from"),(Long)r.getObject("effective_to"),r.getString("configuration_json"),r.getLong("created_by"),(Long)r.getObject("reviewed_by")),building,from.toEpochMilli(),to.toEpochMilli());
    }
    public Batch batch(String building, String idOrKey, boolean byKey) {
        return jdbc.query("SELECT * FROM biz_energy_manual_batch WHERE building_id=? AND "+(byKey?"idempotency_key":"batch_id")+"=?",(r,n)->new Batch(r.getString("batch_id"),r.getString("building_id"),r.getString("payload_hash"),r.getString("status"),r.getLong("revision"),r.getLong("created_by")),building,idOrKey).stream().findFirst().orElse(null);
    }
    public void insertBatch(String id,String building,String key,String hash,String reason,long user) {
        jdbc.update("INSERT INTO biz_energy_manual_batch(batch_id,building_id,idempotency_key,payload_hash,status,created_by,reason,created_at) VALUES (?,?,?,?,'PENDING_REVIEW',?,?,?)",id,building,key,hash,user,reason,Instant.now().toEpochMilli());
    }
    public void insertReading(String id,String batch,String building,ClassificationContracts.Reading reading,String json,BigDecimal quantity,long user) {
        jdbc.update("INSERT INTO biz_energy_manual_reading(fact_id,batch_id,building_id,point_id,start_inclusive,end_exclusive,reading_json,normalized_quantity,supersedes_fact_id,created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",id,batch,building,reading.pointId(),reading.startInclusive().toEpochMilli(),reading.endExclusive().toEpochMilli(),json,quantity,reading.supersedesFactId(),user);
    }
    public int approveBatch(String building,String id,long revision,long reviewer) {
        return jdbc.update("UPDATE biz_energy_manual_batch SET status='APPROVED',reviewed_by=?,revision=revision+1 WHERE building_id=? AND batch_id=? AND status='PENDING_REVIEW' AND revision=?",reviewer,building,id,revision);
    }
    public List<StoredReading> readings(String building,Instant from,Instant to,boolean approved) {
        return jdbc.query("SELECT r.*,b.status AS batch_status FROM biz_energy_manual_reading r JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>? "+(approved?"AND b.status='APPROVED' AND NOT EXISTS (SELECT 1 FROM biz_energy_manual_reading s JOIN biz_energy_manual_batch sb ON sb.batch_id=s.batch_id WHERE s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED') ":"")+"ORDER BY r.start_inclusive,r.fact_id LIMIT 5001",(r,n)->new StoredReading(r.getString("fact_id"),r.getString("batch_id"),r.getString("reading_json"),r.getBigDecimal("normalized_quantity"),r.getLong("created_by"),r.getString("batch_status")),building,to.toEpochMilli(),from.toEpochMilli());
    }
    public List<StoredReading> batchReadings(String building,String batch) {
        return jdbc.query("SELECT r.*,b.status AS batch_status FROM biz_energy_manual_reading r JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id WHERE r.building_id=? AND r.batch_id=? ORDER BY r.fact_id",(r,n)->new StoredReading(r.getString("fact_id"),r.getString("batch_id"),r.getString("reading_json"),r.getBigDecimal("normalized_quantity"),r.getLong("created_by"),r.getString("batch_status")),building,batch);
    }
    private long count(String sql,Object... args) { Long n=jdbc.queryForObject(sql,Long.class,args);return n==null?0:n; }
}
