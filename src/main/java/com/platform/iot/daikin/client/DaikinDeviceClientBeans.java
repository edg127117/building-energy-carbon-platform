package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.catalog.DaikinCatalogClient;
import com.platform.iot.daikin.catalog.DaikinCatalogReader;
import com.platform.iot.daikin.mapping.DaikinDevicePageDecoder;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.sync.DaikinCatalogClientProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/** 只把显式登记的稳定来源接到厂家；缺失配置使启动失败，不回退到任意 URL 或测试客户端。 */
@Configuration
@EnableConfigurationProperties(DaikinDeviceClientProperties.class)
public class DaikinDeviceClientBeans {
    @Bean
    @ConditionalOnProperty(prefix = "daikin.device-client", name = "enabled", havingValue = "true")
    DaikinCatalogClientProvider daikinCatalogClientProvider(DaikinDeviceClientProperties properties,
                                                            ObjectMapper mapper) {
        DaikinDeviceKey.requireIdentity(properties.getSourceId());
        var credentials = new DaikinClientConfiguration.Credentials(properties.getAppId(),
                properties.getPassword(), properties.getSignatureSalt(), properties.getCommunicationKey());
        var configuration = new DaikinClientConfiguration(true, true, properties.getBaseUri(), credentials,
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofMinutes(1), 1024 * 1024, 4, 4);
        Clock clock = Clock.systemUTC();
        var client = new DaikinReadonlyClient(configuration, new DaikinV2DeviceWireCodec(mapper),
                new DaikinHttpTransport.Jdk(configuration.connectTimeout()), mapper, clock);
        var catalog = new DaikinCatalogClient(client,
                new DaikinCatalogReader(new DaikinDevicePageDecoder(
                        DaikinDevicePageDecoder.FieldPolicy.unconfirmed()), clock, 1000, 100000));
        String sourceId = properties.getSourceId();
        return requestedSource -> sourceId.equals(requestedSource) ? Optional.of(catalog) : Optional.empty();
    }
}
