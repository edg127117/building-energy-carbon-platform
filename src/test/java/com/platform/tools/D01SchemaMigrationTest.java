package com.platform.tools;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class D01SchemaMigrationTest {
    @Test
    void appendsLedgerAfterTheAlreadyDeployedRoomSensorMigration() {
        assertEquals("122", D01SchemaMigration.target("upgrade122"));
        assertEquals("123", D01SchemaMigration.target("validate123"));
        assertEquals("121", D01SchemaMigration.predecessor(122));
        assertEquals("122", D01SchemaMigration.predecessor(123));
        var before = SharedAlignmentSchemaContract.expectedObjects(121);
        var after = SharedAlignmentSchemaContract.expectedObjects(123);
        assertTrue(after.containsAll(before));
        assertEquals(before.size() + 8, after.size());
        assertTrue(after.stream().anyMatch(o -> o.name().equals("biz_ledger_type_rule")));
        assertTrue(after.stream().anyMatch(o -> o.name().equals("biz_device_comm_target")));
    }

    @Test
    void retainsHistoricalActionsAndRequiresCredentialsForLaterVersions() {
        for (String version : new String[]{"83", "84", "85", "86", "87", "88", "93", "94", "95", "96",
                "97", "98", "99", "100", "101", "102", "103", "104", "105", "106", "107",
                "110", "111", "112", "113", "114", "115", "116", "117", "118", "119", "120", "121", "122", "123"}) {
            assertEquals(version, D01SchemaMigration.target("validate" + version));
            if (!version.equals("83")) assertEquals(version, D01SchemaMigration.target("upgrade" + version));
        }
        for (String action : new String[]{"upgrade83", "upgrade89", "validate89", "upgrade108", "upgrade109",
                "validate108", "validate109", "repair", "clean", "baseline", "85"}) {
            assertThrows(IllegalArgumentException.class, () -> D01SchemaMigration.run(action, Map.of()));
        }
        assertEquals("124", D01SchemaMigration.target("validate124"));
        var error = assertThrows(IllegalArgumentException.class, () -> D01SchemaMigration.run("validate124", Map.of()));
        assertEquals("D01_REQUIRED_D01_JDBC_URL", error.getMessage());
    }

    @Test
    void usesExplicitPredecessorsAcrossVersionGaps() {
        assertEquals("88", D01SchemaMigration.predecessor(93));
        assertEquals("93", D01SchemaMigration.predecessor(94));
        assertEquals("107", D01SchemaMigration.predecessor(110));
        assertEquals("110", D01SchemaMigration.predecessor(111));
        assertEquals("120", D01SchemaMigration.predecessor(121));
    }

    @Test
    void onlyAllowsTheRequestedMigrationAsTheSinglePendingStep() {
        D01SchemaMigration.requireSingleTargetPending("121", List.of("121"));
        for (List<String> pending : List.of(List.<String>of(), List.of("120"), List.of("120", "121"))) {
            assertThrows(IllegalStateException.class,
                    () -> D01SchemaMigration.requireSingleTargetPending("121", pending));
        }
    }

    @Test
    void derivesCumulativeObjectsFromMigrationSql() {
        var through120 = SharedAlignmentSchemaContract.expectedObjects(120);
        var through121 = SharedAlignmentSchemaContract.expectedObjects(121);
        assertTrue(through120.contains(new SharedAlignmentSchemaContract.ObjectRef("TABLE", "biz_carbon_hourly_batch")));
        assertTrue(through121.contains(new SharedAlignmentSchemaContract.ObjectRef("TABLE", "biz_room_sensor_source")));
        assertTrue(through121.contains(new SharedAlignmentSchemaContract.ObjectRef("TABLE", "biz_room_sensor_ingest_pending")));
    }

    @Test
    void rejectsMissingSchemaObjectsAndIncorrectCursorIndexOrder() {
        var expected = Set.of(new SharedAlignmentSchemaContract.ObjectRef("TABLE", "biz_room_sensor_source"));
        assertThrows(IllegalStateException.class,
                () -> SharedAlignmentSchemaContract.requireObjects(expected, Set.of()));

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("information_schema.STATISTICS"), eq(String.class)))
                .thenReturn(java.util.List.of("building_id", "fact_id", "start_inclusive", "end_exclusive"));
        assertThrows(IllegalStateException.class, () -> SharedAlignmentSchemaContract.requireCursorIndex(jdbc));
    }

    @Test
    void rejectsMissingRoomSensorForeignKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("information_schema.KEY_COLUMN_USAGE"), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        assertThrows(IllegalStateException.class, () -> SharedAlignmentSchemaContract.requireForeignKey(jdbc,
                "fk_room_sensor_identity", "biz_room_sensor_source", "identity_id", "biz_device_identity", "identity_id"));
    }

    @Test
    void requiresMediumtextEvidenceInBothPeriodTables() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Integer.class), any(Object[].class))).thenReturn(1);
        SharedAlignmentSchemaContract.requireEvidenceCapacity(jdbc);
        when(jdbc.queryForObject(any(String.class), eq(Integer.class), any(Object[].class))).thenAnswer(call -> {
            Object[] bindings = (Object[]) call.getRawArguments()[2];
            return bindings[0].equals("biz_energy_period_result_snapshot") ? 0 : 1;
        });
        assertThrows(IllegalStateException.class, () -> SharedAlignmentSchemaContract.requireEvidenceCapacity(jdbc));
    }

    @Test
    void refusesCredentialsInJdbcUrlBeforeAnyConnection() {
        assertThrows(IllegalArgumentException.class, () -> D01SchemaMigration.run("upgrade85",
                Map.of("D01_JDBC_URL", "jdbc:mysql://127.0.0.1/iot_platform?password=fixture")));
    }
}
