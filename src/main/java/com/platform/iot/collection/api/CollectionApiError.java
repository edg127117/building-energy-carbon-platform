package com.platform.iot.collection.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "采集治理错误响应")
/** 采集治理 API 的稳定机器错误响应。 */
public record CollectionApiError(
        @Schema(example = "409") int code,
        @Schema(example = "配置已被其他操作修改，请重新提交") String msg,
        @Schema(example = "false") boolean success,
        @Schema(example = "COLLECTION_CONFIG_VERSION_CONFLICT") String errorCode) {}
