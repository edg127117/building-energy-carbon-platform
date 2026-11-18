package com.platform.tools;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DemoSchemaMigrationTest {
    @Test
    void rejectsSharedSystemMissingAndUnsafeDatabaseBindingsBeforeConnecting() {
        for (String name : new String[]{"iot_platform", "IOT_PLATFORM", "iot_platform_archive", "mysql", "sys",
                "information_schema", "performance_schema", "db;DROP", ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> DemoSchemaMigration.run("upgrade121", Map.of("DEMO_EXPECTED_DATABASE", name)));
        }
        assertThrows(IllegalArgumentException.class, () -> DemoSchemaMigration.run("validate120", Map.of()));
    }

    @Test
    void rejectsUnsupportedActionsAndUpgradeWithoutApprovedPredecessor() {
        for (String action : new String[]{"upgrade120", "upgrade124", "repair", "clean", "baseline", "validate119"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> DemoSchemaMigration.run(action, Map.of("DEMO_EXPECTED_DATABASE", "demo_business")));
        }
    }

    @Test
    void acceptsLaterVersionActionsWithoutAddingToAnAllowlist() {
        assertEquals("124", D01SchemaMigration.target("upgrade124"));
        assertEquals("126", D01SchemaMigration.target("validate126"));
        var failure = assertThrows(IllegalArgumentException.class, () -> DemoSchemaMigration.run("upgrade126",
                Map.of("DEMO_EXPECTED_DATABASE", "demo_business", "D01_EXPECTED_PREDECESSOR", "126")));
        assertEquals("D01_SCHEMA_PREDECESSOR_INVALID", failure.getMessage());
    }

    @Test
    void sharedCoreAcceptsNoUnvalidatedDatabaseIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> D01SchemaMigration.runForDatabase("validate123", Map.of(), "db;DROP"));
    }
}
