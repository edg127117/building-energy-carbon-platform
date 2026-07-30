package com.platform;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 HVAC 种子数据的单位契约，避免无单位计算测点再次阻断典型值审批和 Q2。
 */
class HvacSeedUnitContractTest {

    @Test
    void allOnlineAnalogCalculationPointsHaveUnits() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:hvac-seed-unit-contract;"
                        + "MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        dataSource.setUser("sa");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("schema-test.sql"),
                new ClassPathResource("data-test.sql"));
        DatabasePopulatorUtils.execute(populator, dataSource);

        Integer missingUnitCount = new JdbcTemplate(dataSource).queryForObject(
                """
                SELECT COUNT(*)
                FROM biz_data_point
                WHERE UPPER(status) = 'ONLINE'
                  AND UPPER(data_type) = 'ANALOG'
                  AND is_for_calc = 1
                  AND (unit IS NULL OR TRIM(unit) = '')
                """,
                Integer.class);

        assertThat(missingUnitCount).isZero();
    }

    @Test
    void productionSeedUsesDimensionlessUnitForPowerFactor() throws IOException {
        Path productionSeed = Path.of(
                System.getProperty("user.dir"),
                "src", "env", "init", "03-init-hvac-schema.sql");
        String pointLine = Files.readAllLines(
                        productionSeed, StandardCharsets.UTF_8)
                .stream()
                .filter(line -> line.startsWith("('POINT007'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "生产初始化脚本缺少 POINT007"));

        assertThat(pointLine).contains("'PF','ANALOG','1',1)");
    }
}
