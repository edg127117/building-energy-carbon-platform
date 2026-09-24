package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinFieldValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

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

    @Test
    void productionDecoderRecognizesTheTwoCelsiusFieldsAndAllowsSafeRollback() throws Exception {
        var properties = properties();
        var payload = new ObjectMapper().readTree("""
                {"code":"10000","data":{"curPage":1,"totalPages":1,"totalCount":1,
                "sites":[{"siteId":"site","controlers":[{"lcNo":"lc","units":[
                {"unitId":"indoor","roomTemp":27.3,"temperature":24}]}]}]}}
                """);
        var fields = DaikinDeviceClientBeans.deviceDecoder(properties)
                .decode("registered-source", DaikinDeviceKey.Kind.INDOOR, payload, Instant.EPOCH)
                .devices().getFirst().fields();
        assertThat(fields.get("roomTemp").status()).isEqualTo(DaikinFieldValue.Status.PRESENT);
        assertThat(fields.get("roomTemp").normalizedValue()).isEqualTo("27.3");
        assertThat(fields.get("temperature").normalizedValue()).isEqualTo("24");

        properties.setTemperatureCelsiusConfirmed(false);
        var disabled = DaikinDeviceClientBeans.deviceDecoder(properties)
                .decode("registered-source", DaikinDeviceKey.Kind.INDOOR, payload, Instant.EPOCH)
                .devices().getFirst().fields();
        assertThat(disabled.get("roomTemp").status()).isEqualTo(DaikinFieldValue.Status.UNCONFIRMED);
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
