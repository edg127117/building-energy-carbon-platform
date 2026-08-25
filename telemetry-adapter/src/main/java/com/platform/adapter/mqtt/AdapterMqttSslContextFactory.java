package com.platform.adapter.mqtt;

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

/** 从部署目录加载适配器 TLS 信任库和可选客户端密钥库。 */
@Component
public class AdapterMqttSslContextFactory {

    public SSLContext create(AdapterMqttProperties.Tls properties) {
        if (properties == null || !properties.isEnabled()) {
            throw new AdapterMqttTlsException("TLS 未启用时不能创建 SSLContext");
        }
        Path trustPath = readable(properties.getTrustStore(), "trust-store");
        char[] trustPassword = password(properties.getTrustStorePassword(), "trust-store-password");
        try {
            KeyStore trustStore = load(trustPath, properties.getTrustStoreType(), trustPassword);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);

            KeyManagerFactory keyManagers = null;
            if (hasText(properties.getKeyStore())) {
                Path keyPath = readable(properties.getKeyStore(), "key-store");
                char[] keyPassword = password(properties.getKeyStorePassword(), "key-store-password");
                KeyStore keyStore = load(keyPath, properties.getKeyStoreType(), keyPassword);
                keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(keyStore, keyPassword);
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers == null ? null : keyManagers.getKeyManagers(),
                    trustManagers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException exception) {
            throw new AdapterMqttTlsException("适配器 MQTT TLS 密钥材料加载失败", exception);
        }
    }

    private KeyStore load(Path path, String type, char[] password)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(hasText(type) ? type : "PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    private Path readable(String value, String field) {
        if (!hasText(value)) {
            throw new AdapterMqttTlsException("adapter.mqtt.tls." + field + " 必须配置");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new AdapterMqttTlsException("adapter.mqtt.tls." + field + " 不可读");
        }
        return path;
    }

    private char[] password(String value, String field) {
        if (!hasText(value)) {
            throw new AdapterMqttTlsException("adapter.mqtt.tls." + field + " 必须配置");
        }
        return value.toCharArray();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
