package com.platform.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class D01SchemaMigrationTest {
    @Test
    void permitsOnlyExplicitAdjacentMigrationActions() {
        for (String version : new String[]{"83", "84", "85", "86", "87"}) {
            assertEquals(version, D01SchemaMigration.target("validate" + version));
        }
        assertEquals("84", D01SchemaMigration.target("upgrade84"));
        assertEquals("85", D01SchemaMigration.target("upgrade85"));
        assertEquals("86", D01SchemaMigration.target("upgrade86"));
        assertEquals("87", D01SchemaMigration.target("upgrade87"));
        for (String action : new String[]{"upgrade83", "upgrade88", "validate88", "repair", "clean", "baseline", "85"}) {
            assertThrows(IllegalArgumentException.class, () -> D01SchemaMigration.run(action, Map.of()));
        }
    }

    @Test
    void refusesCredentialsInJdbcUrlBeforeAnyConnection() {
        assertThrows(IllegalArgumentException.class, () -> D01SchemaMigration.run("upgrade85",
                Map.of("D01_JDBC_URL", "jdbc:mysql://127.0.0.1/iot_platform?password=fixture")));
    }
}
