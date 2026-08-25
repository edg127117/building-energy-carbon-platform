package com.platform.iot.mqtt;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.net.UnknownHostException;
import java.security.cert.CertificateExpiredException;

import static org.assertj.core.api.Assertions.assertThat;

class MqttFailureClassifierTest {

    private final MqttFailureClassifier classifier = new MqttFailureClassifier();

    @Test
    void classifiesTlsDnsAndAuthenticationFailures() {
        assertThat(classifier.classify(new CertificateExpiredException()))
                .isEqualTo(MqttFailureCategory.CERTIFICATE_TIME_INVALID);
        assertThat(classifier.classify(new SSLPeerUnverifiedException("hostname mismatch")))
                .isEqualTo(MqttFailureCategory.HOSTNAME_MISMATCH);
        assertThat(classifier.classify(new UnknownHostException("broker.invalid")))
                .isEqualTo(MqttFailureCategory.DNS_FAILURE);
        assertThat(classifier.classify(new MqttException(
                MqttException.REASON_CODE_FAILED_AUTHENTICATION)))
                .isEqualTo(MqttFailureCategory.BAD_CREDENTIALS);
        assertThat(classifier.classify(new MqttTlsException("trustStore 必须配置")))
                .isEqualTo(MqttFailureCategory.TLS_CONFIGURATION);
        assertThat(classifier.classify(new IllegalStateException("wrapped",
                new MqttException(MqttException.REASON_CODE_NOT_AUTHORIZED))))
                .isEqualTo(MqttFailureCategory.NOT_AUTHORIZED);
    }
}
