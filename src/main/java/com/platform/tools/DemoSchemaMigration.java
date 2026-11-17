package com.platform.tools;

import java.util.Map;
import java.util.Set;

/** 独立大屏维护入口；显式绑定正在使用的业务库，禁止落到共享库或容器内遗留默认库。 */
public final class DemoSchemaMigration {
    private static final Set<String> ACTIONS = Set.of("validate120", "validate121", "upgrade121",
            "validate122", "upgrade122", "validate123", "upgrade123");

    private DemoSchemaMigration() { }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("DEMO_SCHEMA_ACTION_REQUIRED");
        run(args[0], System.getenv());
    }

    static void run(String action, Map<String, String> environment) {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("DEMO_SCHEMA_ACTION_REQUIRED");
        String database = environment.get("DEMO_EXPECTED_DATABASE");
        if (database == null || !database.matches("[A-Za-z0-9_]{1,64}")
                || Set.of("iot_platform", "iot_platform_archive", "mysql", "sys", "information_schema", "performance_schema")
                        .contains(database.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("DEMO_ACTIVE_DATABASE_REQUIRED");
        }
        // 连接仍由独立维护凭据提供；这里只改变明确核验的库名，不放开旧版本或 repair/baseline。
        D01SchemaMigration.runForDatabase(action, environment, database);
        System.out.println("DEMO_SCHEMA_VALIDATED=" + D01SchemaMigration.target(action));
    }
}
