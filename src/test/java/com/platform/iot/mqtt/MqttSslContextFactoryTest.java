package com.platform.iot.mqtt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttSslContextFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsExternalTrustStoreWithoutTrustAllFallback() throws Exception {
        Path trustStore = tempDir.resolve("trust.p12");
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, "test-pass".toCharArray());
        try (OutputStream output = Files.newOutputStream(trustStore)) {
            store.store(output, "test-pass".toCharArray());
        }
        MqttTlsProperties properties = new MqttTlsProperties();
        properties.setTrustStore(trustStore.toString());
        properties.setTrustStorePassword("test-pass");

        assertThat(new MqttSslContextFactory().create(properties).getSocketFactory())
                .isNotNull();
    }

    @Test
    void rejectsMissingTrustStore() {
        MqttTlsProperties properties = new MqttTlsProperties();
        assertThatThrownBy(() -> new MqttSslContextFactory().create(properties))
                .isInstanceOf(MqttTlsException.class)
                .hasMessageContaining("trustStore");
    }
}
