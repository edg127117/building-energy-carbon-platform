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
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Executes metadata predicates and the read-only drift guard in H2, not MySQL online DDL or lock behavior. */
class ManualWindowCoveringMigrationContractTest {
    private Connection connection;
    private String migration;
    private String predicate;
    private String branch;

    @BeforeEach
    void prepareMetadataFixture() throws Exception {
        migration = Files.readString(Path.of("src", "env", "init", "V112__mysql_manual_window_covering_index.sql"))
                .replace("\r\n", "\n");
        String marker = "SET @v112_cursor_index_columns = (\n";
        int start = migration.indexOf(marker);
        assertThat(start).isGreaterThanOrEqualTo(0);
        start += marker.length();
        predicate = migration.substring(start, migration.indexOf("\n);", start))
                .replace("information_schema.statistics", "index_statistics")
                .replace("DATABASE()", "'fixture'");
        marker = "SET @v112_cursor_index_ddl = ";
        start = migration.indexOf(marker);
        assertThat(start).isGreaterThanOrEqualTo(0);
        start += marker.length();
        branch = migration.substring(start, migration.indexOf("\nEND;", start) + "\nEND".length());
        connection = DriverManager.getConnection("jdbc:h2:mem:window_index_" + UUID.randomUUID()
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
    void exactThreeColumnsSelectOnlyOneAtomicOnlineIndexReplacement() throws Exception {
        insertIndex(3);
        assertThat(shape()).isEqualTo(3);
        assertThat(selectedStatement()).isEqualTo("ALTER TABLE biz_energy_manual_reading DROP INDEX idx_energy_manual_cursor, "
                + "ADD INDEX idx_energy_manual_cursor (building_id, start_inclusive, fact_id, end_exclusive), ALGORITHM=INPLACE, LOCK=NONE");
    }

    @Test
    void exactFourColumnsSelectNoOp() throws Exception {
        insertIndex(4);
        assertThat(shape()).isEqualTo(4);
        assertThat(selectedStatement()).isEqualTo("SELECT 1");
    }

    @Test
    void missingIndexSelectsReadOnlyGuardThatActuallyFailsPreparation() throws Exception {
        assertThat(shape()).isZero();
        assertReadOnlyGuardFails();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM index_statistics WHERE seq_in_index=3",
            "UPDATE index_statistics SET column_name='point_id' WHERE seq_in_index=2",
            "UPDATE index_statistics SET seq_in_index=5 WHERE seq_in_index=3",
            "UPDATE index_statistics SET non_unique=0",
            "UPDATE index_statistics SET index_type='HASH'",
            "UPDATE index_statistics SET collation='D' WHERE seq_in_index=3",
            "UPDATE index_statistics SET sub_part=16 WHERE seq_in_index=2",
            "UPDATE index_statistics SET is_visible='NO'",
            "UPDATE index_statistics SET expression='lower(fact_id)' WHERE seq_in_index=3",
            "INSERT INTO index_statistics SELECT * FROM index_statistics WHERE seq_in_index=3",
            "UPDATE index_statistics SET column_name='building_id', seq_in_index=1 WHERE seq_in_index=2",
            "UPDATE index_statistics SET column_name=NULL WHERE seq_in_index=2",
            "UPDATE index_statistics SET collation=NULL WHERE seq_in_index=2"
    })
    void anyDefinitionDriftFailsClosedForBothSupportedShapes(String mutation) throws Exception {
        for (int columns : new int[]{3, 4}) {
            execute("DELETE FROM index_statistics");
            insertIndex(columns);
            execute(mutation);
            assertThat(shape()).as("columns=%s, mutation=%s", columns, mutation).isZero();
            assertReadOnlyGuardFails();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE index_statistics SET table_schema='other_schema'",
            "UPDATE index_statistics SET table_name='other_table'",
            "UPDATE index_statistics SET index_name='other_index'"
    })
    void otherSchemaTableOrIndexCannotSatisfyCurrentDatabaseGuard(String mutation) throws Exception {
        insertIndex(4);
        execute(mutation);
        assertThat(shape()).isZero();
        assertReadOnlyGuardFails();
    }

    @Test
    void unrelatedSchemaRowsDoNotAlterAnExactCurrentDatabaseDefinition() throws Exception {
        insertIndex(3);
        execute("INSERT INTO index_statistics SELECT 'other_schema',table_name,index_name,seq_in_index,column_name,"
                + "non_unique,index_type,collation,sub_part,is_visible,expression FROM index_statistics");
        assertThat(shape()).isEqualTo(3);
    }

    @Test
    void boundsSessionWaitRestoresSuccessfulSessionAndContainsNoFallbackOrUnrelatedChanges() {
        String executable = migration.replaceAll("(?m)^--.*$", "").toUpperCase(Locale.ROOT);
        assertThat(executable).contains(
                "TABLE_SCHEMA = DATABASE()",
                "TABLE_NAME = 'BIZ_ENERGY_MANUAL_READING'",
                "INDEX_NAME = 'IDX_ENERGY_MANUAL_CURSOR'",
                "COUNT(DISTINCT SEQ_IN_INDEX) = COUNT(*)",
                "MAX(SEQ_IN_INDEX) = COUNT(*)",
                "WHEN 4 THEN 'SELECT 1'",
                "WHEN 3 THEN 'ALTER TABLE",
                "SET @V112_PREVIOUS_LOCK_WAIT_TIMEOUT = @@SESSION.LOCK_WAIT_TIMEOUT",
                "SET SESSION LOCK_WAIT_TIMEOUT = 5",
                "PREPARE V112_CURSOR_INDEX_STATEMENT FROM @V112_CURSOR_INDEX_DDL",
                "EXECUTE V112_CURSOR_INDEX_STATEMENT",
                "DEALLOCATE PREPARE V112_CURSOR_INDEX_STATEMENT",
                "SET SESSION LOCK_WAIT_TIMEOUT = @V112_PREVIOUS_LOCK_WAIT_TIMEOUT",
                "SET @V112_CURSOR_INDEX_COLUMNS = NULL",
                "SET @V112_CURSOR_INDEX_DDL = NULL",
                "SET @V112_PREVIOUS_LOCK_WAIT_TIMEOUT = NULL");
        assertThat(executable.split("ALTER TABLE", -1)).hasSize(2);
        assertThat(executable.split("DROP INDEX", -1)).hasSize(2);
        assertThat(executable).doesNotContain("ALGORITHM=COPY", "LOCK=SHARED", "LOCK=EXCLUSIVE", "DROP TABLE",
                "TRUNCATE ", "DELETE ", "UPDATE ", "INSERT ", "GRANT ", "SET GLOBAL", "CREATE PROCEDURE", "CREATE FUNCTION",
                "FLYWAY_SCHEMA_HISTORY", "READING_JSON", "CXG_V4_STAGE", "IOT_PLATFORM");
        assertThat(executable.indexOf("SET SESSION LOCK_WAIT_TIMEOUT = 5"))
                .isLessThan(executable.indexOf("PREPARE V112_CURSOR_INDEX_STATEMENT"));
        assertThat(executable.indexOf("SET SESSION LOCK_WAIT_TIMEOUT = @V112_PREVIOUS_LOCK_WAIT_TIMEOUT"))
                .isGreaterThan(executable.indexOf("DEALLOCATE PREPARE V112_CURSOR_INDEX_STATEMENT"));
    }

    private void insertIndex(int count) throws Exception {
        String[] columns = {"building_id", "start_inclusive", "fact_id", "end_exclusive"};
        for (int index = 0; index < count; index++) {
            execute("INSERT INTO index_statistics VALUES ('fixture','biz_energy_manual_reading',"
                    + "'idx_energy_manual_cursor'," + (index + 1) + ",'" + columns[index]
                    + "',1,'BTREE','A',NULL,'YES',NULL)");
        }
    }

    private int shape() throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(predicate)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private String selectedStatement() throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT " + branch.replace("@v112_cursor_index_columns", Integer.toString(shape())))) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private void assertReadOnlyGuardFails() throws Exception {
        String guard = selectedStatement();
        assertThat(guard).isEqualTo("SELECT v112_guard.incompatible_cursor_index FROM (SELECT 1 AS expected_definition) AS v112_guard");
        assertThatThrownBy(() -> {
            try (var statement = connection.prepareStatement(guard)) { statement.executeQuery(); }
        }).isInstanceOf(SQLException.class);
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
