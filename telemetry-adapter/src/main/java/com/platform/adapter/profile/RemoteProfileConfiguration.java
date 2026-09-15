package com.platform.adapter.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import javax.net.ssl.SSLContext;

/** 仅在远程快照模式创建平台 HTTPS 客户端。 */
@Configuration
@ConditionalOnProperty(prefix = "adapter.profile", name = "mode", havingValue = "remote")
public class RemoteProfileConfiguration {

    @Bean
    public HttpClient adapterConfigurationHttpClient(AdapterProfileProperties properties) {
        if (properties.getRemote().getConnectTimeoutMillis() <= 0) {
            throw new IllegalArgumentException("adapter.profile.connect-timeout-millis必须为正数");
        }
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getRemote().getConnectTimeoutMillis()))
                .sslContext(adapterConfigurationSslContextFactory().create(properties.getRemote()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    public AdapterConfigurationSslContextFactory adapterConfigurationSslContextFactory() {
        return new AdapterConfigurationSslContextFactory();
    }

    @Bean
    public AdapterConfigurationClient adapterConfigurationClient(
            ObjectMapper objectMapper,
            HttpClient adapterConfigurationHttpClient,
            AdapterProfileProperties properties) {
        return new HttpsAdapterConfigurationClient(
                objectMapper, adapterConfigurationHttpClient, properties);
    }
}
