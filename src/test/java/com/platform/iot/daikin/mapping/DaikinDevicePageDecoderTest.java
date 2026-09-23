package com.platform.iot.daikin.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.platform.iot.daikin.model.DaikinFieldValue.Status.*;
import static org.assertj.core.api.Assertions.*;

class DaikinDevicePageDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant received = Instant.parse("2026-09-15T08:00:00Z");
    private final DaikinDevicePageDecoder decoder = new DaikinDevicePageDecoder(
            DaikinDevicePageDecoder.FieldPolicy.unconfirmed());

    @Test
    void retainsSourceIdentityWithoutInventingBuildingOrDeviceTime() throws Exception {
        ObjectNode page = page();
        page.put("resTime", "2020-11-14T13:57:40Z");
        unit(page).put("id", "equipment-001").put("onOff", "off").put("fanSpeed", "high")
                .put("roomTemp", 26.5).put("temperature", 25);
        unit(page).put("token", "must-not-copy");
        var value = decode(page, DaikinDeviceKey.Kind.INDOOR);
        assertThat(value.key()).isEqualTo(new DaikinDeviceKey("source-a", "001", "LC-a",
                DaikinDeviceKey.Kind.INDOOR, "0010"));
        assertThat(value.equipmentId()).isEqualTo("equipment-001");
        assertThat(value.observedAt()).isEqualTo(received);
        assertThat(value.responseTime()).isEqualTo("2020-11-14T13:57:40Z");
        assertThat(value.fields().get("onOff").normalizedValue()).isEqualTo("off");
        assertThat(value.fields().get("fanSpeed").normalizedValue()).isEqualTo("high");
        assertThat(value.fields().get("roomTemp").status()).isEqualTo(UNCONFIRMED);
        assertThat(value.fields().get("roomTemp").normalizedValue()).isNull();
        assertThat(value.toString()).doesNotContain("must-not-copy");
        assertThatThrownBy(() -> value.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void distinguishesMissingUnknownInvalidAndFalseInsteadOfNormalizingToZero() throws Exception {
        ObjectNode page = page();
        unit(page).put("mode", "new-factory-mode").put("isFilterDirty", false)
                .put("inEquipmentError", "false").putNull("roomTemp").put("temperature", "26")
                .put("errorType", 42);
        var fields = decode(page, DaikinDeviceKey.Kind.INDOOR).fields();
        assertThat(fields.get("mode").status()).isEqualTo(UNKNOWN);
        assertThat(fields.get("mode").rawJson()).isEqualTo("\"new-factory-mode\"");
        assertThat(fields.get("onOff").status()).isEqualTo(MISSING);
        assertThat(fields.get("roomTemp").status()).isEqualTo(MISSING);
        assertThat(fields.get("temperature").status()).isEqualTo(INVALID);
        assertThat(fields.get("inEquipmentError").status()).isEqualTo(INVALID);
        assertThat(fields.get("isFilterDirty").status()).isEqualTo(PRESENT);
        assertThat(fields.get("isFilterDirty").normalizedValue()).isEqualTo("false");
        assertThat(fields.get("errorType").status()).isEqualTo(INVALID);
    }

    @Test
    void realMaintenanceFieldUsesExistingInternalKey() throws Exception {
        ObjectNode page = page();
        unit(page).put("inMaintenanceMode", true);
        var fields = decode(page, DaikinDeviceKey.Kind.INDOOR).fields();
        assertThat(fields.get("inMantenanceMode").normalizedValue()).isEqualTo("true");
        assertThat(fields).doesNotContainKey("inMaintenanceMode");
    }

    @Test
    void temperatureRequiresExplicitUnitConfirmationAndDoesNotUseRemoteTemperatureAsRoomTemperature() throws Exception {
        ObjectNode page = page();
        unit(page).put("roomTemp", 0).put("arth1", "27.0");
        var confirmed = new DaikinDevicePageDecoder(new DaikinDevicePageDecoder.FieldPolicy(true, null));
        var fields = confirmed.decode("source-a", DaikinDeviceKey.Kind.INDOOR, page, received)
                .devices().getFirst().fields();
        assertThat(fields.get("roomTemp").normalizedValue()).isEqualTo("0");
        assertThat(fields.get("arth1").status()).isEqualTo(UNCONFIRMED);
        assertThat(fields.get("temperature").status()).isEqualTo(MISSING);
    }

    @Test
    void compressorMappingDoesNotGuessCaseOrCoerceNumericSampleAndDetectsConflicts() throws Exception {
        ObjectNode page = page();
        unit(page).put("mc11", "off");
        var value = decode(page, DaikinDeviceKey.Kind.OUTDOOR);
        assertThat(value.fields()).doesNotContainKey("roomTemp");
        assertThat(value.fields().get("mc11").status()).isEqualTo(UNCONFIRMED);
        var confirmed = new DaikinDevicePageDecoder(new DaikinDevicePageDecoder.FieldPolicy(false, "mc11"));
        var fields = confirmed.decode("source-a", DaikinDeviceKey.Kind.OUTDOOR, page, received)
                .devices().getFirst().fields();
        assertThat(fields.get("compressorOnOff").normalizedValue()).isEqualTo("off");
        unit(page).put("Mc11", "on");
        assertThat(confirmed.decode("source-a", DaikinDeviceKey.Kind.OUTDOOR, page, received)
                .devices().getFirst().fields().get("compressorOnOff").status()).isEqualTo(INVALID);
        unit(page).remove("Mc11");
        unit(page).put("mc11", 0);
        assertThat(confirmed.decode("source-a", DaikinDeviceKey.Kind.OUTDOOR, page, received)
                .devices().getFirst().fields().get("compressorOnOff").status()).isEqualTo(INVALID);
    }

    @Test
    void identitiesPreserveCaseAndLeadingZeroWithoutCollidingAcrossSourcesAndKinds() throws Exception {
        ObjectNode page = page();
        var indoor = decode(page, DaikinDeviceKey.Kind.INDOOR);
        var outdoor = decode(page, DaikinDeviceKey.Kind.OUTDOOR);
        var other = decoder.decode("source-b", DaikinDeviceKey.Kind.INDOOR, page, received).devices().getFirst();
        assertThat(indoor.key()).isNotEqualTo(outdoor.key()).isNotEqualTo(other.key());
        unit(page).put("unitId", 10);
        assertThat(decode(page, DaikinDeviceKey.Kind.INDOOR).key().unitId()).isEqualTo("10");
        unit(page).put("unitId", 10.1);
        assertThatThrownBy(() -> decode(page, DaikinDeviceKey.Kind.INDOOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("DAIKIN_DEVICE_PAGE_INVALID");
    }

    @Test
    void rejectsPartialHierarchyAndResponseErrorsWithoutLeakingVendorDetails() throws Exception {
        ObjectNode page = page();
        page.put("code", "40000").put("codeInfo", "sensitive-response");
        assertThatThrownBy(() -> decode(page, DaikinDeviceKey.Kind.INDOOR))
                .hasMessage("DAIKIN_DEVICE_PAGE_INVALID");
        page.put("code", "10000");
        ((ObjectNode) page.path("data")).remove("sites");
        assertThatThrownBy(() -> decode(page, DaikinDeviceKey.Kind.INDOOR))
                .hasMessage("DAIKIN_DEVICE_PAGE_INVALID");
        ObjectNode noUnits = page();
        ((ObjectNode) noUnits.at("/data/sites/0/controlers/0")).remove("units");
        assertThatThrownBy(() -> decode(noUnits, DaikinDeviceKey.Kind.INDOOR))
                .hasMessage("DAIKIN_DEVICE_PAGE_INVALID");
    }

    @Test
    void rejectsUnspecifiedPaginationRatherThanAssumingSinglePage() throws Exception {
        ObjectNode page = page();
        ((ObjectNode) page.path("data")).remove("totalPages");
        assertThatThrownBy(() -> decode(page, DaikinDeviceKey.Kind.INDOOR))
                .hasMessage("DAIKIN_DEVICE_PAGE_INVALID");
    }

    @Test
    void oversizedOptionalValueIsInvalidInsteadOfTruncatedIntoValidState() throws Exception {
        ObjectNode page = page();
        unit(page).put("fanSpeed", "h".repeat(1100));
        var field = decode(page, DaikinDeviceKey.Kind.INDOOR).fields().get("fanSpeed");
        assertThat(field.status()).isEqualTo(INVALID);
        assertThat(field.rawJson()).isNull();
    }

    @Test
    void exponentCannotExpandIntoUnboundedTemperatureText() throws Exception {
        ObjectNode page = page();
        unit(page).put("roomTemp", new java.math.BigDecimal("1e-1000000"));
        var confirmed = new DaikinDevicePageDecoder(new DaikinDevicePageDecoder.FieldPolicy(true, null));
        assertThat(confirmed.decode("source-a", DaikinDeviceKey.Kind.INDOOR, page, received)
                .devices().getFirst().fields().get("roomTemp").status()).isEqualTo(INVALID);
    }

    @Test
    void explicitEmptyPageIsNotEquivalentToMissingDevicesArray() throws Exception {
        ObjectNode page = page();
        ObjectNode data = (ObjectNode) page.path("data");
        data.put("totalCount", 0).put("totalPages", 0).putArray("sites");
        assertThat(decoder.decode("source-a", DaikinDeviceKey.Kind.INDOOR, page, received).devices()).isEmpty();
    }

    private DaikinDeviceObservation decode(ObjectNode page, DaikinDeviceKey.Kind kind) {
        return decoder.decode("source-a", kind, page, received).devices().getFirst();
    }

    private ObjectNode unit(ObjectNode page) { return (ObjectNode) page.at("/data/sites/0/controlers/0/units/0"); }

    private ObjectNode page() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"code":"10000","data":{"curPage":1,"totalPages":1,"totalCount":1,
                 "sites":[{"siteId":"001","siteName":"隔离测试项目","controlers":[{
                 "lcNo":"LC-a","isConnectionUp":true,"units":[{"unitId":"0010","name":"测试内机"}]}]}]}}
                """);
    }
}
