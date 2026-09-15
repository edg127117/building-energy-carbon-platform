package com.platform.adapter.profile;

import java.util.List;

/** 已从完整快照中解析出的唯一协议模板及其有序字段映射。 */
public record ResolvedProtocolProfile(
        ProtocolProfile profile,
        List<ProtocolFieldMapping> mappings) {

    public ResolvedProtocolProfile {
        mappings = List.copyOf(mappings);
    }
}
