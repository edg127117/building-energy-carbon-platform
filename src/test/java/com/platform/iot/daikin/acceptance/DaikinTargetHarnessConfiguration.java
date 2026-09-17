package com.platform.iot.daikin.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.catalog.DaikinCatalogClient;
import com.platform.iot.daikin.catalog.DaikinCatalogReader;
import com.platform.iot.daikin.client.DaikinClientConfiguration;
import com.platform.iot.daikin.client.DaikinEndpoint;
import com.platform.iot.daikin.client.DaikinHttpTransport;
import com.platform.iot.daikin.client.DaikinReadonlyClient;
import com.platform.iot.daikin.client.DaikinWireCodec;
import com.platform.iot.daikin.mapping.DaikinDevicePageDecoder;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.runtime.DaikinRuntimeClientProvider;
import com.platform.iot.daikin.sync.DaikinCatalogClientProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 仅由 {@link DaikinTargetHarnessApplication} 显式加入的测试装配。 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("daikin-target")
@ConditionalOnProperty(name = {"daikin.target.enabled", "daikin.target.isolated"}, havingValue = "true")
public class DaikinTargetHarnessConfiguration {

    @Bean
    @Order(100)
    ApplicationRunner daikinTargetFixtureInitializer(DaikinTargetFixture fixture) {
        return arguments -> fixture.initialize();
    }

    @Bean
    DaikinTargetVendor daikinTargetVendor() {
        return new DaikinTargetVendor();
    }

    @Bean
    DaikinCatalogClientProvider daikinTargetCatalogProvider(
            DaikinTargetVendor vendor, ObjectMapper mapper) {
        Clock clock = Clock.systemUTC();
        DaikinHttpTransport transport = (request, maximum) -> {
            String path = request.uri().getPath();
            String body;
            if (path.equals("/token") || path.equals("/token/refresh")) {
                body = "{\"code\":\"10000\"}";
            } else if (path.equals("/v2/equipments/inunit")) {
                body = vendor.indoorPage(mapper);
            } else if (path.equals("/v2/equipments/outunit")) {
                body = vendor.emptyPage();
            } else {
                throw new IllegalStateException("DAIKIN_TARGET_UNEXPECTED_TEST_PATH");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maximum) throw new IllegalStateException("DAIKIN_TARGET_RESPONSE_TOO_LARGE");
            return new DaikinHttpTransport.Response(200, bytes);
        };
        DaikinWireCodec codec = new DaikinWireCodec() {
            @Override
            public EncodedRequest encode(DaikinEndpoint endpoint, Map<String, String> parameters,
                    DaikinClientConfiguration.Credentials credentials, String accessToken, Instant now) {
                // 本地测试封装只保留生产客户端的白名单、分页和解析路径，不声称等于厂家签名协议。
                return new EncodedRequest(Map.of(), parameters, new byte[0]);
            }

            @Override
            public Token decodeToken(DaikinEndpoint endpoint, JsonNode envelope, Instant now) {
                return new Token("target-test-token", "target-test-refresh", now.plusSeconds(3600));
            }

            @Override
            public boolean isAuthenticationFailure(JsonNode envelope) {
                return false;
            }
        };
        var clientConfiguration = new DaikinClientConfiguration(true, true,
                URI.create("https://daikin-target.invalid"),
                new DaikinClientConfiguration.Credentials(
                        "target-test-app", "target-test-secret", "target-test-salt", "1234567890123456"),
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ZERO,
                64 * 1024, 2, 100);
        var client = new DaikinReadonlyClient(clientConfiguration, codec, transport, mapper, clock);
        var reader = new DaikinCatalogReader(new DaikinDevicePageDecoder(
                new DaikinDevicePageDecoder.FieldPolicy(true, null)), clock, 2, 10);
        DaikinCatalogClient catalog = new DaikinCatalogClient(client, reader);
        return sourceId -> DaikinTargetFixture.SOURCE_ID.equals(sourceId)
                ? Optional.of(catalog) : Optional.empty();
    }

