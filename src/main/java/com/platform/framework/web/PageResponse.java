package com.platform.framework.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "稳定分页响应")
/** 新版本列表接口的稳定分页契约，不暴露 MyBatis 分页对象。 */
public record PageResponse<T>(
        @Schema(description = "从 1 开始的页码") long page,
        @Schema(description = "每页条数") long size,
        @Schema(description = "符合条件的总记录数") long total,
        @Schema(description = "当前页数据") List<T> items) {
}
