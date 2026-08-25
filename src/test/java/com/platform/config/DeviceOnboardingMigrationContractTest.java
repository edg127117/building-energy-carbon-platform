package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定设备接入绑定迁移的中断恢复和同名结构冲突边界。 */
class DeviceOnboardingMigrationContractTest {
    private final String migration = read("src/env/init/V14__device_onboarding_binding.sql");

    @Test
    void bindingMigrationGuardsEachSchemaObjectBeforeCreatingIt() {
        assertThat(migration)
                .contains("information_schema.`COLUMNS`",
                        "information_schema.`STATISTICS`",
                        "information_schema.`KEY_COLUMN_USAGE`",
                        "ONBOARDING_PRODUCT_COLUMN_CONFLICT",
                        "ONBOARDING_PRODUCT_INDEX_CONFLICT",
                        "ONBOARDING_PRODUCT_FK_CONFLICT")
                .doesNotContain("DROP TABLE", "TRUNCATE TABLE");
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取设备接入绑定迁移: " + path, exception);
        }
    }
}
