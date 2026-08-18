package com.platform.hvac.asset.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "资产管理业务错误")
/** 版本化资产 API 的稳定错误响应。 */
public record AssetApiError(
        int code,
        @Schema(allowableValues = {
                "ASSET_UNAUTHORIZED", "ASSET_FORBIDDEN", "ASSET_NOT_FOUND",
                "ASSET_VALIDATION_FAILED", "ASSET_REFERENCE_CONFLICT", "ASSET_STATE_CONFLICT"
        }) String errorCode,
        String msg,
        boolean success) {
}
