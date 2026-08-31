package com.platform.iot.energymetadata.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "能源测点专业属性错误响应")
/** 能源测点属性接口的统一机器错误响应说明。 */
public record EnergyMetadataApiError(int code, String errorCode, String msg, boolean success) {
}
