package com.platform.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class DependencyAccessGuardTest {
    @Test
    void missingCredentialsStopContextBeforeClientsOrInitializationCanRun() {
        var created = new java.util.concurrent.atomic.AtomicBoolean();
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(MysqlConfig.class);
            context.registerBean("initializationProbe", Object.class, () -> {
                created.set(true);
                return new Object();
            });
            assertThatThrownBy(context::refresh).hasStackTraceContaining("spring.datasource.password");
            assertThat(created).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "change-me", "taosdata", "public", "replace-with-random-password"})
    void rejectsMissingOrExampleSecretsWithoutEchoingThem(String secret) {
        var environment = valid().withProperty("spring.data.redis.password", secret);
        assertThatThrownBy(() -> new DependencyAccessGuard(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.password");
    }

    @Test
    void migrationAccountMayBeAdministrativeButRuntimeMustBeDedicated() {
        var environment = valid().withProperty("spring.flyway.user", "root");
        assertThatCode(() -> new DependencyAccessGuard(environment)).doesNotThrowAnyException();
        for (String property : java.util.List.of("spring.datasource.username", "tdengine.username")) {
            environment.setProperty(property, "root");
            assertThatThrownBy(() -> new DependencyAccessGuard(environment)).hasMessageContaining(property);
            environment.setProperty(property, "platform_app");
        }
    }

    @Test
    void enabledMigrationsCannotSilentlyReuseRuntimeAccount() {
        var environment = valid().withProperty("spring.flyway.user", "platform_app");
        assertThatThrownBy(() -> new DependencyAccessGuard(environment)).hasMessageContaining("spring.flyway.user");
        environment.setProperty("spring.flyway.enabled", "false");
        assertThatCode(() -> new DependencyAccessGuard(environment)).doesNotThrowAnyException();
    }

    @Test
    void disabledMqttDoesNotRequireAnUnusedBrokerPassword() {
        var environment = valid().withProperty("mqtt.enabled", "false").withProperty("mqtt.password", "");
        assertThatCode(() -> new DependencyAccessGuard(environment)).doesNotThrowAnyException();
    }

    private MockEnvironment valid() {
        var environment = new MockEnvironment();
        for (String key : java.util.List.of("spring.datasource.password", "spring.data.redis.password",
                "tdengine.password", "mqtt.password", "spring.flyway.password")) {
            environment.setProperty(key, "isolated-test-only-password");
        }
        return environment.withProperty("spring.datasource.username", "platform_app")
                .withProperty("spring.flyway.user", "platform_migrator")
                .withProperty("tdengine.username", "platform_app");
    }
}
