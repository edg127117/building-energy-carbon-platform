package com.platform.tools;

import org.junit.jupiter.api.Test;
import java.util.Map;
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
    void permitsNoOtherUpgradeRepairOrBaselineAction() {
        for (String action : new String[]{"upgrade120", "upgrade124", "repair", "clean", "baseline", "validate119"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> DemoSchemaMigration.run(action, Map.of("DEMO_EXPECTED_DATABASE", "demo_business")));
        }
    }

    @Test
    void sharedCoreAcceptsNoUnvalidatedDatabaseIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> D01SchemaMigration.runForDatabase("validate123", Map.of(), "db;DROP"));
    }
}
