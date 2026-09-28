package com.platform.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.*;

/** 固定天气载荷的有界压缩与哈希；跨库重试只重放已持久化内容。 */
@Component
public class WeatherCodec {
    private final ObjectMapper mapper;
    public WeatherCodec(ObjectMapper mapper) { this.mapper=mapper; }
    public String json(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch(Exception e) { throw new IllegalArgumentException("WEATHER_SERIALIZATION_FAILED",e); }
    }
    public <T> T read(String text, Class<T> type) {
        try { return mapper.readValue(text,type); }
        catch(Exception e) { throw new IllegalArgumentException("WEATHER_PAYLOAD_INVALID",e); }
    }
    public String hash(Object o) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json(o).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public byte[] pack(Object o) {
        byte[] raw=json(o).getBytes(StandardCharsets.UTF_8);
        if(raw.length>4*1024*1024) throw new IllegalArgumentException("WEATHER_PAYLOAD_TOO_LARGE");
        try {
            var bytes=new ByteArrayOutputStream();
            try(var gz=new GZIPOutputStream(bytes)) { gz.write(raw); }
            if(bytes.size()>1024*1024) throw new IllegalArgumentException("WEATHER_PAYLOAD_TOO_LARGE");
            return bytes.toByteArray();
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    public <T> T unpack(byte[] data,Class<T> type) {
        try(var gz=new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] raw=gz.readNBytes(4*1024*1024+1);
            if(raw.length>4*1024*1024) throw new IllegalArgumentException("WEATHER_PAYLOAD_TOO_LARGE");
            return read(new String(raw,StandardCharsets.UTF_8),type);
        } catch(IOException e) { throw new IllegalStateException("WEATHER_PAYLOAD_INVALID",e); }
    }
}
