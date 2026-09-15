package com.platform.iot.daikin.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.client.*;
import com.platform.iot.daikin.mapping.DaikinDevicePageDecoder;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DaikinCatalogClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneOffset.UTC);
    private final List<String> paths = new ArrayList<>();

    @Test
    void isolatedAuthenticationPagingAndNormalizationPreserveTypedState() {
        DaikinHttpTransport transport = (request, limit) -> {
            paths.add(request.uri().getPath());
            if (request.uri().getPath().equals("/token")) return response("{\"code\":\"10000\"}");
            int page = request.uri().getRawQuery().equals("page=1") ? 1 : 2;
            return response("""
                    {"code":"10000","data":{"curPage":%d,"totalPages":2,"totalCount":2,
                    "sites":[{"siteId":"test-site","controlers":[{"lcNo":"test-lc",
                    "units":[{"unitId":"%d","onOff":"off","fanSpeed":"middleHigh","roomTemp":26.5}]}]}]}}
                    """.formatted(page, page));
        };
        var catalog = catalog(transport, true);
        var result = catalog.read("test-source", DaikinDeviceKey.Kind.INDOOR);
        assertThat(result).hasSize(2);
        assertThat(paths).containsExactly("/token", "/v2/equipments/inunit", "/v2/equipments/inunit");
        assertThat(result.getFirst().fields().get("fanSpeed").normalizedValue()).isEqualTo("middleHigh");
        assertThat(result.getFirst().fields().get("roomTemp").normalizedValue()).isNull();
    }

    @Test
    void disabledCatalogNeverAuthenticatesOrRequestsDevices() {
        var catalog = catalog((request, limit) -> {
            paths.add(request.uri().getPath());
            throw new AssertionError("disabled client must not perform I/O");
        }, false);
        assertThatThrownBy(() -> catalog.read("test-source", DaikinDeviceKey.Kind.OUTDOOR))
                .isInstanceOf(DaikinClientException.class);
        assertThat(paths).isEmpty();
    }

    @Test
    void vendorFailureDoesNotReturnAlreadyReadDevices() {
        var catalog = catalog((request, limit) -> {
            if (request.uri().getPath().equals("/token")) return response("{\"code\":\"10000\"}");
            if (request.uri().getRawQuery().equals("page=2")) {
                return response("{\"code\":\"50000\",\"codeInfo\":\"private vendor response\"}");
            }
            return response("""
                    {"code":"10000","data":{"curPage":1,"totalPages":2,"totalCount":2,
                    "sites":[{"siteId":"test-site","controlers":[{"lcNo":"test-lc",
                    "units":[{"unitId":"1"}]}]}]}}
                    """);
        }, true);
        assertThatThrownBy(() -> catalog.read("test-source", DaikinDeviceKey.Kind.OUTDOOR))
                .isInstanceOf(DaikinClientException.class)
                .hasNoCause()
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("private vendor response"));
    }

    private DaikinCatalogClient catalog(DaikinHttpTransport transport, boolean enabled) {
        var config = new DaikinClientConfiguration(enabled, true, URI.create("https://daikin.invalid"),
                new DaikinClientConfiguration.Credentials("test-app", "test-secret", "test-salt", "1234567890123456"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO,
                4096, 1, 100);
        // 隔离测试专用封装，不模拟为厂家已确认的签名或 GET 密文协议。
        DaikinWireCodec testCodec = new DaikinWireCodec() {
            @Override
            public EncodedRequest encode(DaikinEndpoint endpoint, Map<String, String> parameters,
                                          DaikinClientConfiguration.Credentials credentials,
                                          String token, Instant now) {
                return new EncodedRequest(Map.of(), parameters, new byte[0]);
            }
            @Override
            public Token decodeToken(DaikinEndpoint endpoint, JsonNode envelope, Instant now) {
                return new Token("test-token", "test-refresh", now.plusSeconds(3600));
            }
            @Override
            public boolean isAuthenticationFailure(JsonNode envelope) { return false; }
        };
        var client = new DaikinReadonlyClient(config, testCodec, transport, mapper, clock);
        var reader = new DaikinCatalogReader(new DaikinDevicePageDecoder(
                DaikinDevicePageDecoder.FieldPolicy.unconfirmed()), clock, 2, 10);
        return new DaikinCatalogClient(client, reader);
    }

    private DaikinHttpTransport.Response response(String body) {
        return new DaikinHttpTransport.Response(200, body.getBytes(StandardCharsets.UTF_8));
    }
}
