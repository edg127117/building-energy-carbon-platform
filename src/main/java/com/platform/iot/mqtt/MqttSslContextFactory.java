package com.platform.iot.mqtt;

import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

@Component
/** 从外部 truststore/keystore 构造 JSSE 上下文，禁止隐式信任全部证书。 */
public class MqttSslContextFactory {

    public SSLContext create(MqttTlsProperties properties) {
        if (!properties.isEnabled()) {
            throw new MqttTlsException("TLS 未启用时不能创建 SSLContext");
        }
        Path trustStorePath = requiredReadableFile(properties.getTrustStore(), "trustStore");
        char[] trustPassword = requiredPassword(
                properties.getTrustStorePassword(), "trustStorePassword");
        try {
            KeyStore trustStore = loadStore(
                    trustStorePath, properties.getTrustStoreType(), trustPassword);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);

            KeyManagerFactory keyManagers = null;
            if (hasText(properties.getKeyStore())) {
                Path keyStorePath = requiredReadableFile(properties.getKeyStore(), "keyStore");
                char[] keyPassword = requiredPassword(
                        properties.getKeyStorePassword(), "keyStorePassword");
                KeyStore keyStore = loadStore(
                        keyStorePath, properties.getKeyStoreType(), keyPassword);
                keyManagers = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(keyStore, keyPassword);
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers == null ? null : keyManagers.getKeyManagers(),
                    trustManagers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException exception) {
            throw new MqttTlsException("MQTT TLS 密钥材料加载失败", exception);
        }
    }

    private KeyStore loadStore(Path path, String type, char[] password)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(hasText(type) ? type : "PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    private Path requiredReadableFile(String value, String field) {
        if (!hasText(value)) {
            throw new MqttTlsException("mqtt.tls." + field + " 必须配置");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new MqttTlsException("mqtt.tls." + field + " 不可读");
        }
        return path;
    }

    private char[] requiredPassword(String value, String field) {
        if (!hasText(value)) {
            throw new MqttTlsException("mqtt.tls." + field + " 必须配置");
        }
        return value.toCharArray();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
