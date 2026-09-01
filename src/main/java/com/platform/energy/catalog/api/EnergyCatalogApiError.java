package com.platform.energy.catalog.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "能源字典治理错误响应")
/** 能源字典接口的统一机器错误响应说明。 */
public record EnergyCatalogApiError(int code, String errorCode, String msg, boolean success) {
}
