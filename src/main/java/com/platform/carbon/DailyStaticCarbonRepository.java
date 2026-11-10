package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.platform.carbon.DailyStaticCarbonContracts.*;
import static com.platform.carbon.CarbonErrors.*;

/** Immutable DAY batches. Full frozen text is verified on every direct read, including replays. */
@Repository
public class DailyStaticCarbonRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;
    public DailyStaticCarbonRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,ObjectMapper mapper,
            AuditEvidenceWriter audit,AuditGovernanceProperties auditProperties) {
        this.jdbc=jdbc;this.mapper=mapper;this.audit=audit;this.auditProperties=auditProperties;
    }
    public record Stored(View view,JsonNode evidence) { }
    private record Row(String id,String building,LocalDate date,int revision,String requestHash,long asOf,
                       String sourceVersion,String view,String viewHash,String evidence,String evidenceHash) { }
    public Stored replay(String building,String key,String requestHash) {
        Row row=one("SELECT * FROM biz_carbon_daily_static_batch WHERE building_id=? AND idempotency_key=?",building,key);
        if(row==null)return null;
        if(!requestHash.equals(row.requestHash()))throw error(409,IDEMPOTENCY_CONFLICT,"Daily static idempotency key already has a different request");
        return stored(row);
    }
    // DAY captures current approved data, not an asOf receive-time cutoff; the newest correction wins.
    public Stored byDate(String building,LocalDate date,Integer revision) {
        return stored(revision==null?one("SELECT * FROM biz_carbon_daily_static_batch WHERE building_id=? AND day_date=? ORDER BY revision DESC LIMIT 1",building,java.sql.Date.valueOf(date))
                :one("SELECT * FROM biz_carbon_daily_static_batch WHERE building_id=? AND day_date=? AND revision=?",building,java.sql.Date.valueOf(date),revision));
    }
    public Stored byId(String id) { return stored(one("SELECT * FROM biz_carbon_daily_static_batch WHERE batch_id=?",id)); }
    @Transactional(rollbackFor=Exception.class)
    public Stored publish(long user,Request request,String requestHash,View draft,JsonNode evidence,Runnable verifyCurrent) {
        budget();
        jdbc.update("INSERT INTO biz_carbon_daily_static_scope(building_id,day_date,revision) VALUES (?,?,0) ON DUPLICATE KEY UPDATE revision=revision",request.buildingId(),java.sql.Date.valueOf(request.date()));
        Integer revision=jdbc.queryForObject("SELECT revision FROM biz_carbon_daily_static_scope WHERE building_id=? AND day_date=? FOR UPDATE",Integer.class,request.buildingId(),java.sql.Date.valueOf(request.date()));
        Stored replay=replay(request.buildingId(),request.idempotencyKey(),requestHash);if(replay!=null)return replay;
        // A second independent normal input/rule capture is checked after acquiring the scope lock.
        // Source changes abort the entire insert and audit; no GET or read-model rebuild can publish a calculation.
        verifyCurrent.run();budget();
        String body=CarbonEvidence.canonical(evidence);
        if(body.getBytes(StandardCharsets.UTF_8).length>16*1024*1024 || !hash(body).equals(draft.evidenceHash()) || !frozenMatches(draft,evidence))throw integrity();
        int next=Math.addExact(Objects.requireNonNull(revision),1);
        View view=identity(draft,UUID.randomUUID().toString().replace("-",""),next);
        String viewJson=CarbonEvidence.canonical(CarbonEvidence.tree(view));
        long now=System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO biz_carbon_daily_static_batch(batch_id,building_id,day_date,revision,idempotency_key,request_hash,
                calculation_as_of,source_version,view_json,view_hash,evidence_json,evidence_hash,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,view.calculationBatchId(),view.buildingId(),java.sql.Date.valueOf(view.date()),next,request.idempotencyKey(),requestHash,
                view.calculationAsOf().toEpochMilli(),view.sourceVersion(),viewJson,hash(viewJson),body,view.evidenceHash(),user,now);
        if(jdbc.update("UPDATE biz_carbon_daily_static_scope SET revision=? WHERE building_id=? AND day_date=? AND revision=?",next,view.buildingId(),java.sql.Date.valueOf(view.date()),revision)!=1)
            throw error(409,RECALCULATION_CONFLICT,"Daily static scope changed");
        audit.append(new AuditEvidence("CARBON_MANAGEMENT",view.buildingId(),"USER",user,"CREATE_DAILY_STATIC_CARBON_BATCH",
                "DAILY_STATIC_CARBON_BATCH",view.calculationBatchId(),Integer.toString(next),null,null,
                "date="+view.date()+";requestHash="+requestHash+";evidenceHash="+view.evidenceHash(),"SUCCESS",null,
                TraceContext.current(),LocalDateTime.now(),auditProperties.getEnvironmentMode(),false));
        budget();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { budget(); }
                });
        return new Stored(view,CarbonEvidence.parse(body));
    }
    private Stored stored(Row row) {
        if(row==null)return null;
        if(!matches(row.view(),row.viewHash()) || !matches(row.evidence(),row.evidenceHash()))throw integrity();
        try {
            JsonNode evidence=CarbonEvidence.parse(row.evidence());
            View view=mapper.readValue(row.view(),View.class);
            if(!row.requestHash().equals(CarbonEvidence.canonicalHash(evidence.path("request"))) || !frozenMatches(view,evidence) || !CarbonEvidence.canonicalEquals(evidence,row.evidence())
                    || !CarbonEvidence.canonical(CarbonEvidence.tree(view)).equals(row.view())
                    || !Objects.equals(view.calculationBatchId(),row.id()) || !Objects.equals(view.buildingId(),row.building())
                    || !Objects.equals(view.date(),row.date()) || !Objects.equals(view.revision(),row.revision())
                    || !Objects.equals(view.calculationAsOf(),Instant.ofEpochMilli(row.asOf()))
                    || !Objects.equals(view.sourceVersion(),row.sourceVersion()) || !Objects.equals(view.evidenceHash(),row.evidenceHash())
                    || !"DAY".equals(view.periodType()) || !"MONITORING".equals(view.resultNature())
                    || !"Asia/Shanghai".equals(view.timezoneId()) || !"KG_CO2E".equals(view.unit())
                    || !"PURCHASED_ELECTRICITY_SCOPE_2".equals(view.scope()) || !"COMPLETE".equals(view.state())
                    || !view.date().atStartOfDay(ZoneId.of(view.timezoneId())).toInstant().equals(view.startInclusive())
                    || !view.date().plusDays(1).atStartOfDay(ZoneId.of(view.timezoneId())).toInstant().equals(view.endExclusive()))throw integrity();
            budget();return new Stored(view,evidence);
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException | IllegalStateException e) { throw integrity(); }
    }
    private static boolean frozenMatches(View view,JsonNode evidence) {
        try {
            var request=evidence.path("request");var calculation=evidence.path("calculation");
            var factor=DailyElectricityCarbonService.frozenFactor(calculation);
            return "DAILY_STATIC_PURCHASED_ELECTRICITY_V1".equals(evidence.path("calculationVersion").asText())
                    && view.buildingId().equals(request.path("buildingId").asText()) && view.date().toString().equals(request.path("date").asText())
                    && view.calculationAsOf().equals(Instant.parse(request.path("calculationAsOf").asText()))
                    && view.declaredSourceNature().equals(request.path("declaredSourceNature").asText())
                    && view.factorUsageNature().equals(request.path("factorUsageNature").asText())
                    && "CURRENT_APPROVED_AT_CALCULATION".equals(view.activityWatermarkMeaning())
                    && view.activityWatermarkMeaning().equals(evidence.path("activityWatermarkMeaning").asText())
                    && view.sourceVersion().equals(evidence.path("sourceVersion").asText())
                    && view.inputEvidenceHash().equals(CarbonEvidence.canonicalHash(evidence.path("input")))
                    && view.classificationVersionId().equals(evidence.path("input").path("classification").path("versionId").asText())
                    && view.projectVersionId().equals(evidence.path("input").path("project").path("versionId").asText())
                    && view.relationVersionId().equals(evidence.path("input").path("projection").path("relationVersionId").asText())
                    && view.electricitySourceNature().equals(evidence.path("input").path("projection").path("resultNature").asText())
                    && "DAY".equals(evidence.path("activity").path("periodType").asText())
                    && view.electricityKwh()!=null && view.electricityKwh().signum()>=0
                    && view.electricityKwh().compareTo(new java.math.BigDecimal(calculation.path("activityQuantity").asText()))==0
                    && view.emissionKgCo2e()!=null && view.emissionKgCo2e().signum()>=0
                    && view.emissionKgCo2e().compareTo(new java.math.BigDecimal(calculation.path("rawKgCO2e").asText()))==0
                    && factor!=null && factor.compareTo(view.staticFactorKgCo2ePerKwh())==0
                    && "AVAILABLE".equals(view.factorStatus())
                    && view.factorVersionIds().equals(List.of(calculation.path("factorVersionId").asText()))
                    && view.gasBasis().equals("CO2_ONLY_ELECTRICITY".equals(calculation.path("factor").path("gasCoverage").asText())?"CO2_ONLY":"GHG_CO2E");
        } catch(IllegalArgumentException | NullPointerException | java.time.DateTimeException failure) { return false; }
    }
    private Row one(String sql,Object... args) {
        org.springframework.jdbc.core.RowMapper<Row> mapping=(r,n)->new Row(r.getString("batch_id"),r.getString("building_id"),r.getDate("day_date").toLocalDate(),
                r.getInt("revision"),r.getString("request_hash"),r.getLong("calculation_as_of"),r.getString("source_version"),
                r.getString("view_json"),r.getString("view_hash"),r.getString("evidence_json"),r.getString("evidence_hash"));
        List<Row> values;
        if(CarbonCalculationDeadline.currentRemaining()==null)values=jdbc.query(sql,mapping,args);
        else {
            requireReadBudget();
            values=jdbc.query(connection->{
                // Acquiring a connection consumes this same budget; refresh it before preparing SQL.
                Duration remaining=requireReadBudget();String select=sql.stripLeading();
                if(!select.startsWith("SELECT "))throw new IllegalArgumentException("Daily static read requires SELECT");
                return connection.prepareStatement("SELECT /*+ MAX_EXECUTION_TIME("+Math.min(Integer.MAX_VALUE,remaining.toMillis())+") */ "+select.substring(7));
            },statement->{
                Duration remaining=requireReadBudget();int existing=statement.getQueryTimeout();
                int requested=CarbonCalculationDeadline.timeoutSeconds(remaining);
                statement.setQueryTimeout(existing>0?Math.min(existing,requested):requested);
                for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
            },new org.springframework.jdbc.core.RowMapperResultSetExtractor<>(mapping));
            requireReadBudget();
        }
        return values.isEmpty()?null:values.getFirst();
    }
    private static Duration requireReadBudget() {
        Duration remaining=CarbonCalculationDeadline.currentRemaining();
        // A zero MySQL hint disables its limit, so sub-millisecond requests fail closed.
        if(remaining==null||remaining.isNegative()||remaining.toMillis()<1||Thread.currentThread().isInterrupted())throw CarbonCalculationDeadline.timeout();
        return remaining;
    }
    private static boolean matches(String value,String expected){return value!=null&&expected!=null&&expected.matches("[0-9a-f]{64}")&&hash(value).equals(expected);}
    private static String hash(String value){return CarbonCalculationCore.sha256(value);}
    static void budget(){var remaining=CarbonCalculationDeadline.currentRemaining();if(remaining!=null&&(remaining.isZero()||remaining.isNegative()||Thread.currentThread().isInterrupted()))throw CarbonCalculationDeadline.timeout();}
    private static RuntimeException integrity(){return error(409,"DAILY_STATIC_INTEGRITY_VIOLATION","Daily static frozen evidence integrity violation");}
}
