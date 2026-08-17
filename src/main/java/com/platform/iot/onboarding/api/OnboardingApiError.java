package com.platform.iot.onboarding.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "设备接入业务错误")
/** 新版本设备接入接口的稳定错误响应。 */
public record OnboardingApiError(
        @Schema(description = "HTTP 语义业务码", example = "409") int code,
        @Schema(description = "稳定机器错误码", allowableValues = {
                "ONBOARDING_UNAUTHORIZED",
                "ONBOARDING_FORBIDDEN",
                "ONBOARDING_NOT_FOUND",
                "ONBOARDING_STATE_CONFLICT",
                "ONBOARDING_VALIDATION_FAILED",
                "ONBOARDING_DUPLICATE",
                "ONBOARDING_CONFIG_PENDING"
        }) String errorCode,
        @Schema(description = "脱敏的人类可读提示") String msg,
        @Schema(description = "固定为 false") boolean success) {
}
