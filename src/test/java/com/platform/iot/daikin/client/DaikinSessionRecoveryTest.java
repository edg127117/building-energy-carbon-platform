package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 所有认证恢复仅经内存 transport；不连接厂家或现场设备。 */
class DaikinSessionRecoveryTest {
    private final MutableClock clock = new MutableClock();

    @ParameterizedTest
    @ValueSource(strings = {"401", "403", "10010", "10011"})
    void rejectedRefreshLogsInOnceAndResumesReadsOnTheSameSession(String rejection) {
        var transport = new QueueTransport(token("old"), ok(), rejection(rejection), token("new"), ok(), ok());
        var session = session(transport);
        read(session);
        clock.advance(61);

        read(session);
        read(session);

        assertThat(transport.paths()).containsExactly("/token", "/v2/equipments/inunit", "/token/refresh",
                "/token", "/v2/equipments/inunit", "/v2/equipments/inunit");
        assertThat(transport.requests.get(2).headers()).containsEntry("token", "old");
        assertThat(transport.requests.get(3).headers()).doesNotContainKey("token");
        assertThat(transport.requests.get(4).headers()).containsEntry("token", "new");
    }

    @Test
    void businessAuthenticationFailureRecoversButOnlyReplaysTheReadOnce() {
        var transport = new QueueTransport(token("old"), rejection("10010"), rejection("10011"),
                token("new"), rejection("401"), token("later"), ok());
        var session = session(transport);

        assertAuthenticationFailure(() -> read(session));
        for (int i = 0; i < 10; i++) assertAuthenticationFailure(() -> read(session));
        assertThat(transport.paths()).containsExactly("/token", "/v2/equipments/inunit", "/token/refresh",
                "/token", "/v2/equipments/inunit");
        clock.advance(30);
        read(session);
        assertThat(transport.paths().subList(5, 7)).containsExactly("/token", "/v2/equipments/inunit");
    }

    @Test
    void rejectedFullLoginHasSharedCooldownAndDoesNotReuseTheRejectedRefreshKey() {
        var transport = new QueueTransport(token("old"), ok(), rejection("401"), rejection("10010"),
                token("new"), ok());
        var session = session(transport);
        read(session);
        clock.advance(61);
        assertAuthenticationFailure(() -> read(session));
        for (int i = 0; i < 10; i++) assertAuthenticationFailure(() -> read(session));
        assertThat(transport.requests).hasSize(4);
        clock.advance(29);
        assertAuthenticationFailure(() -> read(session));
        clock.advance(1);
        read(session);
        assertThat(transport.paths()).containsExactly("/token", "/v2/equipments/inunit", "/token/refresh",
                "/token", "/token", "/v2/equipments/inunit");
    }

    @Test
    void refreshTransportAndNonAuthenticationErrorsDoNotTriggerFullLogin() {
        for (String failure : List.of("IO", "500", "90001", "MALFORMED")) {
            var transport = new QueueTransport(token("old"), ok(), ok()) {
                @Override public synchronized Response execute(Request request, int limit) throws IOException {
                    if (request.uri().getPath().equals("/token/refresh")) {
                        requests.add(request);
                        if (failure.equals("IO")) throw new IOException("private-credential");
                        if (failure.equals("500")) return new Response(500, new byte[0]);
                        if (failure.equals("MALFORMED")) return response("not-json private-credential");
                        return response("{\"code\":90001,\"message\":\"private-credential\"}");
                    }
                    return super.execute(request, limit);
                }
            };
            var session = session(transport);
            read(session);
            clock.advance(61);
            assertThatThrownBy(() -> read(session)).isInstanceOf(DaikinClientException.class)
                    .hasNoCause().hasMessageNotContaining("private-credential");
            assertThat(transport.paths()).containsExactly("/token", "/v2/equipments/inunit", "/token/refresh");
        }
    }

