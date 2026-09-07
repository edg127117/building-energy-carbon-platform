package com.platform.modbus.mqtt;

import com.platform.modbus.config.ModbusEdgeProperties.Tls;
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

/** 从部署环境加载信任库和可选客户端密钥库，仓库不保存生产密钥材料。 */
@Component
public class MqttSslContextFactory {

    public SSLContext create(Tls tls) {
        try {
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(load(
                    tls.getTrustStore(), tls.getTrustStorePassword(), tls.getTrustStoreType()));

            KeyManagerFactory keyManagers = null;
            if (hasText(tls.getKeyStore())) {
                keyManagers = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                KeyStore keyStore = load(
                        tls.getKeyStore(), tls.getKeyStorePassword(), tls.getKeyStoreType());
                keyManagers.init(keyStore, chars(tls.getKeyStorePassword()));
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                    keyManagers == null ? null : keyManagers.getKeyManagers(),
                    trustManagers.getTrustManagers(),
                    null);
            return context;
        } catch (GeneralSecurityException | IOException exception) {
            throw new TelemetryPublishException(
                    MqttFailureCategory.TLS_CONFIGURATION,
                    "MQTT TLS材料加载失败",
                    exception);
        }
    }

    private KeyStore load(String location, String password, String type)
            throws GeneralSecurityException, IOException {
        if (!hasText(location)) {
            throw new IOException("信任库或密钥库路径为空");
        }
        Path path = Path.of(location).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException("信任库或密钥库不可读: " + path);
        }
        KeyStore store = KeyStore.getInstance(hasText(type) ? type : "PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, chars(password));
        }
        return store;
    }

    private char[] chars(String value) {
        return value == null ? new char[0] : value.toCharArray();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
