package com.platform.iot.deviceparameter.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "设备参数治理错误响应")
/** 设备参数治理接口的统一错误响应说明。 */
public record DeviceParameterApiError(
        int code,
        String errorCode,
        String msg,
        boolean success) {
}