    @Test
    void concurrentRejectedReadsShareOneRefreshAndOneFullLogin() throws Exception {
        int readers = 6;
        var rejectedReaders = new CountDownLatch(readers);
        var logins = new AtomicInteger();
        var refreshes = new AtomicInteger();
        DaikinHttpTransport transport = (request, limit) -> {
            String path = request.uri().getPath();
            if (path.equals("/token")) return token(logins.incrementAndGet() == 1 ? "old" : "new");
            if (path.equals("/token/refresh")) { refreshes.incrementAndGet(); return rejection("10011"); }
            if ("old".equals(request.headers().get("token"))) {
                rejectedReaders.countDown();
                if (!rejectedReaders.await(3, TimeUnit.SECONDS)) throw new AssertionError("readers did not overlap");
                return rejection("10010");
            }
            return ok();
        };
        var session = session(transport);
        try (var pool = Executors.newFixedThreadPool(readers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < readers; i++) futures.add(pool.submit(() -> read(session)));
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertThat(logins).hasValue(2);
        assertThat(refreshes).hasValue(1);
    }

    @Test
    void lateAuthenticationFailureCannotClearANewerTokenGeneration() throws Exception {
        var logins = new AtomicInteger();
        var oldReads = new CountDownLatch(2);
        var releaseLateFailure = new CountDownLatch(1);
        var firstRejected = new CountDownLatch(1);
        var middleReads = new AtomicInteger();
        var racing = new java.util.concurrent.atomic.AtomicBoolean();
        DaikinHttpTransport transport = (request, limit) -> {
            if (request.uri().getPath().equals("/token")) return token(logins.incrementAndGet() == 1 ? "old" : "final");
            if (request.uri().getPath().equals("/token/refresh")) return token("middle");
            if (racing.get() && "old".equals(request.headers().get("token"))) {
                oldReads.countDown();
                if (!oldReads.await(3, TimeUnit.SECONDS)) throw new AssertionError("old reads did not overlap");
                return rejection("401");
            }
            if ("middle".equals(request.headers().get("token"))) {
                if (middleReads.incrementAndGet() == 1 && !releaseLateFailure.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("late failure was not released");
                }
                return rejection("403");
            }
            return ok();
        };
        var session = session(transport);
        read(session);
        racing.set(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                    assertAuthenticationFailure(() -> read(session));
                    firstRejected.countDown();
                }));
                assertThat(firstRejected.await(3, TimeUnit.SECONDS)).isTrue();
                clock.advance(30);
                read(session);
            } finally {
                releaseLateFailure.countDown();
            }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        read(session);
        assertThat(logins).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"401", "403"})
    void expiredAuthenticationCanRecoverBeforeSendingButNeverReplaysAPut(String rejection) {
        var transport = new QueueTransport(token("old"), ok(), rejection("10011"), token("new"), rejection(rejection));
        var session = session(transport);
        read(session);
        clock.advance(61);

        assertAuthenticationFailure(() -> session.controlOnce(DaikinEndpoint.CONTROL_ON_OFF,
                "unit-1", Map.of("onOff", "on")));
        assertThat(transport.requests.stream().filter(request -> request.method().equals("PUT"))).hasSize(1);
        assertThat(transport.paths()).containsExactly("/token", "/v2/equipments/inunit", "/token/refresh",
                "/token", "/v2/operations/onOff/unit-1");
    }

    private DaikinSession session(DaikinHttpTransport transport) {
        var configuration = new DaikinClientConfiguration(true, true, URI.create("https://vendor.example.test"),
                new DaikinClientConfiguration.Credentials("test", "test", "salt", "0123456789abcdef"),
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ZERO,
                10000, 8, 1000);
        var mapper = new ObjectMapper();
        return new DaikinSession(configuration, new DaikinControlWireCodec(mapper), transport, mapper, clock);
    }

    private static void read(DaikinSession session) {
        assertThat(session.read(DaikinEndpoint.INUNITS, null, Map.of("page", "1")).path("code").asText())
                .isEqualTo("10000");
    }

    private static void assertAuthenticationFailure(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DaikinClientException.class,
                ex -> assertThat(ex.code()).isEqualTo(DaikinClientException.Code.AUTHENTICATION_REQUIRED));
    }

    private DaikinHttpTransport.Response token(String value) {
        return response("{\"code\":10000,\"data\":{\"token\":\"" + value
                + "\",\"refreshKey\":\"refresh\",\"expireAt\":\"" + clock.instant().plusSeconds(value.equals("old") ? 60 : 3600) + "\"}}");
    }

    private static DaikinHttpTransport.Response ok() { return response("{\"code\":10000}"); }
    private static DaikinHttpTransport.Response rejection(String code) {
        return code.equals("401") || code.equals("403")
                ? new DaikinHttpTransport.Response(Integer.parseInt(code), new byte[0])
                : response("{\"code\":\"" + code + "\"}");
    }
    private static DaikinHttpTransport.Response response(String json) {
        return new DaikinHttpTransport.Response(200, json.getBytes(StandardCharsets.UTF_8));
    }

    private static class QueueTransport implements DaikinHttpTransport {
        private final Queue<Response> responses = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        QueueTransport(Response... responses) { this.responses.addAll(List.of(responses)); }
        @Override public synchronized Response execute(Request request, int limit) throws IOException {
            requests.add(request);
            return responses.remove();
        }
        List<String> paths() { return requests.stream().map(request -> request.uri().getPath()).toList(); }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-24T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
