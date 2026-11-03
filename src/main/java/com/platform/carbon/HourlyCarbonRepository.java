package com.platform.carbon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.platform.carbon.CarbonErrors.VALIDATION_FAILED;
import static com.platform.carbon.CarbonErrors.error;

/**
 * 小时结果的不可变持久化边界；不读取上游事实、不参与计算，写入时仅串行分配当天修订。
 *
 * <p>同一建筑日期的范围行在写入期间持锁，审计与批次保存同事务失败，避免留下无审计的结果。</p>
 */
@Repository
public class HourlyCarbonRepository {
    private static final int MAX_EVIDENCE_BYTES = 16 * 1024 * 1024;
    private static final String INTEGRITY_VIOLATION = "HOURLY_CARBON_INTEGRITY_VIOLATION";
    private static final String IDEMPOTENCY_CONFLICT = "HOURLY_CARBON_IDEMPOTENCY_CONFLICT";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    public HourlyCarbonRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                  ObjectMapper mapper,
                                  AuditEvidenceWriter auditWriter,
                                  AuditGovernanceProperties auditProperties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.auditWriter = auditWriter;
        this.auditProperties = auditProperties;
    }

    public record Stored(HourlyCarbonContracts.View view, String evidenceJson,
                         @com.fasterxml.jackson.annotation.JsonIgnore JsonNode parsedEvidence) {
        public Stored(HourlyCarbonContracts.View view, String evidenceJson) {
            this(view, evidenceJson, null);
        }
        /** 解析树只属于本次读取；复用已经完成哈希及规范性校验的内容，不建立跨请求缓存。 */
        public JsonNode evidenceTree() {
            return parsedEvidence == null ? CarbonEvidence.parse(evidenceJson) : parsedEvidence;
        }
    }

    public Stored byKey(String building, String key) {
        return stored(one("""
                SELECT * FROM biz_carbon_hourly_batch
                WHERE building_id=? AND idempotency_key=?
                """, building, key));
    }

    /** 幂等回放必须绑定原请求摘要，不能仅凭建筑和幂等键读取其他请求的结果。 */
    public Stored replay(String building, String key, String requestHash) {
        if (blank(building) || building.length() > 32 || blank(key) || key.length() > 100) {
            throw invalid("小时碳结果幂等身份无效");
        }
        validateRequestHash(requestHash);
        Row existing = one("""
                SELECT * FROM biz_carbon_hourly_batch
                WHERE building_id=? AND idempotency_key=?
                """, building, key);
        return replay(existing, requestHash);
    }

    public Stored byId(String batchId) {
        return stored(one("SELECT * FROM biz_carbon_hourly_batch WHERE batch_id=?", batchId));
    }

    public Stored byDate(String building, LocalDate date, Integer revision) {
        if (revision == null) {
            return stored(one("""
                    SELECT * FROM biz_carbon_hourly_batch
                    WHERE building_id=? AND day_date=?
                    ORDER BY calculation_as_of DESC, revision DESC
                    LIMIT 1
                    """, building, java.sql.Date.valueOf(date)));
        }
        return stored(one("""
                SELECT * FROM biz_carbon_hourly_batch
                WHERE building_id=? AND day_date=? AND revision=?
                """, building, java.sql.Date.valueOf(date), revision));
    }

    @Transactional(rollbackFor = Exception.class)
    public Stored publish(long user, HourlyCarbonContracts.Request request, String requestHash,
                          HourlyCarbonContracts.View draft, String evidenceJson) {
        validateRequest(request, requestHash);
        // 数据库/审计忽略中断时，提交回调再次核对截止；物理提交结果不确定时仍以幂等查询确认。
        var remaining=CarbonCalculationDeadline.currentRemaining();
        if(remaining!=null) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) {
                            var budget=CarbonCalculationDeadline.currentRemaining();
                            if(budget!=null && budget.isZero())throw CarbonCalculationDeadline.timeout();
                        }
                    });
        }

        int currentRevision = lockScope(request.buildingId(), request.date());
        Row existing = one("""
                SELECT * FROM biz_carbon_hourly_batch
                WHERE building_id=? AND idempotency_key=? FOR UPDATE
                """, request.buildingId(), request.idempotencyKey());
        if (existing != null) {
            return replay(existing, requestHash);
        }

        // 重放已在锁内返回；只有新批次才处理受16MiB上限约束的本地 JSON 证据。
        PreparedEvidence evidence = prepareEvidence(draft, evidenceJson);
        int revision = currentRevision + 1;
        String batchId = UUID.randomUUID().toString().replace("-", "");
        HourlyCarbonContracts.View view = HourlyCarbonContracts.identity(draft, batchId, revision);
        validateViewIdentity(request, view, evidence.hash());
        String viewJson = canonicalJson(view);
        String viewHash = immutableHash(viewJson);
        long now = Instant.now().toEpochMilli();

        jdbc.update("""
                INSERT INTO biz_carbon_hourly_batch
                (batch_id,building_id,day_date,revision,idempotency_key,request_hash,calculation_as_of,
                 view_json,view_hash,evidence_json,evidence_hash,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, batchId, request.buildingId(), java.sql.Date.valueOf(request.date()), revision,
                request.idempotencyKey(), requestHash, request.calculationAsOf().toEpochMilli(), viewJson,
                viewHash, evidence.json(), evidence.hash(), user, now);
        if (jdbc.update("""
                UPDATE biz_carbon_hourly_scope SET revision=?
                WHERE building_id=? AND day_date=? AND revision=?
                """, revision, request.buildingId(), java.sql.Date.valueOf(request.date()), currentRevision) != 1) {
            throw new IllegalStateException("小时碳结果范围修订已变化");
        }
        auditWriter.append(new AuditEvidence("CARBON_MANAGEMENT", request.buildingId(), "USER", user,
                "CREATE_HOURLY_CARBON_BATCH", "HOURLY_CARBON_BATCH", batchId,
                Integer.toString(revision), null, null,
                "date=" + request.date() + ";calculationAsOf=" + request.calculationAsOf().toEpochMilli()
                        + ";requestHash=" + requestHash + ";viewHash=" + viewHash
                        + ";evidenceHash=" + evidence.hash(),
                "SUCCESS", null, TraceContext.current(), LocalDateTime.now(),
                auditProperties.getEnvironmentMode(), false));
        return new Stored(view, evidence.json());
    }

    private int lockScope(String building, LocalDate date) {
        jdbc.update("""
                INSERT INTO biz_carbon_hourly_scope(building_id,day_date,revision)
                VALUES (?,?,0) ON DUPLICATE KEY UPDATE revision=revision
                """, building, java.sql.Date.valueOf(date));
        Integer revision = jdbc.queryForObject("""
                SELECT revision FROM biz_carbon_hourly_scope
                WHERE building_id=? AND day_date=? FOR UPDATE
                """, Integer.class, building, java.sql.Date.valueOf(date));
        if (revision == null) {
            throw new IllegalStateException("小时碳结果范围锁未建立");
        }
        return revision;
    }

    private PreparedEvidence prepareEvidence(HourlyCarbonContracts.View draft, String evidenceJson) {
        if (draft == null) {
            throw invalid("小时碳结果视图不能为空");
        }
        if (evidenceJson == null || evidenceJson.getBytes(StandardCharsets.UTF_8).length > MAX_EVIDENCE_BYTES) {
            throw invalid("小时碳证据不能为空且不能超过16MiB");
        }
        try {
            JsonNode parsed = CarbonEvidence.parse(evidenceJson);
            if (parsed == null) {
                throw invalid("小时碳证据必须是JSON");
            }
            String canonical = CarbonEvidence.canonical(parsed);
            if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_EVIDENCE_BYTES) {
                throw invalid("小时碳证据不能超过16MiB");
            }
            String hash = immutableHash(canonical);
            if (!hash.equals(draft.evidenceHash())) {
                throw invalid("小时碳视图与证据摘要不一致");
            }
            return new PreparedEvidence(canonical, hash);
        } catch (IllegalStateException exception) {
            throw invalid("小时碳证据必须是JSON");
        }
    }

    private void validateRequest(HourlyCarbonContracts.Request request, String requestHash) {
        if (request == null || blank(request.buildingId()) || request.buildingId().length() > 32
                || request.date() == null || request.calculationAsOf() == null
                || blank(request.idempotencyKey()) || request.idempotencyKey().length() > 100) {
            throw invalid("小时碳结果请求身份无效");
        }
        validateRequestHash(requestHash);
    }

    private void validateRequestHash(String requestHash) {
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw invalid("小时碳结果请求摘要无效");
        }
    }

    private void validateViewIdentity(HourlyCarbonContracts.Request request,
                                      HourlyCarbonContracts.View view, String evidenceHash) {
        if (!Objects.equals(request.buildingId(), view.buildingId())
                || !Objects.equals(request.date(), view.date())
                || !Objects.equals(request.calculationAsOf(), view.calculationAsOf())
                || !Objects.equals(evidenceHash, view.evidenceHash())) {
            throw invalid("小时碳结果视图身份或证据摘要不一致");
        }
    }

    private Stored stored(Row row) {
        if (row == null) {
            return null;
        }
        if (!hashMatches(row.viewJson(), row.viewHash()) || !hashMatches(row.evidenceJson(), row.evidenceHash())) {
            throw integrity();
        }
        try {
            JsonNode evidence = CarbonEvidence.parse(row.evidenceJson());
            if (evidence == null || !row.evidenceJson().equals(CarbonEvidence.canonical(evidence))) {
                throw integrity();
            }
            HourlyCarbonContracts.View view = mapper.readValue(row.viewJson(), HourlyCarbonContracts.View.class);
            if (!row.viewJson().equals(canonicalJson(view))
                    || !Objects.equals(row.buildingId(), view.buildingId())
                    || !Objects.equals(row.dayDate(), view.date())
                    || !Objects.equals(row.batchId(), view.calculationBatchId())
                    || !Objects.equals(row.revision(), view.revision())
                    || !Objects.equals(Instant.ofEpochMilli(row.calculationAsOf()), view.calculationAsOf())
                    || !Objects.equals(row.evidenceHash(), view.evidenceHash())) {
                throw integrity();
            }
            return new Stored(view, row.evidenceJson(), evidence);
        } catch (JsonProcessingException | IllegalArgumentException | IllegalStateException exception) {
            throw integrity();
        }
    }

    private Stored replay(Row existing, String requestHash) {
        if (existing == null) {
            return null;
        }
        if (!requestHash.equals(existing.requestHash())) {
            throw error(409, IDEMPOTENCY_CONFLICT, "幂等键已用于不同的小时碳结果请求");
        }
        return stored(existing);
    }

    private Row one(String sql, Object... values) {
        List<Row> rows = jdbc.query(sql, this::row, values);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Row row(ResultSet result, int ignored) throws SQLException {
        return new Row(result.getString("batch_id"), result.getString("building_id"),
                result.getDate("day_date").toLocalDate(), result.getInt("revision"),
                result.getString("request_hash"), result.getLong("calculation_as_of"),
                result.getString("view_json"), result.getString("view_hash"),
                result.getString("evidence_json"), result.getString("evidence_hash"));
    }

    private String canonicalJson(Object value) {
        try {
            return writeCanonical(mapper.valueToTree(value));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("小时碳结果无法规范化序列化", exception);
        }
    }

    private String writeCanonical(JsonNode value) throws JsonProcessingException {
        return mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(canonicalNode(value));
    }

    private JsonNode canonicalNode(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                sorted.set(name, canonicalNode(value.get(name)));
            }
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode normalized = mapper.createArrayNode();
            for (JsonNode item : value) {
                normalized.add(canonicalNode(item));
            }
            return normalized;
        }
        return value;
    }

    private static boolean hashMatches(String content, String expectedHash) {
        return content != null && expectedHash != null && expectedHash.matches("[0-9a-f]{64}")
                && expectedHash.equals(immutableHash(content));
    }

    private static String immutableHash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static RuntimeException invalid(String message) {
        return error(400, VALIDATION_FAILED, message);
    }

    private static RuntimeException integrity() {
        return error(409, INTEGRITY_VIOLATION, "已保存的小时碳结果完整性校验失败");
    }

    private record PreparedEvidence(String json, String hash) {
    }

    private record Row(String batchId, String buildingId, LocalDate dayDate, int revision,
                       String requestHash, long calculationAsOf, String viewJson, String viewHash,
                       String evidenceJson, String evidenceHash) {
    }
}
