package com.platform.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只读检查共享库迁移后的对象与关键结构，不写入业务数据或修复数据库。 */
final class SharedAlignmentSchemaContract {
    private static final String RESOURCE = "db/shared-test-alignment.json";
    private static final Pattern CREATE = Pattern.compile(
            "(?i)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TEMPORARY\\s+)?(TABLE|VIEW|TRIGGER|PROCEDURE)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?((?:`[^`]+`|[\\w$]+)(?:\\s*\\.\\s*(?:`[^`]+`|[\\w$]+))?)");
    private static final Pattern DROP = Pattern.compile(
            "(?i)\\bDROP\\s+(?:TEMPORARY\\s+)?(TABLE|VIEW|TRIGGER|PROCEDURE)\\s+(?:IF\\s+EXISTS\\s+)?([^;]*?)(?:;|//)");
    private static final Pattern IDENTIFIER = Pattern.compile("(?:`([^`]+)`|([\\w$]+))");

    private SharedAlignmentSchemaContract() { }

    static void validate(JdbcTemplate jdbc, int target) {
        requireLedgerColumns(jdbc, target);
        var expected = expectedObjects(target);
        var actual = readObjects(jdbc);
        requireObjects(expected, actual);
        if (target >= 94) {
            requireColumn(jdbc, "building", "carbon_evaluation_category", null, null, "D01_V94_CLASSIFICATION_INCOMPLETE");
            requireColumn(jdbc, "building", "carbon_evaluation_subcategory", null, null, "D01_V94_CLASSIFICATION_INCOMPLETE");
        }
        if (target >= 97) {
            requireColumn(jdbc, "biz_energy_manual_batch", "reviewed_at", null, null, "D01_V97_REVIEWED_AT_INCOMPLETE");
        }
        if (target >= 100) {
            requireEvidenceCapacity(jdbc);
        }
        if (target >= 107) {
            requireColumn(jdbc, "biz_dynamic_interval_evidence", "snapshot_json", "mediumtext", "YES", "D01_V107_EVIDENCE_RETENTION_INCOMPLETE");
            requireColumn(jdbc, "biz_dynamic_interval_evidence", "snapshot_state", "varchar", "NO", "D01_V107_EVIDENCE_RETENTION_INCOMPLETE");
            if (jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_dynamic_interval_evidence'
                      AND COLUMN_NAME='snapshot_state' AND CHARACTER_MAXIMUM_LENGTH=8
                    """, Integer.class) != 1) {
                throw new IllegalStateException("D01_V107_EVIDENCE_RETENTION_INCOMPLETE");
            }
        }
        if (target >= 112) {
            requireCursorIndex(jdbc);
        }
        if (target >= 121) {
            requireForeignKey(jdbc, "fk_room_sensor_pending_source", "biz_room_sensor_ingest_pending", "source_id",
                    "biz_room_sensor_source", "source_id");
            requireForeignKey(jdbc, "fk_room_sensor_identity", "biz_room_sensor_source", "identity_id",
                    "biz_device_identity", "identity_id");
            var parent = jdbc.queryForMap("""
                    SELECT CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_device_identity' AND COLUMN_NAME='identity_id'
                    """);
            var child = jdbc.queryForMap("""
                    SELECT CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_room_sensor_source' AND COLUMN_NAME='identity_id'
                    """);
            if (!equalMetadata(parent, child, "CHARACTER_SET_NAME") || !equalMetadata(parent, child, "COLLATION_NAME")) {
                throw new IllegalStateException("D01_V121_IDENTITY_COLLATION_INCOMPLETE");
            }
        }
    }

    /** 除表存在外核验关键语义列，避免把同名但不兼容的旧候选表视为升级完成。 */
    private static void requireLedgerColumns(JdbcTemplate jdbc, int target) {
        if (target >= 122) {
            requireColumn(jdbc, "biz_ledger_type_rule", "professional_group", "varchar", "NO", "D01_V122_LEDGER_INCOMPLETE");
            requireColumn(jdbc, "biz_ledger_type_rule", "revision", "bigint", "NO", "D01_V122_LEDGER_INCOMPLETE");
            requireColumn(jdbc, "biz_ledger_config_audit", "after_json", "text", "NO", "D01_V122_LEDGER_INCOMPLETE");
        }
        if (target >= 123) {
            requireColumn(jdbc, "biz_device_comm_target", "report_delayed", "tinyint", "NO", "D01_V123_COMMUNICATION_INCOMPLETE");
            requireColumn(jdbc, "biz_device_comm_target", "scope_token", "char", "NO", "D01_V123_COMMUNICATION_INCOMPLETE");
            requireColumn(jdbc, "biz_device_comm_source", "lease_until", "bigint", "NO", "D01_V123_COMMUNICATION_INCOMPLETE");
        }
    }

    static void requireObjects(Set<ObjectRef> expected, Set<ObjectRef> actual) {
        Set<ObjectRef> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        if (!missing.isEmpty()) throw new IllegalStateException("D01_SHARED_SCHEMA_OBJECTS_MISSING");
    }

    static void requireEvidenceCapacity(JdbcTemplate jdbc) {
        for (String table : List.of("biz_energy_period_result_current", "biz_energy_period_result_snapshot")) {
            requireColumn(jdbc, table, "evidence_json", "mediumtext", "NO", "D01_V100_EVIDENCE_CAPACITY_INCOMPLETE");
        }
    }

    static void requireCursorIndex(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_energy_manual_reading'
                  AND INDEX_NAME='idx_energy_manual_cursor' ORDER BY SEQ_IN_INDEX
                """, String.class);
        if (!List.of("building_id", "start_inclusive", "fact_id", "end_exclusive").equals(columns)) {
            throw new IllegalStateException("D01_V112_CURSOR_INDEX_INCOMPLETE");
        }
    }

    static Set<ObjectRef> expectedObjects(int target) {
        if (target < 94) return Set.of();
        JsonNode manifest = readManifest();
        var objects = new LinkedHashMap<String, ObjectRef>();
        int previous = 93;
        for (JsonNode migration : manifest.path("migrations")) {
            int version = migration.path("version").asInt(-1);
            if (version > target) break;
            int expectedPrevious = version == 110 ? 107 : version - 1;
            if (previous != expectedPrevious) throw new IllegalStateException("D01_SHARED_ALIGNMENT_MIGRATIONS_INVALID");
            previous = version;
            String script = migration.path("script").asText("");
            if (!script.matches("V" + version + "__[A-Za-z0-9_.-]+\\.sql")) {
                throw new IllegalStateException("D01_SHARED_ALIGNMENT_SCRIPT_INVALID");
            }
            applySql(objects, readScript(script));
        }
        // 已部署的 V121 房间传感器及其清单保持原样；台账按新编号继续逐步升级。
        for (var entry : List.of(
                Map.entry(122, "V122__mysql_ledger_display_rules.sql"),
                Map.entry(123, "V123__mysql_device_communication.sql"))) {
            if (entry.getKey() > target) break;
            if (previous + 1 != entry.getKey()) throw new IllegalStateException("D01_SHARED_ALIGNMENT_MIGRATIONS_INVALID");
            applySql(objects, readScript(entry.getValue()));
            previous = entry.getKey();
        }
        if (previous < target) throw new IllegalStateException("D01_SHARED_ALIGNMENT_TARGET_NOT_COVERED");
        return Set.copyOf(objects.values());
    }

    private static JsonNode readManifest() {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            var root = new ObjectMapper().readTree(input);
            if (!"SHARED_ALIGNMENT_93_121_V1".equals(root.path("format").asText())
                    || root.path("source").asInt(-1) != 93 || root.path("target").asInt(-1) != 121
                    || !root.path("migrations").isArray()) {
                throw new IllegalStateException("D01_SHARED_ALIGNMENT_MANIFEST_INVALID");
            }
            return root;
        } catch (IOException ex) {
            throw new IllegalStateException("D01_SHARED_ALIGNMENT_MANIFEST_UNAVAILABLE", ex);
        }
    }

