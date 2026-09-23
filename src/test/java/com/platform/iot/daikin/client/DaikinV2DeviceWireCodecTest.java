package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLDecoder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaikinV2DeviceWireCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DaikinV2DeviceWireCodec codec = new DaikinV2DeviceWireCodec(mapper);
    private final DaikinClientConfiguration.Credentials credentials =
            new DaikinClientConfiguration.Credentials("test-app", "test-password", "test-sign-salt",
                    "0123456789abcdef");
    private final Instant now = Instant.parse("2026-09-23T00:00:00Z");

    @Test
    void tokenRequestSignsPlaintextAndEncryptsCompleteJsonBody() {
        var request = codec.encode(DaikinEndpoint.TOKEN, Map.of("pass", "test-password"),
                credentials, null, now);
        assertThat(request.headers()).containsEntry("appId", "test-app")
                .containsEntry("sign", DaikinProtocolCrypto.sign(
                        Map.of("pass", "test-password"), credentials.salt()));
        assertThat(DaikinProtocolCrypto.decrypt(new String(request.body(), StandardCharsets.US_ASCII),
                credentials.encryptionKey())).isEqualTo("{\"pass\":\"test-password\"}");
        assertThat(request.encryptedQuery()).isNull();
        assertThat(request.toString()).doesNotContain("test-password", "test-app");
    }

    @Test
    void devicePageEncryptsIntegerJsonIntoUnkeyedGetQuery() {
        var request = codec.encode(DaikinEndpoint.INUNITS, Map.of("page", "1"),
                credentials, "test-token", now);
        assertThat(request.headers()).containsEntry("token", "test-token")
                .containsEntry("sign", DaikinProtocolCrypto.sign(Map.of("page", "1"),
                        credentials.salt()));
        assertThat(request.body()).isEmpty();
        assertThat(request.queryParameters()).isEmpty();
        assertThat(DaikinProtocolCrypto.decrypt(request.encryptedQuery(),
                credentials.encryptionKey())).isEqualTo("{\"page\":1}");
    }

    @Test
    void actualExpireAtAndDocumentedExpiredAtAreBothAccepted() throws Exception {
        for (String key : new String[] { "expireAt", "expiredAt" }) {
            var envelope = mapper.readTree("{\"data\":{\"token\":\"t\",\"refreshKey\":\"r\",\""
                    + key + "\":\"2026-09-23T01:00:00+00:00\"}}");
            assertThat(codec.decodeToken(DaikinEndpoint.TOKEN, envelope, now).expiresAt())
                    .isEqualTo(Instant.parse("2026-09-23T01:00:00Z"));
        }
        assertThat(codec.isAuthenticationFailure(mapper.readTree("{\"code\":10010}"))).isTrue();
        assertThat(codec.isAuthenticationFailure(mapper.readTree("{\"code\":\"10011\"}"))).isTrue();
    }

    @Test
    void refusesUnconfirmedOperationsAndInvalidPageWithoutProducingRequest() {
        assertThatThrownBy(() -> codec.encode(DaikinEndpoint.INUNIT, Map.of(), credentials,
                "test-token", now)).isInstanceOf(DaikinClientException.class);
        assertThatThrownBy(() -> codec.encode(DaikinEndpoint.INUNITS, Map.of("page", "0"),
                credentials, "test-token", now)).isInstanceOf(DaikinClientException.class);
        assertThatThrownBy(() -> codec.encode(DaikinEndpoint.INUNITS,
                Map.of("page", "1", "extra", "x"), credentials, "test-token", now))
                .isInstanceOf(DaikinClientException.class);
    }

    @Test
    void readonlyClientSendsEncryptedQueryWithoutPlaintextPage() {
        List<DaikinHttpTransport.Request> requests = new ArrayList<>();
        DaikinHttpTransport transport = (request, limit) -> {
            requests.add(request);
            String body = request.uri().getPath().equals("/token")
                    ? "{\"code\":\"10000\",\"data\":{\"token\":\"t\",\"refreshKey\":\"r\","
                    + "\"expireAt\":\"2026-09-23T01:00:00+00:00\"}}"
                    : "{\"code\":\"10000\",\"data\":{\"totalCount\":0}}";
            return new DaikinHttpTransport.Response(200, body.getBytes(StandardCharsets.UTF_8));
        };
        var configuration = new DaikinClientConfiguration(true, true,
                URI.create("https://vendor.example.test"), credentials, Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ZERO, 4096, 2, 1000);
        var client = new DaikinReadonlyClient(configuration, codec, transport, mapper,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThat(client.read(DaikinEndpoint.INUNITS, null, Map.of("page", "1"))
                .path("code").asText()).isEqualTo("10000");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).uri().getPath()).isEqualTo("/token");
        String query = URLDecoder.decode(requests.get(1).uri().getRawQuery(), StandardCharsets.UTF_8);
        assertThat(DaikinProtocolCrypto.decrypt(query, credentials.encryptionKey()))
                .isEqualTo("{\"page\":1}");
        assertThat(requests.get(1).uri().toString()).doesNotContain("page=1", "test-password");
    }
}
