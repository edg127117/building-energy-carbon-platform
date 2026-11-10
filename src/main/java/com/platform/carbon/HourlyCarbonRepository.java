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
import java.time.Duration;
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
    private static final int MAX_COMPRESSED_VIEW_BYTES = 16 * 1024 * 1024;
    private static final String INTEGRITY_VIOLATION = "HOURLY_CARBON_INTEGRITY_VIOLATION";
    private static final String IDEMPOTENCY_CONFLICT = "HOURLY_CARBON_IDEMPOTENCY_CONFLICT";

    // Opt-in transport only: the authoritative LONGTEXT is read and verified on every request.
    @org.springframework.beans.factory.annotation.Value("${carbon.hourly.read-compression:false}")
    private boolean readCompression;

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
        boolean timed = CarbonCalculationDeadline.currentRemaining() != null;
        ReadMetrics metrics = timed ? new ReadMetrics() : null;
        boolean completed = false;
        long jdbcStarted = System.nanoTime(), jdbcNanos = 0, verificationStarted = 0;
        Row row = null;
        try {
            try {
                row = revision == null ? oneDaily("""
                        SELECT * FROM biz_carbon_hourly_batch
                        WHERE building_id=? AND day_date=?
                        ORDER BY calculation_as_of DESC, revision DESC
                        LIMIT 1
                        """, metrics, building, java.sql.Date.valueOf(date)) : oneDaily("""
                        SELECT * FROM biz_carbon_hourly_batch
                        WHERE building_id=? AND day_date=? AND revision=?
                        """, metrics, building, java.sql.Date.valueOf(date), revision);
            } finally {
                jdbcNanos = System.nanoTime() - jdbcStarted;
            }
            verificationStarted = System.nanoTime();
            Stored result = stored(row);
            if (timed) requireReadBudget();
            completed = true;
            return result;
        } finally {
            if (timed) {
                long verificationMillis = verificationStarted == 0 ? -1 : (System.nanoTime() - verificationStarted) / 1_000_000;
                // One event per requested day; evidence contents and hashes never enter timing diagnostics.
                org.slf4j.LoggerFactory.getLogger(HourlyCarbonRepository.class).info(
                        "Hourly carbon read building={} date={} jdbcMs={} verificationMs={} viewChars={} evidenceChars={} readCompression={} outcome={} metrics={}",
                        building, date, jdbcNanos / 1_000_000, verificationMillis,
                        row == null || row.viewJson() == null ? 0 : row.viewJson().length(),
                        row == null || row.evidenceJson() == null ? 0 : row.evidenceJson().length(), readCompression,
                        completed ? (row == null ? "EMPTY" : "COMPLETE") : "FAILED", metrics);
            }
        }
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
            if (evidence == null || !CarbonEvidence.canonicalEquals(evidence,row.evidenceJson())) {
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

    private Row oneDaily(String sql, ReadMetrics metrics, Object... values) {
        if (metrics != null) metrics.projection = readCompression ? "COMPRESSED" : "RAW";
        if (!readCompression) return oneMapped(sql, false, metrics, values);
        // Preserve the selector and both authoritative bodies. The view has no publication size cap:
        // only originally oversized views use the legacy raw path; a bad compressed body never does.
        String viewBytes = "OCTET_LENGTH(CONVERT(view_json USING utf8mb4))";
        String projection = "SELECT batch_id,building_id,day_date,revision,request_hash,calculation_as_of,"
                + viewBytes + " AS view_utf8_bytes,CASE WHEN " + viewBytes + "<=" + MAX_COMPRESSED_VIEW_BYTES
                + " THEN COMPRESS(CONVERT(view_json USING utf8mb4)) END AS view_compressed,"
                + "CASE WHEN " + viewBytes + ">" + MAX_COMPRESSED_VIEW_BYTES + " THEN view_json END AS view_json,"
                + "view_hash,COMPRESS(CONVERT(evidence_json USING utf8mb4)) AS evidence_compressed,evidence_hash";
        return oneMapped(sql.replace("SELECT *", projection), true, metrics, values);
    }

    private Row one(String sql, Object... values) {
        return oneMapped(sql, false, null, values);
    }

    private Row oneMapped(String sql, boolean compressed, ReadMetrics metrics, Object... values) {
        org.springframework.jdbc.core.RowMapper<Row> rowMapper = (result, index) -> {
            if (metrics != null) metrics.rowReached();
            Row row = row(result, compressed, metrics);
            if (metrics != null) metrics.mappedRows++;
            return row;
        };
        List<Row> rows;
        if (CarbonCalculationDeadline.currentRemaining() == null) {
            rows = jdbc.query(sql, rowMapper, values);
        } else {
            requireReadBudget();
            if (metrics != null) metrics.queryEntered();
            rows = jdbc.query(connection -> {
                if (metrics != null) metrics.prepareCallbackReached();
                // Connection acquisition consumes the same request budget; never reuse a pre-acquisition timeout.
                Duration remaining = requireReadBudget();
                String select = sql.stripLeading();
                if (!select.startsWith("SELECT ")) throw new IllegalArgumentException("Hourly read requires SELECT");
                long millis = Math.min(Integer.MAX_VALUE, remaining.toMillis());
                return connection.prepareStatement("SELECT /*+ MAX_EXECUTION_TIME(" + millis + ") */ "
                        + select.substring("SELECT ".length()));
            }, statement -> {
                // This setter runs after JdbcTemplate settings and must not enlarge a shorter transaction timeout.
                Duration remaining = requireReadBudget();
                int existing = statement.getQueryTimeout();
                int requested = CarbonCalculationDeadline.timeoutSeconds(remaining);
                statement.setQueryTimeout(existing > 0 ? Math.min(existing, requested) : requested);
                for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
                if (metrics != null) metrics.statementReady();
            }, new org.springframework.jdbc.core.RowMapperResultSetExtractor<>(rowMapper));
            if (metrics != null) metrics.jdbcReturned = true;
            requireReadBudget();
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Duration requireReadBudget() {
        Duration remaining = CarbonCalculationDeadline.currentRemaining();
        // Sub-millisecond budgets fail closed: MAX_EXECUTION_TIME(0) would disable the server-side limit.
        if (remaining == null || remaining.isNegative() || remaining.toMillis() < 1 || Thread.currentThread().isInterrupted()) {
            throw CarbonCalculationDeadline.timeout();
        }
        return remaining;
    }

    private Row row(ResultSet result, boolean compressed) throws SQLException {
        return row(result, compressed, null);
    }

    private Row row(ResultSet result, boolean compressed, ReadMetrics metrics) throws SQLException {
        return new Row(result.getString("batch_id"), result.getString("building_id"),
                result.getDate("day_date").toLocalDate(), result.getInt("revision"),
                result.getString("request_hash"), result.getLong("calculation_as_of"),
                readView(result, compressed, metrics), result.getString("view_hash"),
                readEvidence(result, compressed, metrics),
                result.getString("evidence_hash"));
    }

    private static String compressedView(ResultSet result) throws SQLException {
        return compressedView(result, null);
    }

    private static String compressedView(ResultSet result, ReadMetrics metrics) throws SQLException {
        // Source UTF-8 bytes, not Java character counts, select the bounded transport.
        long utf8Bytes = result.getLong("view_utf8_bytes");
        if (result.wasNull() || utf8Bytes < 0) throw integrity();
        if (utf8Bytes > MAX_COMPRESSED_VIEW_BYTES) {
            if (metrics != null) metrics.viewBranch = "OVERSIZED_RAW";
            return result.getString("view_json");
        }
        if (metrics != null) metrics.viewBranch = "COMPRESSED";
        String view = decodeText(frame(result, "view_compressed", metrics), MAX_COMPRESSED_VIEW_BYTES, metrics);
        if (view == null || view.getBytes(StandardCharsets.UTF_8).length != utf8Bytes) throw integrity();
        return view;
    }

    private static String readView(ResultSet result, boolean compressed, ReadMetrics metrics) throws SQLException {
        if (compressed) return compressedView(result, metrics);
        if (metrics != null) metrics.viewBranch = "RAW";
        return result.getString("view_json");
    }

    private static String readEvidence(ResultSet result, boolean compressed, ReadMetrics metrics) throws SQLException {
        if (metrics != null) metrics.evidenceBranch = compressed ? "COMPRESSED" : "RAW";
        return compressed ? decodeText(frame(result, "evidence_compressed", metrics), MAX_EVIDENCE_BYTES, metrics)
                : result.getString("evidence_json");
    }

    private static byte[] frame(ResultSet result, String column, ReadMetrics metrics) throws SQLException {
        if (metrics == null) return result.getBytes(column);
        metrics.getBytesCalls++;
        long started = System.nanoTime();
        boolean returned = false;
        try {
            byte[] bytes = result.getBytes(column);
            returned = true;
            if (bytes == null) metrics.nullFrames++;
            else { metrics.frames++; metrics.frameBytes += bytes.length; }
            return bytes;
        } finally {
            metrics.getBytesNanos += System.nanoTime() - started;
            if (!returned) metrics.getBytesFailures++;
        }
    }

    private static String decodeText(byte[] compressed, int maximumBytes, ReadMetrics metrics) {
        long started = metrics == null ? 0 : System.nanoTime();
        if (metrics != null) metrics.decodeCalls++;
        boolean decoded = false;
        try {
            String result = MysqlCompressedText.decode(compressed, maximumBytes);
            decoded = true;
            return result;
        } catch (IllegalArgumentException exception) {
            throw integrity();
        } finally {
            if (metrics != null) {
                metrics.decodeNanos += System.nanoTime() - started;
                if (!decoded) metrics.decodeFailures++;
            }
        }
    }

    /** Per-day counters only; appended to the existing one-line event, never a new event or ThreadLocal. */
    private static final class ReadMetrics {
        String projection = "NOT_SELECTED", viewBranch = "NOT_REACHED", evidenceBranch = "NOT_REACHED";
        boolean queryEntered, prepareReached, statementReady, firstRowReached, jdbcReturned;
        long queryAt, prepareAt, readyAt, firstRowAt, getBytesNanos, decodeNanos, frameBytes;
        int rowCallbacks, mappedRows, getBytesCalls, getBytesFailures, decodeCalls, decodeFailures, frames, nullFrames;

        void queryEntered() { queryEntered = true; queryAt = System.nanoTime(); }
        void prepareCallbackReached() { prepareReached = true; prepareAt = System.nanoTime(); }
        void statementReady() { statementReady = true; readyAt = System.nanoTime(); }
        void rowReached() {
            rowCallbacks++;
            if (!firstRowReached) { firstRowReached = true; firstRowAt = System.nanoTime(); }
        }
        private static long millis(boolean reached, long nanos) { return reached ? Math.max(0, nanos) / 1_000_000 : -1; }

        @Override public String toString() {
            // Spans overlap jdbcMs and each other; do not add them. Entry->prepare covers acquisition/transaction binding/framework scheduling.
            // Entry->ready also covers statement preparation/parameter binding; ready->first covers execute/server/driver buffering/next.
            // getBytes may only copy a driver buffer; decode includes inflate/UTF-8/copies. Frame bytes are not wire totals.
            return "projection=" + projection + ",viewBranch=" + viewBranch + ",evidenceBranch=" + evidenceBranch
                    + ",queryEntered=" + queryEntered + ",prepareReached=" + prepareReached + ",statementReady=" + statementReady
                    + ",firstRowReached=" + firstRowReached + ",jdbcReturned=" + jdbcReturned
                    + ",queryEntryToPrepareCallbackMs=" + millis(queryEntered && prepareReached, prepareAt - queryAt)
                    + ",queryEntryToStatementReadyMs=" + millis(queryEntered && statementReady, readyAt - queryAt)
                    + ",statementReadyToFirstRowMs=" + millis(statementReady && firstRowReached, firstRowAt - readyAt)
                    + ",rowCallbacks=" + rowCallbacks + ",mappedRows=" + mappedRows
                    + ",getBytesMs=" + millis(getBytesCalls > 0, getBytesNanos) + ",getBytesCalls=" + getBytesCalls + ",getBytesFailures=" + getBytesFailures
                    + ",compressedDecodeMs=" + millis(decodeCalls > 0, decodeNanos) + ",decodeCalls=" + decodeCalls + ",decodeFailures=" + decodeFailures
                    + ",frameBytes=" + (frames + nullFrames > 0 ? frameBytes : -1) + ",frames=" + frames + ",nullFrames=" + nullFrames;
        }
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