    @Bean
    DaikinRuntimeClientProvider daikinTargetRuntimeProvider(DaikinTargetVendor vendor) {
        var semantics = new DaikinRuntimeClientProvider.Semantics(
                "target-synthetic-runtime-v1", "minute", ZoneId.of("Asia/Shanghai"));
        DaikinRuntimeClientProvider.Client client = new DaikinRuntimeClientProvider.Client() {
            @Override
            public DaikinRuntimeClientProvider.Semantics semantics() {
                return semantics;
            }

            @Override
            public DaikinRuntimeClientProvider.Batch read(String sourceId, DaikinDeviceKey.Kind kind,
                    com.platform.iot.daikin.runtime.DaikinRuntimePeriod period, Runnable heartbeat) {
                heartbeat.run();
                if (vendor.runtimeFailure()) throw new IllegalStateException("DAIKIN_TARGET_RUNTIME_FAILURE");
                if (kind != DaikinDeviceKey.Kind.INDOOR) {
                    return new DaikinRuntimeClientProvider.Batch(true, List.of());
                }
                var key = new DaikinDeviceKey(sourceId, DaikinTargetFixture.SITE_ID,
                        DaikinTargetFixture.CONTROLLER_ID, kind, DaikinTargetFixture.UNIT_ID);
                return new DaikinRuntimeClientProvider.Batch(false, List.of(
                        new DaikinRuntimeClientProvider.Reading(key,
                                DaikinRuntimeClientProvider.Status.PRESENT,
                                Map.of("totalRuntime", new BigDecimal("120")), true)));
            }
        };
        return sourceId -> DaikinTargetFixture.SOURCE_ID.equals(sourceId)
                ? Optional.of(client) : Optional.empty();
    }

    /** 控制面由应用内 loopback 复核；这条链只让本机驱动无需伪造业务 JWT。 */
    @Bean
    @Order(0)
    SecurityFilterChain daikinTargetControlSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/__test/daikin/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/__test/daikin/**").permitAll()
                        .anyRequest().permitAll())
                .build();
    }
}

/** 线程安全的隔离厂家输入；它不保存真实 URL、凭据或未确认 wire。 */
final class DaikinTargetVendor {
    private final AtomicBoolean equipmentFault = new AtomicBoolean();
    private final AtomicBoolean runtimeFailure = new AtomicBoolean();
    private final AtomicReference<BigDecimal> roomTemperature = new AtomicReference<>(new BigDecimal("24.5"));

    boolean equipmentFault() {
        return equipmentFault.get();
    }

    void equipmentFault(boolean value) {
        equipmentFault.set(value);
    }

    boolean runtimeFailure() {
        return runtimeFailure.get();
    }

    void runtimeFailure(boolean value) {
        runtimeFailure.set(value);
    }

    void temperature(BigDecimal value) {
        if (value == null || value.compareTo(new BigDecimal("-50")) < 0
                || value.compareTo(new BigDecimal("80")) > 0) {
            throw new IllegalArgumentException("DAIKIN_TARGET_TEMPERATURE_OUT_OF_RANGE");
        }
        roomTemperature.set(value);
    }

    BigDecimal temperature() {
        return roomTemperature.get();
    }

    String indoorPage(ObjectMapper mapper) throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> unit = new java.util.LinkedHashMap<>();
        unit.put("unitId", DaikinTargetFixture.UNIT_ID);
        unit.put("id", "TARGET-VENDOR-EQUIPMENT-1");
        unit.put("name", "测试专用大金内机");
        unit.put("onOff", "on");
        unit.put("mode", "cooling");
        unit.put("unitStatus", equipmentFault() ? "equipmentErrorOperating" : "operating");
        unit.put("fanSpeed", "middle");
        unit.put("airflowDirection", "airFlowAuto");
        unit.put("roomTemp", roomTemperature.get());
        unit.put("temperature", new BigDecimal("23.0"));
        unit.put("inCommunicationError", false);
        unit.put("inEquipmentError", equipmentFault());
        unit.put("inMantenanceMode", false);
        unit.put("isFilterDirty", false);
        unit.put("isGroupSlave", false);
        unit.put("errorCode", equipmentFault() ? "TARGET-E01" : "");
        unit.put("errorType", equipmentFault() ? 1 : 0);
        Map<String, Object> controller = new java.util.LinkedHashMap<>();
        controller.put("lcNo", DaikinTargetFixture.CONTROLLER_ID);
        controller.put("isConnectionUp", true);
        controller.put("inForcedStop", false);
        controller.put("units", List.of(unit));
        Map<String, Object> site = Map.of("siteId", DaikinTargetFixture.SITE_ID,
                "siteName", "测试专用项目", "controlers", List.of(controller));
        return mapper.writeValueAsString(Map.of("code", "10000", "resTime", Instant.now().toString(),
                "data", Map.of("curPage", 1, "totalPages", 1, "totalCount", 1,
                        "sites", List.of(site))));
    }

    String emptyPage() {
        return "{\"code\":\"10000\",\"data\":{\"curPage\":1,\"totalPages\":0,\"totalCount\":0,\"sites\":[]}}";
    }
}
