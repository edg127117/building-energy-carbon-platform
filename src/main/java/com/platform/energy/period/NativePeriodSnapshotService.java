package com.platform.energy.period;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/** 独立原生量提交端口：不调用折标服务，MySQL 内容固定后幂等写 TDengine 才可读。 */
@Service
@RequiredArgsConstructor
public class NativePeriodSnapshotService {
    public record NumericSample(Instant at, BigDecimal value, BigDecimal coverageRatio) {}
    public record Snapshot(String snapshotId,String buildingId,String sourceId,String quantityType,
            String unitCode,String evidenceHash,List<NumericSample> samples) {}
    private final JdbcTemplate jdbc;
    private final EnergyPeriodValueStore store;
    private final EnergyPeriodAuthorization authorization;
    private final ObjectMapper mapper;

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void publish(long user, Collection<String> roles, Snapshot value) {
        if(value==null) throw new IllegalArgumentException("Native snapshot required");
        authorization.requireCalculation(user,roles); authorization.checkBuilding(user,roles,value.buildingId());
        if (value.samples() == null || value.samples().isEmpty() || value.samples().size() > 20000
                || !List.of("COOLING_ENERGY","ELECTRICITY_ENERGY","COOLING_POWER","EERP").contains(value.quantityType())
                || !("EERP".equals(value.quantityType()) ? "1" : "COOLING_POWER".equals(value.quantityType()) ? "kW" : "kWh").equals(value.unitCode()))
            throw new IllegalArgumentException("Native snapshot type, unit or size invalid");
        if(value.snapshotId()==null||value.snapshotId().isBlank()||value.snapshotId().length()>64
                ||value.sourceId()==null||value.sourceId().isBlank()||value.sourceId().length()>32
                ||value.evidenceHash()==null||!value.evidenceHash().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Native snapshot identity or evidence invalid");
        java.util.Set<Instant> times=new java.util.HashSet<>();
        for(NumericSample sample:value.samples()) {
            if(sample==null||sample.at()==null||sample.at().getNano()%1_000_000!=0||!times.add(sample.at())
                    ||sample.value()==null||sample.value().signum()<0||sample.value().toPlainString().length()>48
                    ||sample.coverageRatio()==null||sample.coverageRatio().signum()<0||sample.coverageRatio().compareTo(BigDecimal.ONE)>0
                    ||sample.coverageRatio().toPlainString().length()>48)
                throw new IllegalArgumentException("Native sample value, precision or timestamp invalid");
        }
        String json = json(value), hash = hash(json);
        try {
            jdbc.update("INSERT INTO energy_native_quantity_snapshot(snapshot_id,building_id,quantity_type,content_hash,content_json,status,created_at) VALUES (?,?,?,?,?,'PENDING',?)",
                    value.snapshotId(),value.buildingId(),value.quantityType(),hash,json,Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException exists) {
            var hashes = jdbc.queryForList("SELECT content_hash FROM energy_native_quantity_snapshot WHERE snapshot_id=?",String.class,value.snapshotId());
            if (hashes.size()!=1 || !hash.equals(hashes.getFirst())) throw new IllegalStateException("Immutable native snapshot conflict");
        }
        for (NumericSample sample : value.samples()) {
            // 复用数值表的point_id标签但不冒充物理测点；原始依赖保存在不可变来源快照中。
            String derivedIdentity="NATIVE:"+hash(value.buildingId()+":"+value.sourceId()+":"+value.quantityType()).substring(0,25);
            // 既有TDengine覆盖率文本列只有24字节；完整精度仍在MySQL证据，完整性不用此显示副本判断。
            BigDecimal numericCoverage=sample.coverageRatio().setScale(18,java.math.RoundingMode.HALF_UP);
            store.write(new EnergyPeriodModels.NumericResult(value.snapshotId(),value.buildingId(),derivedIdentity,sample.at(),
                    sample.value(),value.unitCode(),null,null,numericCoverage,"DEVELOPMENT_SIMULATION",value.evidenceHash(),1));
        }
        jdbc.update("UPDATE energy_native_quantity_snapshot SET status='VISIBLE' WHERE snapshot_id=? AND content_hash=?",value.snapshotId(),hash);
    }
    public Snapshot read(long user, Collection<String> roles, String building, String id) {
        authorization.requireReader(roles); authorization.checkBuilding(user,roles,building);
        var rows = jdbc.queryForList("SELECT content_json FROM energy_native_quantity_snapshot WHERE snapshot_id=? AND building_id=? AND status='VISIBLE'",String.class,id,building);
        if (rows.size()!=1) throw new com.platform.framework.exception.BusinessException(404,"NATIVE_SNAPSHOT_NOT_VISIBLE","原生量快照不存在或尚未发布");
        try { return mapper.readValue(rows.getFirst(),Snapshot.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Native snapshot unreadable",e); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
