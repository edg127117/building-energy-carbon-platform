package com.platform.adapter.profile;

import com.fasterxml.jackson.databind.JsonNode;

/** 从内存配置快照中选择一份唯一启用的协议模板。 */
public interface ProtocolProfileProvider {

    ResolvedProtocolProfile resolve(String topic, JsonNode payload);
}
