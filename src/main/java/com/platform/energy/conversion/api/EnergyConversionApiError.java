package com.platform.energy.conversion.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "能源折标规则与模拟计算错误响应")
/** 能源折标接口的统一机器错误响应说明。 */
public record EnergyConversionApiError(int code, String errorCode, String msg, boolean success) {
}
