package com.platform.iot.websocket;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class HvacRealtimeSessionRegistryTest {

    @Mock
    private HvacRealtimeAccessService access;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private HvacRealtimeSessionRegistry registry;

    @BeforeEach
    void setUp() {
        doReturn(scheduledFuture).when(taskScheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        registry = new HvacRealtimeSessionRegistry(access, taskScheduler);
    }

    @Test
    void sendsOnlyToAuthenticatedSessionsInTheTargetBuilding() throws Exception {
        Session a = session("A");
        Session b = session("B");
        registry.open(a);
        registry.open(b);
        when(access.authenticate("ta", "BLD001"))
                .thenReturn(subscription(1L, "BLD001", "ta"));
        when(access.authenticate("tb", "BLD002"))
                .thenReturn(subscription(2L, "BLD002", "tb"));
        registry.subscribe(a, "ta", "BLD001");
        registry.subscribe(b, "tb", "BLD002");

        registry.sendToBuilding("BLD001", "message-a");

        verify(a.getBasicRemote()).sendText("message-a");
        verify(b.getBasicRemote(), never()).sendText(anyString());
    }

    @Test
    void keepsPendingSessionsOutOfEveryBuildingRoute() throws Exception {
        Session pending = session("pending");
        registry.open(pending);

        registry.sendToBuilding("BLD001", "message-a");

        verify(pending.getBasicRemote(), never()).sendText(anyString());
    }

    @Test
    void resubscribeAtomicallyMovesSessionToTheNewBuilding() throws Exception {
        Session session = session("A");
        registry.open(session);
        when(access.authenticate("token-a", "BLD001"))
                .thenReturn(subscription(1L, "BLD001", "token-a"));
        when(access.authenticate("token-b", "BLD002"))
                .thenReturn(subscription(1L, "BLD002", "token-b"));

        registry.subscribe(session, "token-a", "BLD001");
        registry.subscribe(session, "token-b", "BLD002");
        registry.sendToBuilding("BLD001", "old-building");
        registry.sendToBuilding("BLD002", "new-building");

        verify(session.getBasicRemote(), never()).sendText("old-building");
        verify(session.getBasicRemote()).sendText("new-building");
    }

    @Test
    void pingRevalidatesAndReplacesTheStoredSubscription() {
        Session session = session("A");
        HvacRealtimeSubscription initial = subscription(1L, "BLD001", "old-token");
        HvacRealtimeSubscription refreshed = subscription(1L, "BLD001", "new-token");
        registry.open(session);
        when(access.authenticate("old-token", "BLD001")).thenReturn(initial);
        when(access.revalidate(initial)).thenReturn(refreshed);
        registry.subscribe(session, "old-token", "BLD001");

        HvacRealtimeSubscription result = registry.ping(session);

        assertThat(result).isEqualTo(refreshed);
        verify(access).revalidate(initial);
    }

    @Test
    void rejectsPingBeforeSubscriptionWithProtocolCloseCode() {
        Session session = session("A");
        registry.open(session);

        assertThatThrownBy(() -> registry.ping(session))
                .isInstanceOf(HvacRealtimeAccessException.class)
                .satisfies(error -> assertThat(
                        ((HvacRealtimeAccessException) error).closeCode()).isEqualTo(4400));
    }

    @Test
    void deniedPingRemovesBuildingRouteBeforeTheEndpointClosesTheTransport() throws Exception {
        Session session = session("A");
        HvacRealtimeSubscription initial = subscription(1L, "BLD001", "token");
        registry.open(session);
        when(access.authenticate("token", "BLD001")).thenReturn(initial);
        when(access.revalidate(initial)).thenThrow(new HvacRealtimeAccessException(
                "FORBIDDEN_BUILDING", 4403, "无权订阅该建筑"));
        registry.subscribe(session, "token", "BLD001");

        assertThatThrownBy(() -> registry.ping(session))
                .isInstanceOf(HvacRealtimeAccessException.class);
        registry.sendToBuilding("BLD001", "after-denial");

        verify(session.getBasicRemote(), never()).sendText("after-denial");
    }

    @Test
    void subscriptionTimeoutClosesOnlyStillPendingSession() throws Exception {
        Session session = session("A");
        registry.open(session);
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(timeout.capture(), any(Instant.class));

        timeout.getValue().run();

        verify(session).close(argThat(reason ->
                reason.getCloseCode().getCode() == HvacRealtimeProtocol.CLOSE_TIMEOUT));
    }

    @Test
    void heartbeatTimeoutClosesAuthenticatedSessionWhenNoNextPingArrives() throws Exception {
        Session session = session("A");
        registry.open(session);
        when(access.authenticate("token", "BLD001"))
                .thenReturn(subscription(1L, "BLD001", "token"));
        registry.subscribe(session, "token", "BLD001");
        ArgumentCaptor<Runnable> timeouts = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler, times(2)).schedule(timeouts.capture(), any(Instant.class));

        List<Runnable> scheduled = timeouts.getAllValues();
        scheduled.get(1).run();

        verify(session).close(argThat(reason ->
                reason.getCloseCode().getCode() == HvacRealtimeProtocol.CLOSE_TIMEOUT));
    }

    @Test
    void removeIsIdempotentAndCancelsBothTimeouts() {
        Session session = session("A");
        registry.open(session);
        when(access.authenticate("token", "BLD001"))
                .thenReturn(subscription(1L, "BLD001", "token"));
        registry.subscribe(session, "token", "BLD001");

        registry.remove(session);
        registry.remove(session);

        verify(scheduledFuture, atLeast(2)).cancel(false);
    }

    @Test
    void oneSendFailureRemovesOnlyThatSessionAndStillSendsToNextSession() throws Exception {
        Session failed = session("failed");
        Session healthy = session("healthy");
        registry.open(failed);
        registry.open(healthy);
        when(access.authenticate("failed-token", "BLD001"))
                .thenReturn(subscription(1L, "BLD001", "failed-token"));
        when(access.authenticate("healthy-token", "BLD001"))
                .thenReturn(subscription(2L, "BLD001", "healthy-token"));
        registry.subscribe(failed, "failed-token", "BLD001");
        registry.subscribe(healthy, "healthy-token", "BLD001");
        RemoteEndpoint.Basic failedRemote = failed.getBasicRemote();
        RemoteEndpoint.Basic healthyRemote = healthy.getBasicRemote();
        doThrow(new IOException("closed"))
                .when(failedRemote).sendText("message-a");

        registry.sendToBuilding("BLD001", "message-a");

        verify(healthyRemote).sendText("message-a");
        registry.sendToBuilding("BLD001", "message-b");
        verify(failedRemote, never()).sendText("message-b");
        verify(healthyRemote).sendText("message-b");
    }

    @Test
    void serializesControlSendsForOneSession() throws Exception {
        Session session = session("A");
        registry.open(session);
        RemoteEndpoint.Basic remote = session.getBasicRemote();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger activeSends = new AtomicInteger();
        AtomicInteger maxConcurrentSends = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            int active = activeSends.incrementAndGet();
            maxConcurrentSends.accumulateAndGet(active, Math::max);
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                releaseFirst.await(1, TimeUnit.SECONDS);
            } else {
                secondEntered.countDown();
            }
            activeSends.decrementAndGet();
            return null;
        }).when(remote).sendText(anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> registry.sendControl(session, "first"));
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> registry.sendControl(session, "second"));
            assertThat(secondEntered.await(150, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(maxConcurrentSends.get()).isEqualTo(1);
    }

    private Session session(String id) {
        Session session = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(session.getId()).thenReturn(id);
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getBasicRemote()).thenReturn(remote);
        return session;
    }

    private HvacRealtimeSubscription subscription(Long userId, String buildingId, String token) {
        return new HvacRealtimeSubscription(
                userId, Set.of("BUILDING_OWNER"), buildingId, token,
                System.currentTimeMillis() + 60_000L);
    }
}
