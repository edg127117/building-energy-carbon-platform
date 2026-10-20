package com.platform.config;

import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Set;

/** 拒绝以缺失/公开示例凭据或数据库超级用户运行；只检查配置，不连接或修改依赖服务。 */
public class DependencyAccessGuard {
    private static final Set<String> EXAMPLES = Set.of("change-me", "changeme", "taosdata", "public",
            "password", "123456", "admin", "root");

    public DependencyAccessGuard(Environment environment) {
        requirePassword(environment, "spring.datasource.password");
        requirePassword(environment, "spring.data.redis.password");
        requirePassword(environment, "tdengine.password");
        requireApplicationUser(environment, "spring.datasource.username");
        requireApplicationUser(environment, "tdengine.username");
        if (environment.getProperty("mqtt.enabled", Boolean.class, true)) {
            requirePassword(environment, "mqtt.password");
        }
        if (environment.getProperty("spring.flyway.enabled", Boolean.class, true)) {
            requirePassword(environment, "spring.flyway.password");
            String migrationUser = environment.getProperty("spring.flyway.user", "").trim();
            if (migrationUser.isEmpty() || migrationUser.equalsIgnoreCase(environment.getProperty("spring.datasource.username", ""))) {
                throw new IllegalStateException("spring.flyway.user 必须显式配置独立的迁移账号");
            }
        }
    }

    private static void requirePassword(Environment environment, String property) {
        String value = environment.getProperty(property, "");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 16 || EXAMPLES.contains(normalized) || normalized.startsWith("replace-")) {
            throw new IllegalStateException(property + " 必须配置独立的非示例密码（至少16字符）");
        }
    }

    private static void requireApplicationUser(Environment environment, String property) {
        String value = environment.getProperty(property, "").trim();
        if (value.isEmpty() || value.equalsIgnoreCase("root")) {
            throw new IllegalStateException(property + " 必须使用专用应用账号");
        }
    }
}
