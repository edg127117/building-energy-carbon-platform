package com.platform.iot.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("mqtt-integration")
@EnabledIfEnvironmentVariable(named = "MQTT_TEST_BROKER_ENABLED", matches = "true")
class MqttTlsBrokerIntegrationTest {

    @Test
    void verifiesTlsHostnameAndQos1BrokerRoundTrip() throws Exception {
        MqttTlsProperties tls = new MqttTlsProperties();
        tls.setTrustStore(System.getenv("MQTT_TEST_TRUST_STORE"));
        tls.setTrustStorePassword(System.getenv("MQTT_TEST_TRUST_STORE_PASSWORD"));
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setSocketFactory(new MqttSslContextFactory().create(tls).getSocketFactory());
        options.setSSLHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        options.setHttpsHostnameVerificationEnabled(true);

        String clientId = "tls-test-" + UUID.randomUUID();
        String topic = "test/reliability/" + clientId;
        CountDownLatch received = new CountDownLatch(1);
        try (MqttClient client = new MqttClient(
                "ssl://localhost:8883", clientId, new MemoryPersistence())) {
            client.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
                public void connectionLost(Throwable cause) { }
                public void deliveryComplete(
                        org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) { }
                public void messageArrived(String actualTopic, MqttMessage message) {
                    if (topic.equals(actualTopic)) {
                        received.countDown();
                    }
                }
            });
            client.connect(options);
            client.subscribe(topic, 1);
            client.publish(topic, "qos1".getBytes(StandardCharsets.UTF_8), 1, false);
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            client.disconnect();
        }
    }
}
