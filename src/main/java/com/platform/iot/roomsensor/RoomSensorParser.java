package com.platform.iot.roomsensor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** 从可信网关报文提取观测值和通讯证据。CA00、CA02 只更新通讯证据。 */
@Component
public class RoomSensorParser {
    private final ObjectMapper mapper;

    public RoomSensorParser(ObjectMapper mapper) { this.mapper = mapper; }

    public record Value(String deviceTime, Integer targetCount, Integer deviceSeq, Boolean doorOpen) { }

    public record Parsed(List<Value> values, boolean communicationEvidence) {
        private static final Parsed EMPTY = new Parsed(List.of(), false);
    }

    record Frame(String gatewayMac, String protocol, Map<String, Parsed> devices) {
        private static final Frame EMPTY = new Frame("", "", Map.of());
    }

    public Parsed parse(RoomSensorSource source, byte[] payload) throws IOException {
        Frame frame = decode(payload);
        if (!source.protocol().equals(frame.protocol()) || !source.gatewayMac().equals(frame.gatewayMac()))
            return Parsed.EMPTY;
        return frame.devices().getOrDefault(source.deviceMac(), Parsed.EMPTY);
    }

    Frame decode(byte[] payload) throws IOException {
        JsonNode root = mapper.readTree(payload);
        if (root == null) return Frame.EMPTY;
        if (root.isObject()) {
            String gateway = mac(root.path("mac"));
            JsonNode radar = root.path("radar");
            Integer count = unsigned(radar.path("num"), Integer.MAX_VALUE);
            Integer seq = unsigned(root.path("sn"), 65535);
            if (!validMac(gateway) || !"coord".equals(radar.path("type").asText()) || count == null || seq == null)
                return Frame.EMPTY;
            return new Frame(gateway, "MSR01_RADAR",
                    Map.of(gateway, new Parsed(List.of(new Value(time(root), count, seq, null)), true)));
        }
        if (!root.isArray() || root.isEmpty() || !"Gateway".equals(root.get(0).path("type").asText()))
            return Frame.EMPTY;
        String gateway = mac(root.get(0).path("mac"));
        if (!validMac(gateway)) return Frame.EMPTY;
        Map<String, List<Value>> observations = new LinkedHashMap<>();
        for (int i = 1; i < root.size(); i++) {
            JsonNode item = root.get(i);
            String child = mac(item.path("mac"));
            if (!validMac(child) || child.equals(gateway)) continue;
            DoorFrame frame = doorFrame(item.path("rawData").asText(), child);
            if (frame == null) continue;
            List<Value> values = observations.computeIfAbsent(child, ignored -> new ArrayList<>());
            if (frame.open() != null) values.add(new Value(time(item), null, null, frame.open()));
        }
        Map<String, Parsed> devices = new LinkedHashMap<>();
        observations.forEach((child, values) -> devices.put(child, new Parsed(List.copyOf(values), true)));
        return new Frame(gateway, "S4_DOOR", devices);
    }

    static String topic(String protocol, String gatewayMac) {
        return "/MSR01/" + gatewayMac.toLowerCase(Locale.ROOT)
                + ("MSR01_RADAR".equals(protocol) ? "/radar" : "/status");
    }

    /** 这两个协议的身份由原生主题和网关帧确立，标准报文不能声明其归属。 */
    public static boolean isNativeProfile(String profile) {
        return "MSR01_RADAR".equalsIgnoreCase(profile) || "S4_DOOR".equalsIgnoreCase(profile);
    }

    private static boolean validMac(String value) {
        return value.matches("[0-9A-F]{12}");
    }

    private static String mac(JsonNode node) {
        return node.isTextual() ? node.textValue().toUpperCase(Locale.ROOT) : "";
    }

    private static Integer unsigned(JsonNode node, int max) {
        String text = node.isTextual() || node.isIntegralNumber() ? node.asText() : "";
        if (!text.matches("[0-9]{1,10}")) return null;
        try { long value = Long.parseLong(text); return value <= max ? (int) value : null; }
        catch (NumberFormatException invalid) { return null; }
    }

    private static String time(JsonNode node) {
        JsonNode time = node.path("timestamp");
        return time.isTextual() && time.textValue().getBytes(StandardCharsets.UTF_8).length <= 64
                ? time.textValue() : null;
    }

    private record DoorFrame(Boolean open) { }

    private static DoorFrame doorFrame(String raw, String deviceMac) {
        if (raw.length() > 510 || (raw.length() & 1) != 0) return null;
        byte[] bytes;
        try { bytes = HexFormat.of().parseHex(raw); }
        catch (IllegalArgumentException invalid) { return null; }
        Boolean state = null;
        boolean info = false;
        for (int offset = 0; offset < bytes.length;) {
            int length = bytes[offset] & 0xff;
            if (length == 0) break;
            if (offset + length >= bytes.length) return null;
            if (length >= 5 && (bytes[offset + 1] & 0xff) == 0xff
                    && (bytes[offset + 2] & 0xff) == 0x39 && (bytes[offset + 3] & 0xff) == 0x06
                    && (bytes[offset + 4] & 0xff) == 0xca) {
                int version = bytes[offset + 5] & 0xff;
                if (version == 3 && length == 14 && (bytes[offset + 6] & 0xff) == 0x1f) {
                    if (state != null) return null;
                    // bit7 为门开，其它位可能是附带状态。
                    state = (bytes[offset + 7] & 0x80) != 0;
                } else if (version == 0 && length == 27) {
                    // CA00 的小端 MAC 必须与网关标注的子设备一致。
                    byte[] mac = new byte[6];
                    for (int i = 0; i < mac.length; i++) mac[i] = bytes[offset + 11 - i];
                    info |= deviceMac.equals(HexFormat.of().withUpperCase().formatHex(mac));
                } else if (version == 2 && length == 15) {
                    info = true;
                }
            }
            offset += length + 1;
        }
        return state != null || info ? new DoorFrame(state) : null;
    }
}
