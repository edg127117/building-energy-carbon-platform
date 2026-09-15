package com.platform.iot.daikin.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 可替换的 HTTP 边界。JDK 实现保留平台默认 TLS 校验，禁止重定向，并限制整体时长及响应体大小。
 */
public interface DaikinHttpTransport {

    Response execute(Request request, int maxResponseBytes) throws IOException, InterruptedException;

    record Request(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout) {
        public Request {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "Request[method=" + method + ", uri=<redacted>, headers=<redacted>, body=<redacted>, timeout="
                    + timeout + "]";
        }
    }

    record Response(int statusCode, byte[] body) {
        public Response {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "Response[statusCode=" + statusCode + ", body=<redacted>]";
        }
    }

    final class Jdk implements DaikinHttpTransport {
        private final HttpClient client;

        public Jdk(Duration connectTimeout) {
            this(HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build());
        }

        Jdk(HttpClient client) {
            this.client = Objects.requireNonNull(client, "client");
        }

        @Override
        public Response execute(Request request, int maxResponseBytes) throws IOException, InterruptedException {
            if (!"https".equalsIgnoreCase(request.uri().getScheme())) {
                throw new DaikinClientException(DaikinClientException.Code.INSECURE_TARGET);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
            request.headers().forEach(builder::header);
            HttpRequest.BodyPublisher publisher = request.body().length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(request.body());
            builder.method(request.method(), publisher);
            CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(builder.build(),
                    ignored -> new LimitedBodySubscriber(maxResponseBytes));
            try {
                HttpResponse<byte[]> response = future.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
                return new Response(response.statusCode(), response.body());
            } catch (TimeoutException ex) {
                future.cancel(true);
                throw new HttpTimeoutException("大金 HTTP 整体请求超时");
            } catch (InterruptedException ex) {
                future.cancel(true);
                throw ex;
            } catch (ExecutionException ex) {
                Throwable cause = rootCause(ex);
                if (cause instanceof DaikinClientException clientException) {
                    throw clientException;
                }
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("大金 HTTP 请求执行失败");
            }
        }

        private static Throwable rootCause(Throwable throwable) {
            Throwable current = throwable;
            while (current.getCause() != null && current.getCause() != current) {
                current = current.getCause();
            }
            return current;
        }

        static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
            private final int limit;
            private final CompletableFuture<byte[]> body = new CompletableFuture<>();
            private final ByteArrayOutputStream output = new ByteArrayOutputStream();
            private Flow.Subscription subscription;
            private int received;

            LimitedBodySubscriber(int limit) {
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
                for (ByteBuffer buffer : buffers) {
                    int size = buffer.remaining();
                    if (received > limit - size) {
                        subscription.cancel();
                        body.completeExceptionally(
                                new DaikinClientException(DaikinClientException.Code.RESPONSE_TOO_LARGE));
                        return;
                    }
                    byte[] chunk = new byte[size];
                    buffer.get(chunk);
                    output.writeBytes(chunk);
                    received += size;
                }
                subscription.request(1);
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
}
