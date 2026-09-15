package com.platform.adapter.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Envelope;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpsAdapterConfigurationClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appendsPathToApiBaseAndSendsIdentityAndCapabilityHeaders() throws Exception {
        FakeHttpClient http = new FakeHttpClient(200,
                "{\"success\":true,\"code\":200,\"data\":null}");
        AdapterProfileProperties properties = properties("https://localhost:8444/api");
        HttpsAdapterConfigurationClient client = new HttpsAdapterConfigurationClient(
                objectMapper, http, properties);

        assertThat(client.fetch()).isEmpty();

        assertThat(http.request.uri()).isEqualTo(URI.create(
                "https://localhost:8444/api/v1/adapter-configurations/target-1"));
        assertThat(http.request.headers().firstValue("X-Adapter-Key"))
                .contains("independent-secret");
        assertThat(http.request.headers().firstValue("X-Adapter-Schema-Version")).contains("1");
        assertThat(http.request.headers().firstValue("X-Adapter-Output-Version")).contains("V2");
    }

    @Test
    void readsOrdinaryResultDataEnvelope() throws Exception {
        Envelope envelope = new Envelope(7, "a".repeat(64), "{\"schemaVersion\":1}");
        String body = objectMapper.writeValueAsString(new Result(envelope));
        FakeHttpClient http = new FakeHttpClient(200, body);
        HttpsAdapterConfigurationClient client = new HttpsAdapterConfigurationClient(
                objectMapper, http, properties("https://platform.example/api"));

        assertThat(client.fetch()).contains(envelope);
    }

    @Test
    void refusesPlainHttpAndOversizedResponses() {
        assertThatThrownBy(() -> new HttpsAdapterConfigurationClient(
                objectMapper, new FakeHttpClient(200, "{}"),
                properties("http://localhost:8081/api")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");

        AdapterProfileProperties properties = properties("https://localhost:8444/api");
        properties.getRemote().setMaxResponseBytes(4);
        HttpsAdapterConfigurationClient client = new HttpsAdapterConfigurationClient(
                objectMapper, new FakeHttpClient(200, "12345"), properties);
        assertThatThrownBy(client::fetch)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("大小限制");
    }

    @Test
    void cancelsRequestWhenResponseBodyDoesNotCompleteBeforeDeadline() {
        AdapterProfileProperties properties = properties("https://localhost:8444/api");
        properties.getRemote().setRequestTimeoutMillis(20);
        FakeHttpClient http = new FakeHttpClient();
        HttpsAdapterConfigurationClient client = new HttpsAdapterConfigurationClient(
                objectMapper, http, properties);

        assertThatThrownBy(client::fetch)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("超时");
        assertThat(http.pendingFuture).isCancelled();
    }

    private AdapterProfileProperties properties(String baseUrl) {
        AdapterProfileProperties properties = new AdapterProfileProperties();
        properties.setOutputVersion("V2");
        properties.getRemote().setBaseUrl(baseUrl);
        properties.getRemote().setTargetId("target-1");
        properties.getRemote().setAdapterKey("independent-secret");
        return properties;
    }

    private record Result(Envelope data) {
    }

    private static final class FakeHttpClient extends HttpClient {
        private final int status;
        private final byte[] body;
        private final boolean stalled;
        private HttpRequest request;
        private CompletableFuture<HttpResponse<byte[]>> pendingFuture;

        private FakeHttpClient(int status, String body) {
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
            this.stalled = false;
        }

        private FakeHttpClient() {
            this.status = 200;
            this.body = new byte[0];
            this.stalled = true;
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            this.request = request;
            @SuppressWarnings("unchecked")
            T responseBody = (T) new ByteArrayInputStream(body);
            return new FakeResponse<>(request, status, responseBody);
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            this.request = request;
            if (stalled) {
                pendingFuture = new CompletableFuture<>();
                @SuppressWarnings("unchecked")
                CompletableFuture<HttpResponse<T>> typed =
                        (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) pendingFuture;
                return typed;
            }
            @SuppressWarnings("unchecked")
            T responseBody = (T) body;
            return CompletableFuture.completedFuture(
                    new FakeResponse<>(request, status, responseBody));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private record FakeResponse<T>(HttpRequest request, int statusCode, T body)
            implements HttpResponse<T> {
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
    }
}
