package com.platform.iot.mqtt;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertPathValidatorException;
import java.util.Locale;

@Component
/** 将异常链压缩为稳定分类；日志不得输出密码、私钥或完整证书。 */
public class MqttFailureClassifier {

    public MqttFailureCategory classify(Throwable failure) {
        if (contains(failure, CertificateExpiredException.class)
                || contains(failure, CertificateNotYetValidException.class)) {
            return MqttFailureCategory.CERTIFICATE_TIME_INVALID;
        }
        if (contains(failure, SSLPeerUnverifiedException.class)
                || messageContains(failure, "hostname")
                || messageContains(failure, "subject alternative")) {
            return MqttFailureCategory.HOSTNAME_MISMATCH;
        }
        if (contains(failure, CertPathValidatorException.class)
                || messageContains(failure, "unable to find valid certification path")
                || messageContains(failure, "unknown ca")) {
            return MqttFailureCategory.CA_UNTRUSTED;
        }
        if (contains(failure, SSLHandshakeException.class)
                && (messageContains(failure, "bad certificate")
                || messageContains(failure, "certificate required"))) {
            return MqttFailureCategory.CLIENT_CERTIFICATE_REJECTED;
        }
        if (contains(failure, UnknownHostException.class)) {
            return MqttFailureCategory.DNS_FAILURE;
        }
        if (contains(failure, SocketTimeoutException.class)) {
            return MqttFailureCategory.TIMEOUT;
        }
        if (contains(failure, ConnectException.class)) {
            return MqttFailureCategory.CONNECTION_REFUSED;
        }
        if (contains(failure, MqttTlsException.class)) {
            return MqttFailureCategory.TLS_CONFIGURATION;
        }
        MqttException mqttException = find(failure, MqttException.class);
        if (mqttException != null) {
            return switch (mqttException.getReasonCode()) {
                case MqttException.REASON_CODE_FAILED_AUTHENTICATION ->
                        MqttFailureCategory.BAD_CREDENTIALS;
                case MqttException.REASON_CODE_NOT_AUTHORIZED ->
                        MqttFailureCategory.NOT_AUTHORIZED;
                case MqttException.REASON_CODE_SERVER_CONNECT_ERROR,
                     MqttException.REASON_CODE_CLIENT_NOT_CONNECTED,
                     MqttException.REASON_CODE_CONNECTION_LOST ->
                        MqttFailureCategory.BROKER_UNAVAILABLE;
                case MqttException.REASON_CODE_CLIENT_TIMEOUT -> MqttFailureCategory.TIMEOUT;
                case MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION ->
                        MqttFailureCategory.PROTOCOL_ERROR;
                default -> MqttFailureCategory.UNKNOWN;
            };
        }
        return MqttFailureCategory.UNKNOWN;
    }

    private boolean messageContains(Throwable failure, String expected) {
        String needle = expected.toLowerCase(Locale.ROOT);
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private <T extends Throwable> T find(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}
