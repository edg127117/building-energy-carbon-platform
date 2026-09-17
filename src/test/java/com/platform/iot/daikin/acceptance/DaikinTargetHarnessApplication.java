package com.platform.iot.daikin.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.PlatformApplication;
import com.platform.config.TestRedisConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只从测试 classpath 启动的大金目标环境后端。它连接专用 MySQL/TDengine，厂家侧仍是
 * 测试 provider，不把当前模拟报文声明为真实厂家 wire 验收。
 */
public final class DaikinTargetHarnessApplication {
    static final Path DEFAULT_ENV = Path.of("target", "daikin-target-env.json");

    private DaikinTargetHarnessApplication() {
    }

    public static void main(String[] args) throws IOException {
        if (!"true".equalsIgnoreCase(System.getenv("DAIKIN_TARGET_ISOLATED"))) {
            throw new IllegalStateException("DAIKIN_TARGET_ISOLATED=true is required");
        }
        TargetEnvironment target = TargetEnvironment.read(DEFAULT_ENV);
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("test", "daikin-target");
        environment.getPropertySources().addFirst(new MapPropertySource("daikinTarget", target.properties()));

        SpringApplication application = new SpringApplication(
                PlatformApplication.class,
                TestRedisConfiguration.class,
                DaikinTargetHarnessConfiguration.class);
        application.setEnvironment(environment);
        application.run(args);
    }

    record TargetEnvironment(String mysqlUrl, String mysqlUser, String mysqlPassword,
                             String tdUrl, String tdUser, String tdPassword, String tdDatabase,
                             int backendPort, String adminPassword, String ownerPassword) {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        static TargetEnvironment read(Path path) throws IOException {
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Missing isolated target environment: " + path);
            }
            JsonNode root = MAPPER.readTree(path.toFile());
            TargetEnvironment value = new TargetEnvironment(
                    required(root, "mysqlUrl"), required(root, "mysqlUser"), required(root, "mysqlPassword"),
                    required(root, "tdUrl"), required(root, "tdUser"), required(root, "tdPassword"),
                    required(root, "tdDatabase"), integer(root, "backendPort"),
                    required(root, "adminPassword"), required(root, "ownerPassword"));
            value.validate();
            return value;
        }

        Map<String, Object> properties() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("daikin.target.enabled", "true");
            values.put("daikin.target.isolated", "true");
            values.put("daikin.target.admin-password", adminPassword);
            values.put("daikin.target.owner-password", ownerPassword);
            values.put("server.address", "127.0.0.1");
            values.put("server.port", backendPort);
            values.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
            values.put("spring.datasource.url", mysqlSessionUrl());
            values.put("spring.datasource.username", mysqlUser);
            values.put("spring.datasource.password", mysqlPassword);
            values.put("spring.datasource.hikari.connection-init-sql", "SET time_zone = '+08:00'");
            values.put("spring.sql.init.mode", "never");
            values.put("spring.flyway.enabled", "true");
            values.put("spring.flyway.locations", "classpath:db/migration/mysql");
            values.put("spring.flyway.validate-on-migrate", "true");
            values.put("spring.flyway.clean-disabled", "true");
            values.put("tdengine.initialization-enabled", "true");
            values.put("tdengine.url", tdUrl);
            values.put("tdengine.username", tdUser);
            values.put("tdengine.password", tdPassword);
            values.put("tdengine.database", tdDatabase);
            values.put("mqtt.enabled", "false");
            values.put("database.charset-fix.enabled", "false");
            values.put("daikin.directory-sync.enabled", "true");
            values.put("daikin.directory-sync.schedule-delay-ms", "3600000");
            values.put("daikin.directory-sync.base-backoff-seconds", "1");
            values.put("daikin.monitoring.enabled", "true");
            values.put("daikin.monitoring.schedule-delay-ms", "3600000");
            values.put("daikin.monitoring.retry-seconds", "1");
            values.put("daikin.runtime.enabled", "true");
            values.put("daikin.runtime.schedule-delay-ms", "3600000");
            values.put("daikin.runtime.jobs-per-source", "2");
            values.put("daikin.runtime.retry-seconds", "1");
            values.put("daikin.retention.cleanup-enabled", "true");
            return values;
        }

        private String mysqlSessionUrl() {
            String separator = mysqlUrl.contains("?") ? "&" : "?";
            // JDBC serverTimezone 只解释时间值；目标验收还必须把每个连接的 MySQL 会话设为北京时间。
            return mysqlUrl + separator
                    + "connectionTimeZone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
        }

        private void validate() {
            requireLocalJdbc(mysqlUrl, "jdbc:mysql://", "MySQL");
            requireLocalJdbc(tdUrl, "jdbc:TAOS-RS://", "TDengine");
            if (!mysqlUrl.matches("(?i)^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):\\d+/iot_platform(?:[?].*)?$")) {
                throw new IllegalStateException("MySQL target must be the localhost iot_platform database");
            }
            if (!tdDatabase.matches("daikin_[A-Za-z0-9_]{1,50}")) {
                throw new IllegalStateException("TDengine target database must use the daikin_ prefix");
            }
            if (backendPort < 1024 || backendPort > 65535) {
                throw new IllegalStateException("backendPort is outside the isolated port range");
            }
            if (adminPassword.length() < 12 || ownerPassword.length() < 12
                    || adminPassword.equals(ownerPassword)) {
                throw new IllegalStateException("Distinct target account passwords of at least 12 characters are required");
            }
        }

        private static void requireLocalJdbc(String value, String prefix, String label) {
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            String expected = prefix.toLowerCase(java.util.Locale.ROOT);
            if (!(lower.startsWith(expected + "127.0.0.1:") || lower.startsWith(expected + "localhost:"))) {
                throw new IllegalStateException(label + " target must be localhost");
            }
        }

        private static String required(JsonNode root, String name) {
            JsonNode value = root.path(name);
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalStateException("Missing target environment field: " + name);
            }
            return value.textValue();
        }

        private static int integer(JsonNode root, String name) {
            JsonNode value = root.path(name);
            if (!value.canConvertToInt()) {
                throw new IllegalStateException("Missing target environment field: " + name);
            }
            return value.intValue();
        }
    }
}
