package com.platform.audit;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅使用 S01 CI 同一一次性空库；建夹具前核对主机、库名、版本与时区，禁止指向部署环境。 */
@EnabledIfEnvironmentVariable(named = "S01_AUTH_IT_ENABLED", matches = "true")
class BackendDutyMysqlIntegrationTest extends BackendDutyTimeContract {
    @Override
    protected DataSource dataSource() {
        assertThat(System.getenv("S01_AUTH_IT_ISOLATED")).isEqualTo("true");
        String url = System.getenv("S01_MYSQL_URL");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:").contains("/s01_auth_it?");
        var source = new DriverManagerDataSource(url, "root", System.getenv("S01_MYSQL_PASSWORD"));
        var properties = new Properties();
        properties.setProperty("sessionVariables", "time_zone='+00:00'");
        properties.setProperty("connectionTimeZone", "Asia/Shanghai");
        properties.setProperty("forceConnectionTimeZoneToSession", "false");
        source.setConnectionProperties(properties);
        var check = new JdbcTemplate(source);
        assertThat(check.queryForObject("SELECT @@hostname", String.class)).isEqualTo("s01-auth-mysql");
        assertThat(check.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("s01_auth_it");
        assertThat(check.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
        assertThat(check.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        assertThat(check.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isZero();
        return source;
    }
}
