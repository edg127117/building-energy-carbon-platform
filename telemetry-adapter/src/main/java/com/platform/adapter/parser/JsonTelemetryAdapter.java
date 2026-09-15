package com.platform.adapter.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 运行适配器的 Spring 入口；全部解析行为继承自平台预览共用的无 I/O 核心。 */
@Component
public class JsonTelemetryAdapter extends JsonTelemetryParser {
    public JsonTelemetryAdapter(ObjectMapper objectMapper) {
        super(objectMapper);
    }
}
