package com.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class ManagementListenerConfigurationTest {
    @Test
    void rejectsSharedPortAndNonLoopbackBind() {
        var environment = new MockEnvironment().withProperty("server.port", "18081")
                .withProperty("management.server.port", "18081").withProperty("management.server.address", "127.0.0.1");
        assertThatThrownBy(() -> ManagementSecurityConfig.validateListener(environment)).isInstanceOf(IllegalStateException.class);
        environment.setProperty("management.server.port", "18082");
        environment.setProperty("management.server.address", "0.0.0.0");
        assertThatThrownBy(() -> ManagementSecurityConfig.validateListener(environment)).isInstanceOf(IllegalStateException.class);
        environment.setProperty("management.server.address", "127.0.0.1");
        assertThatCode(() -> ManagementSecurityConfig.validateListener(environment)).doesNotThrowAnyException();
    }
}