    private static String readScript(String name) {
        try (var input = new ClassPathResource("db/migration/mysql/" + name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("D01_SHARED_ALIGNMENT_MIGRATION_UNAVAILABLE_" + name, ex);
        }
    }

    private static void applySql(Map<String, ObjectRef> objects, String sql) {
        String uncommented = sql.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*--.*$", "");
        var creates = CREATE.matcher(uncommented);
        // 按源码顺序回放对象声明，排除在同一迁移末尾已经删除的临时过程。
        var operations = new ArrayList<SqlObjectOperation>();
        while (creates.find()) operations.add(new SqlObjectOperation(creates.start(), true, creates.group(1), creates.group(2)));
        var drops = DROP.matcher(uncommented);
        while (drops.find()) {
            String kind = drops.group(1);
            Matcher names = IDENTIFIER.matcher(drops.group(2));
            while (names.find()) operations.add(new SqlObjectOperation(drops.start(), false, kind,
                    names.group(1) != null ? names.group(1) : names.group(2)));
        }
        operations.sort((left, right) -> Integer.compare(left.position(), right.position()));
        var preScript = new LinkedHashMap<String, ObjectRef>(objects);
        for (var operation : operations) {
            var ref = ref(operation.kind(), operation.name());
            if (operation.create()) preScript.put(ref.key(), ref);
            else preScript.remove(ref.key());
        }
        objects.clear();
        objects.putAll(preScript);
    }

    private static ObjectRef ref(String kind, String qualifiedName) {
        Matcher matcher = IDENTIFIER.matcher(qualifiedName.trim());
        String name = null;
        while (matcher.find()) name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (name == null) throw new IllegalStateException("D01_SHARED_ALIGNMENT_OBJECT_NAME_INVALID");
        return new ObjectRef(kind.toUpperCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
    }

    private static Set<ObjectRef> readObjects(JdbcTemplate jdbc) {
        var result = new LinkedHashSet<ObjectRef>();
        for (var row : jdbc.queryForList("SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()")) {
            String type = value(row, "TABLE_TYPE");
            result.add(new ObjectRef("VIEW".equalsIgnoreCase(type) ? "VIEW" : "TABLE", value(row, "TABLE_NAME").toLowerCase(Locale.ROOT)));
        }
        for (var row : jdbc.queryForList("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()")) {
            result.add(new ObjectRef("TRIGGER", value(row, "TRIGGER_NAME").toLowerCase(Locale.ROOT)));
        }
        for (var row : jdbc.queryForList("SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE()")) {
            if ("PROCEDURE".equalsIgnoreCase(value(row, "ROUTINE_TYPE"))) {
                result.add(new ObjectRef("PROCEDURE", value(row, "ROUTINE_NAME").toLowerCase(Locale.ROOT)));
            }
        }
        return result;
    }

    private static String value(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).filter(java.util.Objects::nonNull)
                .map(String::valueOf).findFirst().orElse("");
    }

    private static void requireColumn(JdbcTemplate jdbc, String table, String column, String type, String nullable, String error) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?");
        var args = new ArrayList<Object>(List.of(table, column));
        if (type != null) { sql.append(" AND DATA_TYPE=?"); args.add(type); }
        if (nullable != null) { sql.append(" AND IS_NULLABLE=?"); args.add(nullable); }
        if (jdbc.queryForObject(sql.toString(), Integer.class, args.toArray()) != 1) throw new IllegalStateException(error);
    }

    static void requireForeignKey(JdbcTemplate jdbc, String constraint, String table, String column, String parentTable, String parentColumn) {
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
                WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME=? AND TABLE_NAME=? AND COLUMN_NAME=?
                  AND REFERENCED_TABLE_NAME=? AND REFERENCED_COLUMN_NAME=?
                """, Integer.class, constraint, table, column, parentTable, parentColumn) != 1) {
            throw new IllegalStateException("D01_V121_FOREIGN_KEYS_INCOMPLETE");
        }
    }

    private static boolean equalMetadata(Map<String, Object> left, Map<String, Object> right, String key) {
        String first = value(left, key);
        return !first.isBlank() && first.equalsIgnoreCase(value(right, key));
    }

    record ObjectRef(String kind, String name) {
        String key() { return kind + ":" + name; }
    }

    private record SqlObjectOperation(int position, boolean create, String kind, String name) { }
}
