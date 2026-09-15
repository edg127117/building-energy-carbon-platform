package com.platform.iot.daikin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DaikinHttpTransportTest {

    @Test
    void cancelsWholeRequestWhenBodyDoesNotCompleteBeforeDeadline() {
        HttpClient httpClient = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), anyBodyHandler())).thenReturn(pending);
        DaikinHttpTransport.Jdk transport = new DaikinHttpTransport.Jdk(httpClient);
        DaikinHttpTransport.Request request = new DaikinHttpTransport.Request("GET",
                URI.create("https://api.example.test/v2/equipments"), Map.of(), new byte[0],
                Duration.ofMillis(20));

        assertThatThrownBy(() -> transport.execute(request, 32))
                .isInstanceOf(java.net.http.HttpTimeoutException.class);
        assertThat(pending).isCancelled();
    }

    @Test
    void cancelsSubscriptionAsSoonAsBodyExceedsBudget() {
        DaikinHttpTransport.Jdk.LimitedBodySubscriber subscriber =
                new DaikinHttpTransport.Jdk.LimitedBodySubscriber(4);
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) {
            }

            @Override public void cancel() {
                cancelled.set(true);
            }
        });

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3}),
                ByteBuffer.wrap(new byte[] {4, 5})));

        assertThat(cancelled).isTrue();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(DaikinClientException.class);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<byte[]> anyBodyHandler() {
        return any(HttpResponse.BodyHandler.class);
    }
}
