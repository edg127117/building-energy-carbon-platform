package com.platform.adapter.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "adapter.profile.mode=remote",
                "adapter.profile.output-version=V2",
                "adapter.profile.remote.base-url=https://localhost:8444/api",
                "adapter.profile.remote.target-id=context-test",
                "adapter.profile.remote.adapter-key=context-test-secret",
                "adapter.mqtt.enabled=false"
        })
@ActiveProfiles("test")
class RemoteProfileSpringContextTest {

    @MockBean
    private AdapterConfigurationClient configurationClient;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ProtocolProfileProvider provider;

    @Test
    void createsOnlyRemoteProviderThroughProductionConstructor() {
        assertThat(provider).isInstanceOf(RemoteProtocolProfileProvider.class);
        assertThat(context.getBeansOfType(ProtocolProfileProvider.class))
                .hasSize(1)
                .containsValue(provider);
        assertThat(context.getBeansOfType(JdbcProtocolProfileProvider.class)).isEmpty();
    }
}
