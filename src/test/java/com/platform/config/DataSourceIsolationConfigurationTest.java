package com.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DataSourceIsolationConfigurationTest {

    @Test
    void mysqlConfigExposesJdbcTemplateBoundToPrimaryDatasource() {
        DataSource dataSource = mock(DataSource.class);

        JdbcTemplate template = new MysqlConfig().mysqlJdbcTemplate(dataSource);

        assertThat(template.getDataSource()).isSameAs(dataSource);
    }

    @Test
    void charsetFixCanBeDisabledByProperty() {
        ConditionalOnProperty condition =
                DatabaseCharsetFix.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("database.charset-fix");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }

    @Test
    void tdengineInitializationCanBeDisabledByProperty() {
        Method runner = Arrays.stream(TdengineConfig.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("initTaosDb"))
                .findFirst()
                .orElseThrow();
        ConditionalOnProperty condition =
                runner.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("tdengine");
        assertThat(condition.name()).containsExactly("initialization-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}
