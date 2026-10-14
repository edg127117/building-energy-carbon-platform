package com.platform.iot.daikin.control;

import com.platform.iot.daikin.client.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** 真实业务编排、H2事务和协议适配器串接；HTTP transport 完全内存模拟，不代表MySQL或现场验收。 */
class DaikinControlOfflineFlowTest {
    @Test void allFiveActionsTraverseEncryptedWirePersistenceAndReadbackWithoutNetwork() throws Exception {
        var f = new ControlFixture();
        var writes = new AtomicInteger();
        DaikinHttpTransport transport = (request, limit) -> {
            String path = request.uri().getPath();
            if (path.equals("/token")) return response("""
                    {"code":"10000","data":{"token":"offline-token","refreshKey":"offline-refresh","expireAt":"2099-01-01T00:00:00Z"}}
                    """);
            if (request.method().equals("PUT")) {
                assertThat(path).endsWith("/vendor-22");
                var params = f.json.readTree(DaikinProtocolCrypto.decrypt(new String(request.body(), StandardCharsets.US_ASCII), "0123456789abcdef"));
                String key = params.fieldNames().next();
                assertThat(path).isEqualTo("/v2/operations/" + key + "/vendor-22");
                String observed = key.equals("airFlow") ? "airflowDirection" : key;
                if (key.equals("temperature")) f.gateway.unit.put(observed, Integer.parseInt(params.get(key).textValue()));
                else f.gateway.unit.put(observed, params.get(key).textValue());
                if (key.equals("onOff")) f.gateway.unit.put("unitStatus", params.get(key).textValue().equals("on") ? "operating" : "stopped");
                writes.incrementAndGet(); return response("{\"code\":\"10000\"}");
            }
            assertThat(path).isEqualTo("/v2/equipments/inunit/vendor-22");
            var body = f.json.createObjectNode().put("code", "10000");
            var site = body.putObject("data").putArray("sites").addObject().put("siteId", "site");
            var controller = f.gateway.controller.deepCopy(); controller.putArray("units").add(f.gateway.unit.deepCopy());
            site.putArray("controlers").add(controller);
            return response(body.toString());
        };
        var config = new DaikinClientConfiguration(true, true, URI.create("https://vendor.example.test"),
                new DaikinClientConfiguration.Credentials("offline-app", "offline-pass", "offline-salt", "0123456789abcdef"),
                Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(10), 1_000_000, 1, 1000);
        var client = new DaikinControlClient("source", new DaikinSession(config, new DaikinControlWireCodec(f.json), transport, f.json, f.clock));
        var service = new DaikinControlService(f.props, f.access, f.policy, f.store, List.of(client), f.clock);
        for (var pair : List.of(new String[]{"ON_OFF", "on"}, new String[]{"TEMPERATURE", "26"}, new String[]{"MODE", "heating"},
                new String[]{"AIR_FLOW", "airFlowAuto"}, new String[]{"FAN_SPEED", "high"})) {
            var options = service.options(7, "equipment");
            var op = service.submit(7, "equipment", new Submit(Action.valueOf(pair[0]), pair[1], "offline-" + pair[0], options.revision()), "offline-trace");
            assertThat(op.status()).isEqualTo(Status.ACCEPTED);
            f.stabilize(service);
            assertThat(service.get(7, op.operationId()).status()).isEqualTo(Status.CLOUD_VERIFIED);
            assertThat(service.get(7, op.operationId()).isolated()).isFalse();
        }
        assertThat(writes).hasValue(5);
        assertThat(service.history(7, "equipment", 1, 20).total()).isEqualTo(5);
        assertThat(f.jdbc.queryForList("SELECT after_summary FROM sys_security_audit_event", String.class)).allMatch(s -> !s.contains("offline-token"));
    }
    private static DaikinHttpTransport.Response response(String text) { return new DaikinHttpTransport.Response(200, text.getBytes(StandardCharsets.UTF_8)); }
}
