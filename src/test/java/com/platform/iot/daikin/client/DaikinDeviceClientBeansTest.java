package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaikinDeviceClientBeansTest {
    @Test
    void onlyConfiguredSourceReceivesProductionClientWithoutMakingRequest() {
        var properties = properties();
        var provider = new DaikinDeviceClientBeans().daikinCatalogClientProvider(properties,
                new ObjectMapper());
        assertThat(provider.clientFor("other-source")).isEmpty();
        assertThat(provider.clientFor("registered-source")).isPresent();
    }

    @Test
    void insecureTargetOrMissingCredentialsFailBeforeProviderIsAvailable() {
        var properties = properties();
        properties.setBaseUri(URI.create("http://vendor.example.test"));
        assertThatThrownBy(() -> new DaikinDeviceClientBeans()
                .daikinCatalogClientProvider(properties, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setBaseUri(URI.create("https://vendor.example.test"));
        properties.setPassword(null);
        assertThatThrownBy(() -> new DaikinDeviceClientBeans()
                .daikinCatalogClientProvider(properties, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DaikinDeviceClientProperties properties() {
        var properties = new DaikinDeviceClientProperties();
        properties.setEnabled(true);
        properties.setSourceId("registered-source");
        properties.setBaseUri(URI.create("https://vendor.example.test"));
        properties.setAppId("test-app");
        properties.setPassword("test-password");
        properties.setCommunicationKey("0123456789abcdef");
        properties.setSignatureSalt("test-sign-salt");
        return properties;
    }
}
