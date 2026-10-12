package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.catalog.DaikinCatalogClient;
import com.platform.iot.daikin.catalog.DaikinCatalogReader;
import com.platform.iot.daikin.mapping.DaikinDevicePageDecoder;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.sync.DaikinCatalogClientProvider;
import com.platform.iot.daikin.control.DaikinControlGateway;
import com.platform.iot.daikin.control.DaikinControlProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/** 只把显式登记的稳定来源接到厂家；缺失配置使启动失败，不回退到任意 URL 或测试客户端。 */
@Configuration
// 普通测试只能手工装配模拟 transport，宿主机启用环境变量也不能引入真实 HTTP 客户端。
@org.springframework.context.annotation.Profile("!test")
@EnableConfigurationProperties(DaikinDeviceClientProperties.class)
public class DaikinDeviceClientBeans {
    @Bean
    @ConditionalOnProperty(prefix = "daikin.device-client", name = "enabled", havingValue = "true")
    DaikinSession daikinSession(DaikinDeviceClientProperties properties, ObjectMapper mapper,
                                DaikinControlProperties controls) {
        DaikinDeviceKey.requireIdentity(properties.getSourceId());
        var credentials = new DaikinClientConfiguration.Credentials(properties.getAppId(),
                properties.getPassword(), properties.getSignatureSalt(), properties.getCommunicationKey());
        var configuration = new DaikinClientConfiguration(true, true, properties.getBaseUri(), credentials,
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofMinutes(1), 1024 * 1024, 4, 4);
        Clock clock = Clock.systemUTC();
        DaikinWireCodec codec = controls.isEnabled() && controls.isWireConfirmed()
                ? new DaikinControlWireCodec(mapper) : new DaikinV2DeviceWireCodec(mapper);
        return new DaikinSession(configuration, codec, new DaikinHttpTransport.Jdk(configuration.connectTimeout()), mapper, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "daikin.device-client", name = "enabled", havingValue = "true")
    DaikinCatalogClientProvider daikinCatalogClientProvider(DaikinDeviceClientProperties properties, DaikinSession session) {
        var catalog = new DaikinCatalogClient(new DaikinReadonlyClient(session),
                new DaikinCatalogReader(deviceDecoder(properties), Clock.systemUTC(), 1000, 100000));
        String sourceId = properties.getSourceId();
        return requestedSource -> sourceId.equals(requestedSource) ? Optional.of(catalog) : Optional.empty();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "${daikin.device-client.enabled:false} && ${daikin.control.enabled:false} && ${daikin.control.wire-confirmed:false}")
    DaikinControlGateway daikinControlGateway(DaikinDeviceClientProperties properties, DaikinSession session) {
        return new DaikinControlClient(properties.getSourceId(), session, properties.isTemperatureCelsiusConfirmed());
    }

    static DaikinDevicePageDecoder deviceDecoder(DaikinDeviceClientProperties properties) {
        return new DaikinDevicePageDecoder(new DaikinDevicePageDecoder.FieldPolicy(
                properties.isTemperatureCelsiusConfirmed()));
    }
}
