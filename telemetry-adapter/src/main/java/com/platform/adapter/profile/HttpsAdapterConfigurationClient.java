package com.platform.adapter.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Envelope;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Receipt;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 使用独立适配器凭据主动访问平台 HTTPS 发布接口。 */
public class HttpsAdapterConfigurationClient implements AdapterConfigurationClient {

    static final String ADAPTER_KEY_HEADER = "X-Adapter-Key";
    static final String SCHEMA_HEADER = "X-Adapter-Schema-Version";
    static final String OUTPUT_HEADER = "X-Adapter-Output-Version";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AdapterProfileProperties properties;
    private final URI configurationUri;

    public HttpsAdapterConfigurationClient(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            AdapterProfileProperties properties) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.properties = properties;
        this.configurationUri = configurationUri(properties);
    }

    @Override
    public Optional<Envelope> fetch() throws IOException, InterruptedException {
        HttpRequest request = request(configurationUri).GET().build();
        HttpResponse<byte[]> response = sendBounded(request);
        if (response.statusCode() == 204 || response.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(response.statusCode());
        JsonNode root = objectMapper.readTree(response.body());
        if (root == null || !root.isObject()
                || root.has("success") && !root.path("success").asBoolean(false)) {
            throw new IOException("配置服务返回失败结果");
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || data.isNull()) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.treeToValue(data, Envelope.class));
    }

    @Override
    public void sendReceipt(Receipt receipt) throws IOException, InterruptedException {
        HttpRequest request = request(URI.create(configurationUri + "/receipts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(receipt)))
                .build();
        HttpResponse<byte[]> response = sendBounded(request);
        requireSuccess(response.statusCode());
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(positive(properties.getRemote().getRequestTimeoutMillis())))
                .header(ADAPTER_KEY_HEADER, required(
                        properties.getRemote().getAdapterKey(), "remote.adapter-key"))
                .header(SCHEMA_HEADER, "1")
                .header(OUTPUT_HEADER, required(properties.getOutputVersion(), "outputVersion"))
                .header("Accept", "application/json");
    }

    private HttpResponse<byte[]> sendBounded(HttpRequest request)
            throws IOException, InterruptedException {
        int limit = positive(properties.getRemote().getMaxResponseBytes());
        int timeoutMillis = positive(properties.getRemote().getRequestTimeoutMillis());
        CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(
                request, ignored -> new LimitedBodySubscriber(limit));
        try {
            HttpResponse<byte[]> response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response.body() != null && response.body().length > limit) {
                throw new IOException("配置服务响应超过大小限制");
            }
            return response;
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IOException("配置服务响应正文超时", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("配置服务请求失败", cause);
        }
    }

    private static void requireSuccess(int statusCode) throws IOException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("配置服务返回非成功状态: " + statusCode);
        }
    }

    private static URI configurationUri(AdapterProfileProperties properties) {
        URI base = URI.create(required(properties.getRemote().getBaseUrl(), "remote.base-url"));
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null) {
            throw new IllegalArgumentException("adapter.profile.base-url必须是无查询参数的HTTPS地址");
        }
        String targetId = required(properties.getRemote().getTargetId(), "remote.target-id");
        String encodedTarget = URLEncoder.encode(targetId, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String root = base.toString().replaceAll("/+$", "");
        return URI.create(root + "/v1/adapter-configurations/" + encodedTarget);
    }

    private static int positive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("超时和响应大小限制必须为正数");
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("adapter.profile." + field + "不能为空");
        }
        return value.trim();
    }

    /** 在订阅响应正文时即限制累计字节，避免先无限缓冲再检查大小。 */
    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int remaining = buffer.remaining();
                    if (remaining > limit - output.size()) {
                        subscription.cancel();
                        body.completeExceptionally(new IOException("配置服务响应超过大小限制"));
                        return;
                    }
                    byte[] bytes = new byte[remaining];
                    buffer.get(bytes);
                    output.write(bytes, 0, bytes.length);
                }
                subscription.request(1);
            } catch (RuntimeException exception) {
                subscription.cancel();
                body.completeExceptionally(exception);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }
}
