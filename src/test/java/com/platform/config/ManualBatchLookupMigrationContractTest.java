package com.platform.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态迁移契约与离线元数据判定，不代表真实 MySQL DDL 或并发锁行为验证。 */
class ManualBatchLookupMigrationContractTest {
    private Connection connection;
    private String migration;
    private String predicate;

    @BeforeEach
    void prepareMetadataFixture() throws Exception {
        migration = Files.readString(Path.of("src", "env", "init",
                "V111__mysql_manual_batch_lookup_index.sql")).replace("\r\n", "\n");
        String marker = "SET @v111_batch_index_matches = (\n";
        int start = migration.indexOf(marker) + marker.length();
        predicate = migration.substring(start, migration.indexOf("\n);", start))
                .replace("information_schema.statistics", "index_statistics")
                .replace("DATABASE()", "'fixture'");
        connection = DriverManager.getConnection("jdbc:h2:mem:batch_index_" + UUID.randomUUID()
                + ";MODE=MySQL;NON_KEYWORDS=COLLATION");
        execute("""
                CREATE TABLE index_statistics (
                    table_schema VARCHAR(64), table_name VARCHAR(64), index_name VARCHAR(64),
                    seq_in_index INT, column_name VARCHAR(64), non_unique INT,
                    index_type VARCHAR(16), collation VARCHAR(1), sub_part INT,
                    is_visible VARCHAR(3), expression VARCHAR(128)
                )
                """);
    }

    @AfterEach
    void closeMetadataFixture() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void addsOnlyTheOnlineBatchIndexAndRestoresTheSessionTimeout() {
        String executable = migration.replaceAll("(?m)^--.*$", "").toUpperCase(Locale.ROOT);
        assertThat(executable).contains(
                "TABLE_SCHEMA = DATABASE()",
                "TABLE_NAME = 'BIZ_ENERGY_MANUAL_READING'",
                "INDEX_NAME = 'IDX_ENERGY_MANUAL_BATCH_FACT'",
                "@V111_BATCH_INDEX_MATCHES,\n    'SELECT 1'",
                "ALTER TABLE BIZ_ENERGY_MANUAL_READING ADD INDEX IDX_ENERGY_MANUAL_BATCH_FACT "
                        + "(BUILDING_ID, BATCH_ID, FACT_ID), ALGORITHM=INPLACE, LOCK=NONE",
                "SET @V111_PREVIOUS_LOCK_WAIT_TIMEOUT = @@SESSION.LOCK_WAIT_TIMEOUT",
                "SET SESSION LOCK_WAIT_TIMEOUT = 5",
                "SET SESSION LOCK_WAIT_TIMEOUT = @V111_PREVIOUS_LOCK_WAIT_TIMEOUT",
                "PREPARE V111_BATCH_INDEX_STATEMENT FROM @V111_BATCH_INDEX_DDL",
                "EXECUTE V111_BATCH_INDEX_STATEMENT",
                "DEALLOCATE PREPARE V111_BATCH_INDEX_STATEMENT");
        assertThat(executable).doesNotContain("ALGORITHM=COPY", "LOCK=SHARED", "LOCK=EXCLUSIVE",
                "DROP ", "TRUNCATE ", "DELETE ", "UPDATE ", "INSERT ", "GRANT ", "SET GLOBAL",
                "FLYWAY_SCHEMA_HISTORY", "READING_JSON", "CXG_V4_STAGE", "IOT_PLATFORM");
    }

    @Test
    void absentIndexRequiresCreation() throws Exception {
        assertThat(matchesExistingIndex()).isFalse();
    }

    @Test
    void identicalIndexIsAcceptedWithoutRebuilding() throws Exception {
        insertExactIndex();
        assertThat(matchesExistingIndex()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM index_statistics WHERE seq_in_index=3",
            "UPDATE index_statistics SET column_name='point_id' WHERE seq_in_index=2",
            "UPDATE index_statistics SET seq_in_index=4 WHERE seq_in_index=3",
            "UPDATE index_statistics SET non_unique=0",
            "UPDATE index_statistics SET index_type='HASH'",
            "UPDATE index_statistics SET collation='D' WHERE seq_in_index=3",
            "UPDATE index_statistics SET sub_part=16 WHERE seq_in_index=2",
            "UPDATE index_statistics SET is_visible='NO'",
            "UPDATE index_statistics SET expression='lower(fact_id)' WHERE seq_in_index=3",
            "INSERT INTO index_statistics SELECT * FROM index_statistics WHERE seq_in_index=3"
    })
    void incompatibleSameNameIndexDoesNotTakeTheNoOpBranch(String mutation) throws Exception {
        insertExactIndex();
        execute(mutation);
        assertThat(matchesExistingIndex()).isFalse();
    }

    @Test
    void otherSchemasTablesAndIndexNamesCannotSatisfyTheGuard() throws Exception {
        insertExactIndex();
        execute("UPDATE index_statistics SET table_schema='other_schema'");
        assertThat(matchesExistingIndex()).isFalse();
        execute("UPDATE index_statistics SET table_schema='fixture', table_name='other_table'");
        assertThat(matchesExistingIndex()).isFalse();
        execute("UPDATE index_statistics SET table_name='biz_energy_manual_reading', index_name='other_index'");
        assertThat(matchesExistingIndex()).isFalse();
    }

    private void insertExactIndex() throws Exception {
        String[] columns = {"building_id", "batch_id", "fact_id"};
        for (int index = 0; index < columns.length; index++) {
            execute("INSERT INTO index_statistics VALUES ('fixture','biz_energy_manual_reading',"
                    + "'idx_energy_manual_batch_fact'," + (index + 1) + ",'" + columns[index]
                    + "',1,'BTREE','A',NULL,'YES',NULL)");
        }
    }

    private boolean matchesExistingIndex() throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(predicate)) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
