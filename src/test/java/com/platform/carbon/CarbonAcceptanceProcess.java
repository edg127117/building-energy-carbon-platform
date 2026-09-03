package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 子 JVM 的启动、HTTP 和强制终止；日志保留在 target，不以内存状态模拟重启。 */
final class CarbonAcceptanceProcess implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final Path EVIDENCE = Path.of("target", "carbon-acceptance", "evidence");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Process process;
    private final int port;
    private final Path log;

    CarbonAcceptanceProcess(String label, String... overrides) throws Exception {
        Files.createDirectories(EVIDENCE);
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        log = EVIDENCE.resolve(label + "-" + System.nanoTime() + ".log");
        List<String> arguments = new ArrayList<>(List.of("-Xmx384m", "-Duser.timezone=Asia/Shanghai",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                CarbonAcceptanceApplication.class.getName(),
                "--spring.config.location=optional:classpath:/carbon-acceptance-empty.yml",
                "--spring.main.banner-mode=off", "--spring.jmx.enabled=false",
                "--server.address=127.0.0.1", "--server.port=" + port,
                "--server.servlet.context-path=/api", "--server.tomcat.threads.max=8",
                "--server.tomcat.threads.min-spare=2", "--server.tomcat.max-connections=64",
                "--server.tomcat.accept-count=32", "--carbon-management.recalculation-enabled=false",
                "--carbon-management.recalculation-scan-delay=200ms",
                "--carbon-management.recalculation-lease=3s", "--carbon-management.retry-backoff=500ms",
                "--carbon-management.maximum-batch-items=10", "--carbon-management.maximum-retries=2",
                "--logging.level.org.springframework.web=ERROR"));
        for (String override : overrides) {
            String option = override.substring(0, override.indexOf('=') + 1);
            arguments.removeIf(argument -> argument.startsWith(option));
            arguments.add(override);
        }
        Path argfile = EVIDENCE.resolve(label + "-" + System.nanoTime() + ".args");
        Files.write(argfile, arguments.stream().map(value -> "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"").toList());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        process = new ProcessBuilder(java, "@" + argfile.toAbsolutePath())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new AssertionError("Child boot failed: " + Files.readString(log));
            try {
                if (get("/__acceptance/metrics").status() == 200) return;
            } catch (IOException ignored) { }
            Thread.sleep(100);
        }
        close();
        throw new AssertionError("Child boot timeout: " + log);
    }

    long pid() { return process.pid(); }
    boolean alive() { return process.isAlive(); }
    Path log() { return log; }
    Reply post(String path, Object body) throws Exception { return post(path, body, 9001); }
    Reply post(String path, Object body, long user) throws Exception {
        return request(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .header("X-Acceptance-User", Long.toString(user))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }
    Reply get(String path) throws Exception { return request(HttpRequest.newBuilder(uri(path)).GET()); }
    Reply step(String action) throws Exception { return post("/__acceptance/step/" + action, Map.of()); }
    Reply run(String building, int year, String nature, String key) throws Exception {
        return post("/v1/carbon-management/calculations",
                CarbonAcceptanceFixture.calculation(building, year, nature, key));
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api" + path); }
    private Reply request(HttpRequest.Builder builder) throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = client.send(builder.timeout(Duration.ofSeconds(45)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body().isBlank() ? JSON.nullNode()
                : JSON.readTree(response.body()), (System.nanoTime() - start) / 1_000_000.0);
    }
    void kill() throws Exception {
        long pid = process.pid();
        process.destroyForcibly();
        assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        evidence("kill-" + pid, Map.of("pid", pid, "aliveAfter", process.isAlive(), "log", log.toString()));
        client.close();
    }
    @Override public void close() throws Exception {
        if (process.isAlive()) kill();
        client.close();
    }
    static void evidence(String name, Object value) throws IOException {
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve(name + ".json"), JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(value));
    }
    record Reply(int status, JsonNode body, double elapsedMs) {
        JsonNode data() { return body.path("data"); }
    }
}
