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
    void controlOffCanConfigureGrantsThroughReadonlyBeanWithoutRegisteringWriteGateway() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(com.platform.iot.daikin.control.DaikinControlProperties.class,
                        com.platform.iot.daikin.control.DaikinControlProperties::new)
                .withPropertyValues("daikin.device-client.enabled=true", "daikin.device-client.source-id=registered-source",
                        "daikin.device-client.base-uri=https://vendor.example.test", "daikin.device-client.app-id=test-app",
                        "daikin.device-client.password=test-password", "daikin.device-client.signature-salt=test-sign-salt",
                        "daikin.device-client.communication-key=0123456789abcdef")
                .withUserConfiguration(DaikinDeviceClientBeans.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(com.platform.iot.daikin.control.DaikinControlGrantReader.class);
                    assertThat(context).doesNotHaveBean(com.platform.iot.daikin.control.DaikinControlGateway.class);
                    var reader = context.getBean(com.platform.iot.daikin.control.DaikinControlGrantReader.class);
                    assertThat(reader.supports("registered-source")).isTrue();
                    assertThat(reader.supports("other-source")).isFalse();
                    assertThat(reader).isNotInstanceOf(com.platform.iot.daikin.control.DaikinControlGateway.class);
                });
    }

    @Test
    void testProfileCannotCreateRealClientsEvenWhenExternalConfigurationEnablesThem() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues("daikin.device-client.enabled=true", "daikin.control.enabled=true", "daikin.control.wire-confirmed=true")
                .withUserConfiguration(DaikinDeviceClientBeans.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DaikinSession.class);
                    assertThat(context).doesNotHaveBean(com.platform.iot.daikin.control.DaikinControlGateway.class);
                    assertThat(context).doesNotHaveBean(com.platform.iot.daikin.control.DaikinControlGrantReader.class);
                });
    }

    @Test
    void onlyConfiguredSourceReceivesProductionClientWithoutMakingRequest() {
        var properties = properties();
        var provider = new DaikinDeviceClientBeans().daikinCatalogClientProvider(properties,
                session(properties));
        assertThat(provider.clientFor("other-source")).isEmpty();
        assertThat(provider.clientFor("registered-source")).isPresent();
    }

    @Test
    void insecureTargetOrMissingCredentialsFailBeforeProviderIsAvailable() {
        var properties = properties();
        properties.setBaseUri(URI.create("http://vendor.example.test"));
        assertThatThrownBy(() -> new DaikinDeviceClientBeans()
                .daikinCatalogClientProvider(properties, session(properties)))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setBaseUri(URI.create("https://vendor.example.test"));
        properties.setPassword(null);
        assertThatThrownBy(() -> new DaikinDeviceClientBeans()
                .daikinCatalogClientProvider(properties, session(properties)))
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

    private static DaikinSession session(DaikinDeviceClientProperties properties) {
        return new DaikinDeviceClientBeans().daikinSession(properties, new ObjectMapper(),
                new com.platform.iot.daikin.control.DaikinControlProperties());
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
