package com.platform.adapter.profile;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/** 为配置拉取加载可选私有 CA 信任库；未配置时使用 JDK 默认可信 CA。 */
public class AdapterConfigurationSslContextFactory {

    public SSLContext create(AdapterProfileProperties.Remote properties) {
        if (properties.getTrustStore() == null || properties.getTrustStore().isBlank()) {
            try {
                return SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException("JDK缺少默认TLS上下文", exception);
            }
        }
        Path trustPath = Path.of(properties.getTrustStore()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(trustPath) || !Files.isReadable(trustPath)) {
            throw new IllegalArgumentException("adapter.profile.remote.trust-store不可读");
        }
        if (properties.getTrustStorePassword() == null
                || properties.getTrustStorePassword().isBlank()) {
            throw new IllegalArgumentException(
                    "adapter.profile.remote.trust-store-password不能为空");
        }
        char[] password = properties.getTrustStorePassword().toCharArray();
        try (InputStream input = Files.newInputStream(trustPath)) {
            KeyStore trustStore = KeyStore.getInstance(
                    properties.getTrustStoreType() == null
                            || properties.getTrustStoreType().isBlank()
                            ? "PKCS12" : properties.getTrustStoreType());
            trustStore.load(input, password);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalArgumentException("配置拉取TLS信任库加载失败", exception);
        }
    }
}
