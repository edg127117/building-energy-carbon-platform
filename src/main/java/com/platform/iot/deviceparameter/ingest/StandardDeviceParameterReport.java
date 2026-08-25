package com.platform.iot.deviceparameter.ingest;

import com.platform.iot.identity.DeviceIdentityKey;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 网关/协议适配器提交给平台的独立设备参数上行契约。
 *
 * <p>它不属于遥测热路径，也不接受设备声明建筑或平台设备 ID。具体 Topic、厂家字段路径、
 * 全量/增量语义和配置版本含义必须由部署配置及硬件合同确认。</p>
 */
public record StandardDeviceParameterReport(
        String standardVersion,
        String profileCode,
        int profileVersion,
        DeviceIdentityKey deviceIdentity,
        String reportId,
        String reportSemantics,
        LocalDateTime reportedAt,
        LocalDateTime receivedAt,
        List<Item> parameters) {

    public StandardDeviceParameterReport {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public record Item(
            String sourcePath,
            String rawValue,
            String rawUnit,
            String mappingVersionId,
            String parameterCode) {
    }
}
