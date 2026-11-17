package com.platform.iot.roomsensor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomSensorParserTest {
    private static final String GATEWAY_MAC = "AC233FC2B716";
    private static final String DOOR_MAC = "C300008A3BAE";
    private static final String OPEN_FRAME = "0201060EFF3906CA031F8029280990B58617";
    private static final String CLOSED_FRAME = "0201060EFF3906CA031F002B2B09AF37A99C";
    private static final String OPEN_TIME = "2026-11-13T10:58:38.181Z";

    private RoomSensorParser parser;

    @BeforeEach
    void setUp() {
        parser = new RoomSensorParser(new ObjectMapper());
    }

    @Test
    void parsesRadarTargetCountAndSequenceWithoutCoordinates() throws IOException {
        String timestamp = "2026-11-13T10:57:47.575Z";
        String payload = """
                {"mac":"AC233FC2B716","timestamp":"%s","sn":"0",
                 "radar":{"type":"coord","num":"4","coords":[[1,2],[3,4]]}}
                """.formatted(timestamp);

        List<RoomSensorParser.Value> values = parser.parse(radarSource(), payload.getBytes());

        assertEquals(1, values.size());
        RoomSensorParser.Value value = values.get(0);
        assertEquals(timestamp, value.deviceTime());
        assertEquals(4, value.targetCount());
        assertEquals(0, value.deviceSeq());
        assertNull(value.doorOpen());
        assertEquals(List.of("deviceTime", "targetCount", "deviceSeq", "doorOpen"),
                List.of(RoomSensorParser.Value.class.getRecordComponents()).stream()
                        .map(component -> component.getName()).toList());
    }

    @Test
    void rejectsRadarInvalidCountSequenceMacAndUnknownType() throws IOException {
        assertTrue(parseRadar("\"num\":\"-1\",\"type\":\"coord\"", "\"sn\":\"1\"", GATEWAY_MAC).isEmpty());
        assertTrue(parseRadar("\"num\":\"4\",\"type\":\"coord\"", "\"sn\":\"65536\"", GATEWAY_MAC).isEmpty());
        assertTrue(parseRadar("\"num\":\"4\",\"type\":\"coord\"", "\"sn\":\"1\"", "001122334455").isEmpty());
        assertTrue(parseRadar("\"num\":\"4\",\"type\":\"unknown\"", "\"sn\":\"1\"", GATEWAY_MAC).isEmpty());
    }

    @Test
    void parsesRealDoorOpenAndClosedFramesAndPreservesTimestamp() throws IOException {
        String closeTime = "2026-11-13T11:00:38.207Z";
        List<RoomSensorParser.Value> values = parseDoor(doorPayload(
                sensor(DOOR_MAC, OPEN_TIME, OPEN_FRAME),
                sensor(DOOR_MAC, closeTime, CLOSED_FRAME)));

        assertEquals(2, values.size());
        assertEquals(OPEN_TIME, values.get(0).deviceTime());
        assertTrue(values.get(0).doorOpen());
        assertNull(values.get(0).targetCount());
        assertNull(values.get(0).deviceSeq());
        assertEquals(closeTime, values.get(1).deviceTime());
        assertFalse(values.get(1).doorOpen());
    }

    @Test
    void doorStateUsesBitSevenAndIgnoresOtherStatusBits() throws IOException {
        String status81 = OPEN_FRAME.replace("1F80", "1F81");
        String status01 = CLOSED_FRAME.replace("1F00", "1F01");

        List<RoomSensorParser.Value> values = parseDoor(doorPayload(
                sensor(DOOR_MAC, OPEN_TIME, status81), sensor(DOOR_MAC, OPEN_TIME, status01)));

        assertEquals(List.of(true, false), values.stream().map(RoomSensorParser.Value::doorOpen).toList());
    }

    @Test
    void rejectsTruncatedWrongCompanyTypeAndBlockFrames() throws IOException {
        String truncated = "0201060EFF3906CA031F80";
        String wrongCompany = OPEN_FRAME.replace("FF3906", "FF9906");
        String wrongType = OPEN_FRAME.replace("CA031F", "CA021F");
        String wrongBlock = OPEN_FRAME.replace("CA031F", "CA0320");

        List<RoomSensorParser.Value> values = parseDoor(doorPayload(
                sensor(DOOR_MAC, OPEN_TIME, truncated),
                sensor(DOOR_MAC, OPEN_TIME, wrongCompany),
                sensor(DOOR_MAC, OPEN_TIME, wrongType),
                sensor(DOOR_MAC, OPEN_TIME, wrongBlock)));

        assertTrue(values.isEmpty());
    }

    @Test
    void ignoresInfoCombinationFramesAndGatewayHeartbeat() throws IOException {
        String ca00 = "0201061BFF3906CA00AE3B8A0000C364610244000001200000860012BFD7CD";
        String ca02 = "0201060FFF3906CA02623331342D6486FA83C9";
        List<RoomSensorParser.Value> values = parseDoor(doorPayload(
                sensor(DOOR_MAC, OPEN_TIME, ca00),
                sensor(DOOR_MAC, OPEN_TIME, ca02)));

        assertTrue(values.isEmpty());
    }

    @Test
    void rejectsNonArrayWrongGatewayAndWrongSensor() throws IOException {
        assertTrue(parser.parse(doorSource(), "{}".getBytes()).isEmpty());
        assertTrue(parser.parse(doorSource(), "[]".getBytes()).isEmpty());
        assertTrue(parseDoor(doorPayloadWithGateway("001122334455",
                sensor(DOOR_MAC, OPEN_TIME, OPEN_FRAME))).isEmpty());
        assertTrue(parseDoor(doorPayload(
                sensor("001122334455", OPEN_TIME, OPEN_FRAME))).isEmpty());
    }

    @Test
    void retainsEveryValidDoorFrameInOneStatusArray() throws IOException {
        String closeTime = "2026-11-13T11:00:38.207Z";
        List<RoomSensorParser.Value> values = parseDoor(doorPayload(
                sensor(DOOR_MAC, OPEN_TIME, OPEN_FRAME),
                sensor(DOOR_MAC, "2026-11-13T10:59:38.181Z", OPEN_FRAME),
                sensor(DOOR_MAC, closeTime, CLOSED_FRAME)));

        assertEquals(3, values.size());
        assertEquals(List.of(true, true, false), values.stream().map(RoomSensorParser.Value::doorOpen).toList());
    }

    private List<RoomSensorParser.Value> parseRadar(String radarFields, String sequence, String mac) throws IOException {
        String payload = "{\"mac\":\"" + mac + "\"," + sequence + ",\"radar\":{" + radarFields + "}}";
        return parser.parse(radarSource(), payload.getBytes());
    }

    private List<RoomSensorParser.Value> parseDoor(String payload) throws IOException {
        return parser.parse(doorSource(), payload.getBytes());
    }

    private static RoomSensorSource radarSource() {
        return source("MSR01_RADAR", GATEWAY_MAC, GATEWAY_MAC);
    }

    private static RoomSensorSource doorSource() {
        return source("S4_DOOR", GATEWAY_MAC, DOOR_MAC);
    }

    private static RoomSensorSource source(String protocol, String gatewayMac, String deviceMac) {
        return new RoomSensorSource("source-1", protocol, "test/topic", gatewayMac, deviceMac,
                "identity-1", "equip-1", "building-1", "space-1", 0L);
    }

    private static String sensor(String mac, String timestamp, String rawData) {
        return "{\"timestamp\":\"" + timestamp + "\",\"mac\":\"" + mac
                + "\",\"rawData\":\"" + rawData + "\"}";
    }

    private static String doorPayload(String... sensors) {
        return doorPayloadWithGateway(GATEWAY_MAC, sensors);
    }

    private static String doorPayloadWithGateway(String gatewayMac, String... sensors) {
        StringBuilder payload = new StringBuilder("[{\"timestamp\":\"2026-11-13T10:57:47.575Z\",\"type\":\"Gateway\",\"mac\":\"")
                .append(gatewayMac).append("\",\"nums\":0}");
        for (String sensor : sensors) payload.append(',').append(sensor);
        return payload.append(']').toString();
    }
}
