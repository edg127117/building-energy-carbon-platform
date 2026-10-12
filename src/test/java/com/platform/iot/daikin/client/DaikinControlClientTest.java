package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.control.DaikinControlAccess.Target;
import com.platform.iot.daikin.control.DaikinControlContracts.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DaikinControlClientTest {
    final ObjectMapper json = new ObjectMapper();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);
    static final Target TARGET = new Target("equipment", "building", "identity", "source", "site", "controller", "unit-11", "vendor-22", "pending", 1, 1, true, null, null, null);
    static final String TOKEN = """
            {"code":"10000","data":{"token":"test-token","refreshKey":"test-refresh","expireAt":"2099-01-01T00:00:00Z"}}
            """;
    DaikinClientConfiguration config() {
        return new DaikinClientConfiguration(true, true, URI.create("https://vendor.example.test"),
                new DaikinClientConfiguration.Credentials("test-app", "test-password", "test-salt", "0123456789abcdef"),
                Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(10), 1_000_000, 1, 1000);
    }

    @ParameterizedTest @EnumSource(Action.class)
    void constructsExactPutWithVendorIdEncryptedStringParameterAndSharedToken(Action action) throws Exception {
        var requests = new ArrayList<DaikinHttpTransport.Request>();
        DaikinHttpTransport transport = (request, max) -> {
            requests.add(request);
            return response(request.uri().getPath().equals("/token") ? TOKEN : "{\"code\":\"10000\"}");
        };
        var session = new DaikinSession(config(), new DaikinControlWireCodec(json), transport, json, clock);
        var client = new DaikinControlClient("source", session);
        String value = switch (action) { case ON_OFF -> "on"; case TEMPERATURE -> "26"; case MODE -> "cooling"; case AIR_FLOW -> "airFlowAuto"; case FAN_SPEED -> "low"; };
        assertThat(client.send(TARGET, action, value).code()).isEqualTo("10000");
        var request = requests.getLast();
        assertThat(request.method()).isEqualTo("PUT");
        assertThat(request.uri().getPath()).isEqualTo("/v2/operations/" + action.parameter + "/vendor-22");
        assertThat(request.headers()).containsEntry("token", "test-token").containsEntry("appId", "test-app");
        assertThat(json.readTree(DaikinProtocolCrypto.decrypt(new String(request.body(), StandardCharsets.US_ASCII), "0123456789abcdef")))
                .isEqualTo(json.valueToTree(Map.of(action.parameter, value)));
        assertThat(request.headers().get("sign")).isEqualTo(DaikinProtocolCrypto.sign(Map.of(action.parameter, value), "test-salt"));
        int calls = requests.size();
        var readonly = new DaikinReadonlyClient(session);
        assertThatThrownBy(() -> readonly.read(DaikinEndpoint.CONTROL_ON_OFF, "vendor-22", Map.of("onOff", "on"))).isInstanceOf(DaikinClientException.class);
        assertThat(requests).hasSize(calls);
    }

    @Test void authenticationRejectionAndTransportFailureNeverReplayPut() {
        for (String scenario : List.of("10010", "10007", "LOST", "MALFORMED")) {
            var requests = new ArrayList<DaikinHttpTransport.Request>();
            DaikinHttpTransport transport = (request, max) -> {
                requests.add(request);
                if (request.uri().getPath().equals("/token")) return response(TOKEN);
                if (scenario.equals("LOST")) throw new java.io.IOException("simulated lost response");
                return response(scenario.equals("MALFORMED") ? "{\"code\":\"10000\",\"code\":\"10007\"}" : "{\"code\":\"" + scenario + "\"}");
            };
            var client = new DaikinControlClient("source", new DaikinSession(config(), new DaikinControlWireCodec(json), transport, json, clock));
            if (scenario.equals("LOST") || scenario.equals("MALFORMED")) assertThatThrownBy(() -> client.send(TARGET, Action.ON_OFF, "on")).isInstanceOf(DaikinClientException.class);
            else assertThat(client.send(TARGET, Action.ON_OFF, "on").code()).isEqualTo(scenario);
            assertThat(requests.stream().filter(r -> r.method().equals("PUT"))).hasSize(1);
            assertThat(requests).hasSize(2);
        }
    }

    @Test void singleUnitReadUsesVendorIdAndStillChecksBothReturnedIdentities() {
        var requests = new ArrayList<DaikinHttpTransport.Request>();
        DaikinHttpTransport transport = (request, max) -> {
            requests.add(request);
            return response(request.uri().getPath().equals("/token") ? TOKEN : """
                    {"code":"10000","data":{"sites":[{"siteId":"site","controlers":[{"lcNo":"controller","units":[{"id":"wrong","unitId":"unit-11"}]}]}]}}
                    """);
        };
        var client = new DaikinControlClient("source", new DaikinSession(config(), new DaikinControlWireCodec(json), transport, json, clock));
        assertThatThrownBy(() -> client.read(TARGET)).isInstanceOf(DaikinClientException.class);
        assertThat(requests.getLast().uri().getPath()).isEqualTo("/v2/equipments/inunit/vendor-22");
        assertThat(requests.stream().filter(r -> r.method().equals("PUT"))).isEmpty();
    }
    @Test void observedVendorCasingAndDecimalTemperaturesPreserveSlaveProtection() {
        var requests = new ArrayList<DaikinHttpTransport.Request>();
        DaikinHttpTransport transport = (request, max) -> {
            requests.add(request);
            return response(request.uri().getPath().equals("/token") ? TOKEN : """
                    {"code":"10000","data":{"sites":[{"siteId":"site","controlers":[{
                    "lcNo":"controller","isConnectionUp":true,"inForcedStop":false,"decommissioned":null,
                    "units":[{"id":"vendor-22","unitId":"unit-11","onOff":"Off","mode":"Cooling",
                    "fanSpeed":"Middle","unitStatus":"Stopped","masterSlaveFlag":"slave","masterSlaveIds":["master"],
                    "isGroupSlave":false,"inCommunicationError":false,"inEquipmentError":false,"inMaintenanceMode":false,
                    "temperature":25.0,"DefaultSetpointRange":{"min":16.0,"max":32.0,"step":1.0}}]}]}]}}
                    """);
        };
        var client = new DaikinControlClient("source", new DaikinSession(config(), new DaikinControlWireCodec(json), transport, json, clock));
        var snapshot = client.read(TARGET);
        assertThat(snapshot.unit().path("onOff").textValue()).isEqualTo("off");
        assertThat(snapshot.unit().path("mode").textValue()).isEqualTo("cooling");
        assertThat(snapshot.unit().path("fanSpeed").textValue()).isEqualTo("middle");
        assertThat(snapshot.unit().path("unitStatus").textValue()).isEqualTo("stopped");
        assertThat(snapshot.unit().path("masterSlaveFlag").textValue()).isEqualTo("Slave");
        assertThat(snapshot.unit().path("temperature").decimalValue()).isEqualByComparingTo("25");
        assertThat(new com.platform.iot.daikin.control.DaikinControlPolicy().options(TARGET, snapshot))
                .allMatch(o -> !o.allowed() && "LINKED_DEVICE_UNCONFIRMED".equals(o.reasonCode()));
        assertThat(requests.getLast().uri().getPath()).isEqualTo("/v2/equipments/inunit/vendor-22");
        assertThat(requests.stream().filter(r -> r.method().equals("PUT"))).isEmpty();
    }
    @Test void unknownVendorStateRemainsUnknownAndCannotBecomeSafeReadback() {
        DaikinHttpTransport transport = (request, max) -> response(request.uri().getPath().equals("/token") ? TOKEN : """
                {"code":"10000","data":{"sites":[{"siteId":"site","controlers":[{
                "lcNo":"controller","isConnectionUp":true,"inForcedStop":false,"units":[
                {"id":"vendor-22","unitId":"unit-11","onOff":"FutureState","unitStatus":"FutureStatus"}]}]}]}}
                """);
        var client = new DaikinControlClient("source", new DaikinSession(config(), new DaikinControlWireCodec(json), transport, json, clock));
        var snapshot = client.read(TARGET);
        assertThat(snapshot.unit().path("unitStatus").textValue()).isEqualTo("FutureStatus");
        assertThat(snapshot.unit().path("onOff").textValue()).isEqualTo("FutureState");
        assertThat(new com.platform.iot.daikin.control.DaikinControlPolicy().options(TARGET, snapshot))
                .allMatch(o -> !o.allowed() && "DEVICE_UNAVAILABLE".equals(o.reasonCode()));
    }
    @Test void successWithoutDeviceDataIsRejectedWithoutTryingAnotherIdentityOrSending() {
        var requests = new ArrayList<DaikinHttpTransport.Request>();
        DaikinHttpTransport transport = (request, max) -> {
            requests.add(request);
            return response(request.uri().getPath().equals("/token") ? TOKEN : "{\"code\":\"10000\"}");
        };
        var client = new DaikinControlClient("source", new DaikinSession(config(), new DaikinControlWireCodec(json), transport, json, clock));
        assertThatThrownBy(() -> client.read(TARGET)).isInstanceOf(DaikinClientException.class);
        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().uri().getPath()).isEqualTo("/v2/equipments/inunit/vendor-22");
        assertThat(requests.stream().filter(r -> r.method().equals("PUT"))).isEmpty();
    }
    @Test void unconfirmedTemperatureUnitDisablesWriteAndReadbackWithoutHidingOtherState() {
        var requests = new ArrayList<DaikinHttpTransport.Request>();
        DaikinHttpTransport transport = (request, max) -> {
            requests.add(request);
            return response(request.uri().getPath().equals("/token") ? TOKEN : """
                    {"code":"10000","data":{"sites":[{"siteId":"site","controlers":[{"lcNo":"controller","units":[
                    {"id":"vendor-22","unitId":"unit-11","onOff":"off","temperature":26,"DefaultSetpointRange":{"min":16,"max":32,"step":1}}]}]}]}}
                    """);
        };
        var client = new DaikinControlClient("source", new DaikinSession(config(), new DaikinControlWireCodec(json), transport, json, clock), false);
        var unit = client.read(TARGET).unit();
        assertThat(unit.path("onOff").asText()).isEqualTo("off");
        assertThat(unit.has("temperature")).isFalse();
        assertThat(unit.has("DefaultSetpointRange")).isFalse();
        assertThatThrownBy(() -> client.send(TARGET, Action.TEMPERATURE, "26")).isInstanceOf(DaikinClientException.class);
        assertThat(requests.stream().filter(r -> r.method().equals("PUT"))).isEmpty();
    }
    private static DaikinHttpTransport.Response response(String text) { return new DaikinHttpTransport.Response(200, text.getBytes(StandardCharsets.UTF_8)); }
}
