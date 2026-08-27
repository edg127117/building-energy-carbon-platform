package com.platform.audit.capacity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEnvironmentMode;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.AuditSummarySanitizer;
import com.platform.audit.JdbcSecurityAuditEvidenceWriter;
import com.platform.audit.query.AuditEvidenceProvider;
import com.platform.audit.query.AuditEvidenceProviderConfiguration;
import com.platform.audit.query.AuditQueryCursor;
import com.platform.audit.query.AuditQueryFilter;
import com.platform.audit.query.AuditQueryPage;
import com.platform.audit.query.AuditQueryService;
import com.platform.audit.retention.AuditCleanupBatch;
import com.platform.audit.retention.AuditRetentionHandler;
import com.platform.audit.retention.AuditRetentionHandlerConfiguration;
import com.platform.audit.retention.AuditRetentionPreview;
import com.platform.audit.retention.AuditRetentionScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "AUDIT_CAPACITY_MYSQL_URL", matches = ".+")
class AuditCapacityMysqlIntegrationTest {
    private static final int BASELINE_EVENTS = 100_000;
    private static final int SECURITY_BATCH_EVENTS = 39_900;
    private static final int APPLICATION_WRITER_EVENTS = 100;
    private static final int DOMAIN_EVENTS = 12_000;
    private static final int CLEANUP_BATCH_SIZE = 500;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2025, 1, 1, 0, 0);
    private static final LocalDateTime WINDOW_END = WINDOW_START.plusDays(1);
    private static final List<AuditTable> TABLES = List.of(
            new AuditTable("SYSTEM_SECURITY", "sys_security_audit_event", "S", SECURITY_BATCH_EVENTS,
                    List.of("idx_security_audit_building_time"), "idx_security_audit_trace",
                    "idx_security_audit_retention", InsertShape.SYSTEM),
            new AuditTable("COLLECTION", "biz_collection_config_audit_log", "C", DOMAIN_EVENTS,
                    List.of("idx_collection_audit_public_building", "idx_collection_audit_building_time"),
                    "idx_collection_audit_public_trace",
                    "idx_collection_audit_retention", InsertShape.COMMON),
            new AuditTable("QUALITY_USAGE", "biz_quality_usage_audit_log", "Q", DOMAIN_EVENTS,
                    List.of("idx_quality_usage_audit_public_building", "idx_quality_usage_audit_building_time"),
                    "idx_quality_usage_audit_public_trace",
                    "idx_quality_usage_audit_retention", InsertShape.COMMON),
            new AuditTable("DEVICE_PARAMETER", "biz_device_parameter_audit_log", "D", DOMAIN_EVENTS,
                    List.of("idx_device_parameter_audit_public_building", "idx_device_parameter_audit_building_time"),
                    "idx_device_parameter_audit_public_trace",
                    "idx_device_parameter_audit_retention", InsertShape.COMMON),
            new AuditTable("ONBOARDING", "biz_onboarding_audit_log", "O", DOMAIN_EVENTS,
                    List.of("idx_onboarding_audit_public_building"), "idx_onboarding_audit_public_trace",
                    "idx_onboarding_audit_retention", InsertShape.COMMON),
            new AuditTable("RELATION", "biz_relation_audit_log", "R", DOMAIN_EVENTS,
                    List.of("idx_relation_audit_public_building", "idx_relation_audit_building_time"),
                    "idx_relation_audit_public_trace",
                    "idx_relation_audit_retention", InsertShape.RELATION));

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void validatesOneHundredThousandDailyEventsWithoutDroppingTheNextCriticalEvent() throws Exception {
        DataSource dataSource = isolatedDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                Integer.class)).as("容量测试数据库必须为空").isZero();

        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/env/init").load();
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        insertSyntheticBuilding(jdbc);

        long preexistingMigrationRows = totalRows(jdbc);
        StorageSize emptySize = storageSize(jdbc);
        long seedStarted = System.nanoTime();
        for (AuditTable table : TABLES) {
            insertBatched(dataSource, table);
        }

        JdbcSecurityAuditEvidenceWriter writer = new JdbcSecurityAuditEvidenceWriter(
                jdbc, new AuditSummarySanitizer());
        List<Long> writerMicros = appendThroughApplicationWriter(writer);
        long seedMillis = elapsedMillis(seedStarted);
        assertThat(syntheticRows(jdbc)).isEqualTo(BASELINE_EVENTS);
        assertThat(totalRows(jdbc)).isEqualTo(preexistingMigrationRows + BASELINE_EVENTS);

        writer.append(evidence("capacity-over-baseline", WINDOW_START.plusHours(23).plusMinutes(59)));
        assertThat(syntheticRows(jdbc))
                .as("10万条是扩容触发线，不能成为第100001条关键证据的丢弃阈值")
                .isEqualTo(BASELINE_EVENTS + 1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE trace_id='capacity-over-baseline'
                """, Long.class)).isOne();

        analyzeAuditTables(jdbc);
        StorageSize populatedSize = storageSize(jdbc);
        assertThat(populatedSize.dataBytes()).isPositive();
        assertThat(populatedSize.indexBytes()).isPositive();

        Map<String, Long> queryMillis = new LinkedHashMap<>();
        Map<String, String> queryIndexes = new LinkedHashMap<>();
        long cleanupMillis;
        int cleanupBatches;
        long deletedRows;
        try (AnnotationConfigApplicationContext context = auditContext(jdbc)) {
            List<AuditEvidenceProvider> providers = context.getBeansOfType(AuditEvidenceProvider.class)
                    .values().stream().sorted(Comparator.comparing(AuditEvidenceProvider::sourceModule)).toList();
            AuditQueryService queryService = new AuditQueryService(
                    providers, null, null, null, new AuditGovernanceProperties());
            verifyQueries(queryService, queryMillis);
            queryIndexes.putAll(verifyIndexPlans(jdbc));

            insertHolds(jdbc);
            List<AuditRetentionHandler> handlers = context.getBeansOfType(AuditRetentionHandler.class)
                    .values().stream().sorted(Comparator.comparing(AuditRetentionHandler::sourceModule)).toList();
            CleanupMeasurement cleanup = cleanupAll(dataSource, handlers);
            cleanupMillis = cleanup.elapsedMillis();
            cleanupBatches = cleanup.batches();
            deletedRows = cleanup.deletedRows();
        }

        assertThat(deletedRows).isEqualTo(BASELINE_EVENTS + 1L - TABLES.size());
        assertThat(syntheticRows(jdbc)).isEqualTo(TABLES.size());
        assertThat(totalRows(jdbc)).isEqualTo(preexistingMigrationRows + TABLES.size());
        for (AuditTable table : TABLES) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table.tableName()
                    + " WHERE audit_id=?", Long.class, auditId(table, 0))).isOne();
        }

        analyzeAuditTables(jdbc);
        StorageSize afterDeleteSize = storageSize(jdbc);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("serverVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        evidence.put("preexistingMigrationAuditRows", preexistingMigrationRows);
        evidence.put("baselineEvents", BASELINE_EVENTS);
        evidence.put("acceptedEvents", BASELINE_EVENTS + 1);
        evidence.put("seedMillis", seedMillis);
        evidence.put("applicationWriterP95Micros", percentile(writerMicros, 95));
        evidence.put("queryMillis", queryMillis);
        evidence.put("queryIndexes", queryIndexes);
        evidence.put("emptyAllocatedBytes", emptySize.allocatedBytes());
        evidence.put("populatedDataBytes", populatedSize.dataBytes());
        evidence.put("populatedIndexBytes", populatedSize.indexBytes());
        evidence.put("allocatedBytesPerEvent", Math.max(1,
                (populatedSize.allocatedBytes() - emptySize.allocatedBytes()) / (BASELINE_EVENTS + 1L)));
        evidence.put("cleanupBatchSize", CLEANUP_BATCH_SIZE);
        evidence.put("cleanupBatches", cleanupBatches);
        evidence.put("cleanupDeletedRows", deletedRows);
        evidence.put("cleanupMillis", cleanupMillis);
        evidence.put("remainingHeldRows", syntheticRows(jdbc));
        evidence.put("allocatedBytesAfterDelete", afterDeleteSize.allocatedBytes());
        System.out.println("AUDIT_CAPACITY_EVIDENCE=" + new ObjectMapper().writeValueAsString(evidence));
    }

    private static DataSource isolatedDataSource() {
        String url = System.getenv("AUDIT_CAPACITY_MYSQL_URL");
        assertThat(System.getenv("AUDIT_CAPACITY_MYSQL_ISOLATED"))
                .as("容量测试必须显式确认使用一次性隔离 MySQL").isEqualTo("true");
        assertThat(url).as("容量测试固定使用项目数据库名").contains("/iot_platform");
        String separator = url.contains("?") ? "&" : "?";
        return new DriverManagerDataSource(url + separator
                + "rewriteBatchedStatements=true&useServerPrepStmts=false",
                System.getenv().getOrDefault("AUDIT_CAPACITY_MYSQL_USER", "root"),
                System.getenv().getOrDefault("AUDIT_CAPACITY_MYSQL_PASSWORD", "test-root"));
    }

    private static AnnotationConfigApplicationContext auditContext(JdbcTemplate jdbc) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("mysqlJdbcTemplate", JdbcTemplate.class, () -> jdbc);
        context.registerBean(AuditSummarySanitizer.class, AuditSummarySanitizer::new);
        context.register(AuditEvidenceProviderConfiguration.class, AuditRetentionHandlerConfiguration.class);
        context.refresh();
        return context;
    }

    private static void insertSyntheticBuilding(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO building
                  (building_id,building_name,building_code,building_type,total_gfa,climate_zone,region_code)
                VALUES ('BLD002','容量合成建筑','CAPACITY-002','办公',1000.00,'夏热冬冷','330100')
                """);
    }

    private static void insertBatched(DataSource dataSource, AuditTable table) {
        String sql = switch (table.shape()) {
            case SYSTEM -> systemInsert(table);
            case COMMON -> commonInsert(table);
            case RELATION -> relationInsert(table);
        };
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (int index = 0; index < table.eventCount(); index++) {
                    switch (table.shape()) {
                        case SYSTEM -> bindSystem(statement, table, index);
                        case COMMON -> bindCommon(statement, table, index);
                        case RELATION -> bindRelation(statement, table, index);
                    }
                    statement.addBatch();
                    if ((index + 1) % 1_000 == 0) statement.executeBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("合成审计数据写入失败: " + table.sourceModule(), failure);
        }
    }

    private static String commonInsert(AuditTable table) {
        return "INSERT INTO " + table.tableName() + " " + """
                (audit_id,building_id,actor_type,operator_id,action_type,object_type,object_id,
                 version_id,review_request_id,before_summary,after_summary,result,reason_code,
                 trace_id,environment_mode,self_approval_dev_mode,operation_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
    }

    private static String systemInsert(AuditTable table) {
        return "INSERT INTO " + table.tableName() + " " + """
                (audit_id,source_module,building_id,actor_type,operator_id,action_type,object_type,
                 object_id,version_id,review_request_id,before_summary,after_summary,result,reason_code,
                 trace_id,environment_mode,self_approval_dev_mode,operation_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
    }

    private static String relationInsert(AuditTable table) {
        return "INSERT INTO " + table.tableName() + " " + """
                (audit_id,building_id,actor_type,operator_id,action_type,object_type,object_id,
                 version_id,request_id,before_state,after_state,result,summary,trace_id,
                 environment_mode,self_approval_dev_mode,operation_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
    }

    private static void bindCommon(PreparedStatement statement, AuditTable table, int index)
            throws Exception {
        boolean collection = "COLLECTION".equals(table.sourceModule());
        String result = collection || index % 10 != 0 ? "SUCCESS" : "REJECTED";
        statement.setString(1, auditId(table, index));
        statement.setString(2, building(index));
        statement.setString(3, "USER");
        statement.setLong(4, operator(index));
        statement.setString(5, action(index));
        statement.setString(6, "POLICY");
        statement.setString(7, objectId(index));
        statement.setString(8, "V" + index % 100);
        statement.setString(9, null);
        statement.setString(10, "state=BEFORE");
        statement.setString(11, "state=AFTER");
        statement.setString(12, result);
        statement.setString(13, "REJECTED".equals(result) ? "CAPACITY_REJECTED" : null);
        statement.setString(14, traceId(table, index));
        statement.setString(15, "TEST");
        statement.setBoolean(16, false);
        statement.setTimestamp(17, Timestamp.valueOf(operationTime(index, table.eventCount())));
    }

    private static void bindSystem(PreparedStatement statement, AuditTable table, int index)
            throws Exception {
        String result = index % 10 == 0 ? "REJECTED" : "SUCCESS";
        statement.setString(1, auditId(table, index));
        statement.setString(2, table.sourceModule());
        statement.setString(3, building(index));
        statement.setString(4, "USER");
        statement.setLong(5, operator(index));
        statement.setString(6, action(index));
        statement.setString(7, "POLICY");
        statement.setString(8, objectId(index));
        statement.setString(9, "V" + index % 100);
        statement.setString(10, null);
        statement.setString(11, "state=BEFORE");
        statement.setString(12, "state=AFTER");
        statement.setString(13, result);
        statement.setString(14, "REJECTED".equals(result) ? "CAPACITY_REJECTED" : null);
        statement.setString(15, traceId(table, index));
        statement.setString(16, "TEST");
        statement.setBoolean(17, false);
        statement.setTimestamp(18, Timestamp.valueOf(operationTime(index, table.eventCount())));
    }

    private static void bindRelation(PreparedStatement statement, AuditTable table, int index)
            throws Exception {
        String result = index % 10 == 0 ? "REJECTED" : "SUCCESS";
        statement.setString(1, auditId(table, index));
        statement.setString(2, building(index));
        statement.setString(3, "USER");
        statement.setLong(4, operator(index));
        statement.setString(5, action(index));
        statement.setString(6, "POLICY");
        statement.setString(7, objectId(index));
        statement.setString(8, "V" + index % 100);
        statement.setString(9, null);
        statement.setString(10, "DRAFT");
        statement.setString(11, "ACTIVE");
        statement.setString(12, result);
        statement.setString(13, "capacity synthetic relation audit");
        statement.setString(14, traceId(table, index));
        statement.setString(15, "TEST");
        statement.setBoolean(16, false);
        statement.setTimestamp(17, Timestamp.valueOf(operationTime(index, table.eventCount())));
    }

    private static List<Long> appendThroughApplicationWriter(JdbcSecurityAuditEvidenceWriter writer) {
        List<Long> micros = new ArrayList<>();
        for (int index = 0; index < APPLICATION_WRITER_EVENTS; index++) {
            long started = System.nanoTime();
            writer.append(evidence("capacity-writer-" + String.format(Locale.ROOT, "%03d", index),
                    WINDOW_START.plusHours(23).plusSeconds(index)));
            micros.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
        }
        return micros;
    }

    private static AuditEvidence evidence(String traceId, LocalDateTime operationTime) {
        return new AuditEvidence("SYSTEM_SECURITY", "BLD001", "SYSTEM", null,
                "CAPACITY_CRITICAL_EVENT", "AUDIT_CAPACITY", traceId, null, null,
                null, "accepted=true", "SUCCESS", null, traceId, operationTime,
                AuditEnvironmentMode.TEST, false);
    }

    private static void verifyQueries(AuditQueryService service, Map<String, Long> metrics) {
        AuditQueryFilter all = filter(null, null, null, null, null, null);
        AuditQueryService.PreparedAuditQuery prepared = new AuditQueryService.PreparedAuditQuery(
                all, Set.of("BLD001", "BLD002"));
        long started = System.nanoTime();
        AuditQueryPage first = service.queryPrepared(prepared, null, 100);
        AuditQueryPage second = service.queryPrepared(prepared, AuditQueryCursor.decode(first.nextCursor()), 100);
        metrics.put("federatedTwoPages", elapsedMillis(started));
        assertThat(first.items()).hasSize(100);
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.items()).hasSize(100);
        assertThat(second.items()).extracting(value -> value.sourceModule() + ":" + value.auditId())
                .doesNotContainAnyElementsOf(first.items().stream()
                        .map(value -> value.sourceModule() + ":" + value.auditId()).toList());

        started = System.nanoTime();
        assertThat(service.countUpTo(prepared, BASELINE_EVENTS + 2L)).isEqualTo(BASELINE_EVENTS + 1L);
        metrics.put("boundedCount", elapsedMillis(started));
        assertQueryReturns(service, metrics, "building", filter("BLD001", null, null, null, null, null));
        assertQueryReturns(service, metrics, "operator", filter(null, 7L, null, null, null, null));
        assertQueryReturns(service, metrics, "object", filter(null, null, "POLICY", "OBJ0001", null, null));
        assertQueryReturns(service, metrics, "result", filter(null, null, null, null, "REJECTED", null));
        assertQueryReturns(service, metrics, "trace", filter(null, null, null, null, null,
                "capacity-over-baseline"));
    }

    private static void assertQueryReturns(
            AuditQueryService service, Map<String, Long> metrics, String name, AuditQueryFilter filter) {
        long started = System.nanoTime();
        AuditQueryPage page = service.queryPrepared(new AuditQueryService.PreparedAuditQuery(
                filter, filter.buildingId() == null ? Set.of("BLD001", "BLD002")
                : Set.of(filter.buildingId())), null, 100);
        metrics.put(name, elapsedMillis(started));
        assertThat(page.items()).as(name + " 组合查询必须返回合成证据").isNotEmpty();
    }

    private static AuditQueryFilter filter(
            String buildingId, Long operatorId, String objectType, String objectId,
            String result, String traceId) {
        return new AuditQueryFilter(null, buildingId, operatorId, null, null, objectType, objectId,
                result, null, traceId, instant(WINDOW_START), instant(WINDOW_END.plusDays(1)));
    }

    private static Map<String, String> verifyIndexPlans(JdbcTemplate jdbc) {
        Map<String, String> indexes = new LinkedHashMap<>();
        for (AuditTable table : TABLES) {
            Map<String, Object> buildingPlan = jdbc.queryForMap("EXPLAIN SELECT audit_id FROM "
                            + table.tableName() + " WHERE building_id=? AND operation_time>=? AND operation_time<?"
                            + " ORDER BY operation_time DESC,audit_id DESC LIMIT 101",
                    "BLD001", Timestamp.valueOf(WINDOW_START.plusHours(12)), Timestamp.valueOf(WINDOW_END));
            String buildingKey = assertPlan(
                    buildingPlan, table.buildingIndexes(), table.sourceModule() + " building query");

            Map<String, Object> tracePlan = jdbc.queryForMap("EXPLAIN SELECT audit_id FROM "
                            + table.tableName() + " WHERE trace_id=?",
                    traceId(table, Math.min(1, table.eventCount() - 1)));
            String traceKey = assertPlan(tracePlan, List.of(table.traceIndex()),
                    table.sourceModule() + " trace query");

            Map<String, Object> retentionPlan = jdbc.queryForMap("EXPLAIN SELECT audit_id,operation_time FROM "
                            + table.tableName() + " WHERE operation_time<?"
                            + " ORDER BY operation_time,audit_id LIMIT " + CLEANUP_BATCH_SIZE,
                    Timestamp.valueOf(WINDOW_END.plusDays(1)));
            String retentionKey = assertPlan(retentionPlan, List.of(table.retentionIndex()),
                    table.sourceModule() + " retention query");
            indexes.put(table.sourceModule(), buildingKey + "," + traceKey + "," + retentionKey);
        }
        return indexes;
    }

    private static String assertPlan(
            Map<String, Object> plan, List<String> expectedIndexes, String description) {
        String key = string(plan.get("key"));
        assertThat(key).as(description + " index").isIn(expectedIndexes);
        assertThat(string(plan.get("Extra"))).as(description + " filesort")
                .doesNotContainIgnoringCase("Using filesort");
        return key;
    }

    private static void insertHolds(JdbcTemplate jdbc) {
        for (AuditTable table : TABLES) {
            jdbc.update("""
                    INSERT INTO sys_audit_evidence_hold
                    (hold_id,source_module,audit_id,reason,legal_basis,starts_at,review_at,status,created_by)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, "HOLD" + table.prefix(), table.sourceModule(), auditId(table, 0),
                    "capacity investigation", "synthetic acceptance",
                    Timestamp.valueOf(WINDOW_START.minusDays(1)), Timestamp.valueOf(WINDOW_END.plusYears(1)),
                    "ACTIVE", 1L);
        }
    }

    private static CleanupMeasurement cleanupAll(
            DataSource dataSource, List<AuditRetentionHandler> handlers) {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        AuditRetentionScope scope = AuditRetentionScope.expiredBefore(WINDOW_END.plusDays(1));
        long started = System.nanoTime();
        long deleted = 0;
        int batches = 0;
        for (AuditRetentionHandler handler : handlers) {
            AuditRetentionPreview preview = handler.preview(scope);
            assertThat(preview.heldCount()).as(handler.sourceModule() + " held count").isOne();
            assertThat(preview.protectedCount()).as(handler.sourceModule() + " protected count").isZero();
            assertThat(preview.eligibleCount()).isEqualTo(preview.candidateCount() - 1);
            while (true) {
                AuditCleanupBatch batch = transaction.execute(status ->
                        handler.deleteNextBatch(scope, CLEANUP_BATCH_SIZE));
                assertThat(batch).isNotNull();
                if (batch.deletedCount() == 0) break;
                assertThat(batch.deletedCount()).isBetween(1, CLEANUP_BATCH_SIZE);
                assertThat(batch.manifestSha256()).hasSize(64);
                deleted += batch.deletedCount();
                batches++;
            }
            assertThat(handler.preview(scope).eligibleCount()).isZero();
        }
        return new CleanupMeasurement(deleted, batches, elapsedMillis(started));
    }

    private static StorageSize storageSize(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(data_length),0),COALESCE(SUM(index_length),0),
                       COALESCE(SUM(data_free),0)
                FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN
                  ('sys_security_audit_event','biz_collection_config_audit_log',
                   'biz_quality_usage_audit_log','biz_device_parameter_audit_log',
                   'biz_onboarding_audit_log','biz_relation_audit_log')
                """, (rs, row) -> new StorageSize(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    private static void analyzeAuditTables(JdbcTemplate jdbc) {
        jdbc.queryForList("ANALYZE TABLE " + String.join(",",
                TABLES.stream().map(AuditTable::tableName).toList()));
    }

    private static long totalRows(JdbcTemplate jdbc) {
        long total = 0;
        for (AuditTable table : TABLES) {
            total += jdbc.queryForObject("SELECT COUNT(*) FROM " + table.tableName(), Long.class);
        }
        return total;
    }

    private static long syntheticRows(JdbcTemplate jdbc) {
        long total = 0;
        for (AuditTable table : TABLES) {
            total += jdbc.queryForObject("SELECT COUNT(*) FROM " + table.tableName()
                    + " WHERE operation_time>=? AND operation_time<?", Long.class,
                    Timestamp.valueOf(WINDOW_START), Timestamp.valueOf(WINDOW_END.plusDays(1)));
        }
        return total;
    }

    private static String auditId(AuditTable table, int index) {
        return "CAP" + table.prefix() + String.format(Locale.ROOT, "%08d", index);
    }

    private static String traceId(AuditTable table, int index) {
        return "trace-" + table.prefix() + "-" + String.format(Locale.ROOT, "%08d", index);
    }

    private static String building(int index) {
        return index % 2 == 0 ? "BLD001" : "BLD002";
    }

    private static long operator(int index) {
        return 1L + index % 100;
    }

    private static String action(int index) {
        return switch (index % 4) {
            case 0 -> "CREATE";
            case 1 -> "SUBMIT";
            case 2 -> "APPROVE";
            default -> "PUBLISH";
        };
    }

    private static String objectId(int index) {
        return "OBJ" + String.format(Locale.ROOT, "%04d", index % 1_000);
    }

    private static LocalDateTime operationTime(int index, int count) {
        long millis = index * 86_000_000L / count;
        return WINDOW_START.plusNanos(millis * 1_000_000L);
    }

    private static Instant instant(LocalDateTime value) {
        return value.atZone(PROJECT_ZONE).toInstant();
    }

    private static long percentile(List<Long> values, int percentile) {
        List<Long> sorted = values.stream().sorted().toList();
        int index = Math.min(sorted.size() - 1,
                Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1));
        return sorted.get(index);
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private enum InsertShape { SYSTEM, COMMON, RELATION }

    private record AuditTable(
            String sourceModule, String tableName, String prefix, int eventCount,
            List<String> buildingIndexes, String traceIndex, String retentionIndex, InsertShape shape) {
    }

    private record StorageSize(long dataBytes, long indexBytes, long freeBytes) {
        long allocatedBytes() {
            return dataBytes + indexBytes;
        }
    }

    private record CleanupMeasurement(long deletedRows, int batches, long elapsedMillis) {
    }
}
