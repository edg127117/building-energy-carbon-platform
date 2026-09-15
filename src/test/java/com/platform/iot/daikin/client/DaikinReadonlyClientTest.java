package com.platform.iot.daikin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DaikinReadonlyClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void staysClosedWithoutExplicitEnablementAndConfirmedWire() {
        AtomicInteger calls = new AtomicInteger();
        DaikinHttpTransport transport = (request, limit) -> {
            calls.incrementAndGet();
            throw new AssertionError("disabled client must not call transport");
        };
        DaikinReadonlyClient disabled = new DaikinReadonlyClient(
                DaikinClientConfiguration.disabled(URI.create("https://api.example.test")), null,
                transport, JSON, CLOCK);

        assertThatThrownBy(() -> disabled.read(DaikinEndpoint.INUNITS, null, Map.of()))
                .isInstanceOfSatisfying(DaikinClientException.class,
                        ex -> assertThat(ex.code()).isEqualTo(DaikinClientException.Code.CLIENT_DISABLED));
        assertThat(calls).hasValue(0);

        DaikinReadonlyClient unconfirmed = new DaikinReadonlyClient(configuration(false), new TestWireCodec(),
                transport, JSON, CLOCK);
        assertThatThrownBy(() -> unconfirmed.read(DaikinEndpoint.INUNITS, null, Map.of()))
                .isInstanceOfSatisfying(DaikinClientException.class,
                        ex -> assertThat(ex.code()).isEqualTo(DaikinClientException.Code.WIRE_NOT_CONFIRMED));
        assertThat(calls).hasValue(0);
    }

    @Test
    void obtainsTokenThenReturnsCompleteSuccessfulEnvelope() {
        QueueTransport transport = new QueueTransport(
                response("{\"code\":10000,\"data\":{\"access\":\"secret-token\"}}"),
                response("{\"code\":\"10000\",\"data\":{\"items\":[{\"id\":1}]}}"));
        DaikinReadonlyClient client = client(transport, new TestWireCodec());

        JsonNode result = client.read(DaikinEndpoint.INUNITS, null, Map.of("page", "2"));

        assertThat(result.path("data").path("items").get(0).path("id").asInt()).isEqualTo(1);
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.get(0).uri().toString()).isEqualTo("https://api.example.test/token");
        assertThat(transport.requests.get(1).uri().toString())
                .isEqualTo("https://api.example.test/v2/equipments/inunit?page=2");
        assertThat(transport.requests.get(1).headers()).containsEntry("Authorization", "Bearer secret-token");
    }

    @Test
    void retriesAuthenticationOnlyOnceWithCoordinatedNewToken() {
        QueueTransport transport = new QueueTransport(
                response("{\"code\":10000}"),
                new DaikinHttpTransport.Response(401, new byte[0]),
                response("{\"code\":10000}"),
                response("{\"code\":10000,\"data\":[]}"));
        TestWireCodec codec = new TestWireCodec();
        DaikinReadonlyClient client = client(transport, codec);

        assertThat(client.read(DaikinEndpoint.EQUIPMENTS, null, Map.of()).path("code").asInt())
                .isEqualTo(10000);
        assertThat(transport.requests).hasSize(4);
        assertThat(transport.requests.get(2).uri().toString()).isEqualTo("https://api.example.test/token/refresh");
        assertThat(transport.requests.get(2).headers()).containsEntry("Authorization", "Bearer secret-token");
        assertThat(codec.calls.get(2).parameters()).containsEntry("refreshKey", "refresh-key");
        assertThat(codec.calls.get(2).accessToken()).isEqualTo("secret-token");
    }

    @Test
    void rejectsNonWhitelistAccessAndDoesNotLeakRemoteBody() {
        QueueTransport transport = new QueueTransport(
                response("{\"code\":10000}"),
                response("{\"code\":90001,\"message\":\"credential=top-secret\"}"));
        DaikinReadonlyClient client = client(transport, new TestWireCodec());

        assertThatThrownBy(() -> client.read(DaikinEndpoint.TOKEN, null, Map.of()))
                .isInstanceOfSatisfying(DaikinClientException.class,
                        ex -> assertThat(ex.code()).isEqualTo(DaikinClientException.Code.INVALID_REQUEST));
        assertThatThrownBy(() -> client.read(DaikinEndpoint.INUNIT, "unit/../other", Map.of()))
                .isInstanceOfSatisfying(DaikinClientException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(DaikinClientException.Code.INVALID_REQUEST);
                });
        assertThat(transport.requests).hasSize(0);

        assertThatThrownBy(() -> client.read(DaikinEndpoint.INUNIT, "unit-1", Map.of()))
                .isInstanceOfSatisfying(DaikinClientException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(DaikinClientException.Code.REMOTE_REJECTED);
                    assertThat(ex.getMessage()).doesNotContain("top-secret", "credential");
                });
    }

    @Test
    void reportsMalformedAndOversizedResponsesWithStableCodes() {
        QueueTransport malformed = new QueueTransport(response("not-json"));
        assertThatThrownBy(() -> client(malformed, new TestWireCodec())
                .read(DaikinEndpoint.INUNITS, null, Map.of()))
                .isInstanceOfSatisfying(DaikinClientException.class,
                        ex -> assertThat(ex.code()).isEqualTo(DaikinClientException.Code.INVALID_RESPONSE));

        DaikinHttpTransport oversized = (request, limit) -> {
            throw new DaikinClientException(DaikinClientException.Code.RESPONSE_TOO_LARGE);
        };
        assertThatThrownBy(() -> client(oversized, new TestWireCodec())
                .read(DaikinEndpoint.INUNITS, null, Map.of()))
                .isInstanceOfSatisfying(DaikinClientException.class,
                        ex -> assertThat(ex.code()).isEqualTo(DaikinClientException.Code.RESPONSE_TOO_LARGE));
    }

    @Test
    void rejectsDuplicateKeysTrailingDocumentsAndIntegerOverflowWithoutLeakingBody() {
        for (String body : List.of("{\"code\":50000,\"code\":10000}",
                "{\"code\":10000} {\"secret\":\"private-value\"}", "{\"code\":4294977296}")) {
            var transport = new QueueTransport(response(body));
            assertThatThrownBy(() -> client(transport, new TestWireCodec())
                    .read(DaikinEndpoint.INUNITS, null, Map.of()))
                    .isInstanceOf(DaikinClientException.class).hasNoCause()
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain("private-value"));
            assertThat(transport.requests).hasSize(1);
        }
    }

    @Test
    void injectedTransportCannotBypassBodyLimitAndCredentialDiagnosticsAreRedacted() {
        var transport = new QueueTransport(response("x".repeat(1025)));
        assertThatThrownBy(() -> client(transport, new TestWireCodec())
                .read(DaikinEndpoint.INUNITS, null, Map.of()))
                .isInstanceOfSatisfying(DaikinClientException.class,
                        error -> assertThat(error.code()).isEqualTo(DaikinClientException.Code.RESPONSE_TOO_LARGE));
        var credentials = new DaikinClientConfiguration.Credentials("private-app", "private-secret",
                "private-salt", "0123456789abcdef");
        assertThat(credentials.toString()).doesNotContain("private", "0123456789abcdef");
        assertThat(new DaikinWireCodec.Token("private-access", "private-refresh", CLOCK.instant())
                .toString()).doesNotContain("private");
        assertThat(new DaikinHttpTransport.Request("GET", URI.create("https://api.example.test/?secret=private"),
                Map.of("token", "private"), new byte[0], Duration.ofSeconds(1)).toString()).doesNotContain("private");
    }

    @Test
    void coordinatesConcurrentTokenAcquisition() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        DaikinHttpTransport transport = (request, limit) -> {
            if (request.uri().getPath().equals("/token")) {
                tokenCalls.incrementAndGet();
                Thread.sleep(20);
            }
            return response("{\"code\":10000,\"data\":[]}");
        };
        DaikinReadonlyClient client = client(transport, new TestWireCodec());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<JsonNode>> calls = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(ignored -> CompletableFuture.supplyAsync(
                            () -> client.read(DaikinEndpoint.INUNITS, null, Map.of()), executor))
                    .toList();
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
        } finally {
            executor.shutdownNow();
        }

        assertThat(tokenCalls).hasValue(1);
    }

    private static DaikinReadonlyClient client(DaikinHttpTransport transport, DaikinWireCodec codec) {
        return new DaikinReadonlyClient(configuration(true), codec, transport, JSON, CLOCK);
    }

    private static DaikinClientConfiguration configuration(boolean wireConfirmed) {
        return new DaikinClientConfiguration(true, wireConfirmed, URI.create("https://api.example.test"),
                new DaikinClientConfiguration.Credentials("account", "secret", "salt", "0123456789abcdef"),
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ZERO,
                1024, 2, 1_000);
    }

    private static DaikinHttpTransport.Response response(String json) {
        return new DaikinHttpTransport.Response(200, json.getBytes(StandardCharsets.UTF_8));
    }

    private static final class QueueTransport implements DaikinHttpTransport {
        private final Queue<Response> responses = new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();

        private QueueTransport(Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public synchronized Response execute(Request request, int maxResponseBytes) {
            requests.add(request);
            return responses.remove();
        }
    }

    private static final class TestWireCodec implements DaikinWireCodec {
        private int tokenSequence;
        private final List<WireCall> calls = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public EncodedRequest encode(DaikinEndpoint endpoint, Map<String, String> parameters,
                                     DaikinClientConfiguration.Credentials credentials, String accessToken,
                                     Instant now) {
            calls.add(new WireCall(endpoint, parameters, accessToken));
            Map<String, String> headers = accessToken == null
                    ? Map.of() : Map.of("Authorization", "Bearer " + accessToken);
            return new EncodedRequest(headers, endpoint.method().equals("GET") ? parameters : Map.of(), new byte[0]);
        }

        @Override
        public Token decodeToken(DaikinEndpoint endpoint, JsonNode successfulEnvelope, Instant now) {
            tokenSequence++;
            return new Token("secret-token" + (tokenSequence == 1 ? "" : "-" + tokenSequence), "refresh-key",
                    now.plusSeconds(3600));
        }

        @Override
        public boolean isAuthenticationFailure(JsonNode envelope) {
            return envelope.path("code").asInt() == 401;
        }

        private record WireCall(DaikinEndpoint endpoint, Map<String, String> parameters, String accessToken) {
        }
    }
}
